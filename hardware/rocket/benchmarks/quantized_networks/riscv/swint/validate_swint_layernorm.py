from pathlib import Path
import json
import subprocess

import numpy as np
import torch

REPO = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-layernorm-riscv-20261029"
EVD = REPO / "results/model_execution/swint_layernorm_riscv_20261029"

C_SOURCE = r"""
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <stdint.h>

static float *read_f32(const char *path, size_t n) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }

    float *a = malloc(n * sizeof(float));
    if (!a) { perror("malloc"); exit(2); }

    if (fread(a, sizeof(float), n, f) != n) {
        fprintf(stderr, "Incorrect tensor size: %s\n", path);
        exit(2);
    }
    if (fgetc(f) != EOF) {
        fprintf(stderr, "Unexpected extra data: %s\n", path);
        exit(2);
    }
    fclose(f);
    return a;
}

int main(int argc, char **argv) {
    if (argc != 7) {
        fprintf(stderr, "Usage: layernorm input weight bias output rows cols\n");
        return 2;
    }

    size_t rows = strtoull(argv[5], NULL, 10);
    size_t cols = strtoull(argv[6], NULL, 10);

    if (!rows || !cols || rows > SIZE_MAX / cols) return 2;

    size_t n = rows * cols;
    float *x = read_f32(argv[1], n);
    float *w = read_f32(argv[2], cols);
    float *b = read_f32(argv[3], cols);
    float *y = malloc(n * sizeof(float));
    if (!y) return 2;

    for (size_t r = 0; r < rows; ++r) {
        size_t offset = r * cols;
        double mean = 0.0;
        double variance = 0.0;

        for (size_t c = 0; c < cols; ++c)
            mean += x[offset + c];
        mean /= (double)cols;

        for (size_t c = 0; c < cols; ++c) {
            double d = (double)x[offset + c] - mean;
            variance += d * d;
        }

        double inv_std = 1.0 / sqrt(variance / (double)cols + 1e-5);

        for (size_t c = 0; c < cols; ++c) {
            double normalized = ((double)x[offset + c] - mean) * inv_std;
            y[offset + c] = (float)(
                normalized * (double)w[c] + (double)b[c]
            );
        }
    }

    FILE *f = fopen(argv[4], "wb");
    if (!f) { perror(argv[4]); return 2; }
    if (fwrite(y, sizeof(float), n, f) != n) return 2;
    if (fclose(f) != 0) return 2;

    free(x);
    free(w);
    free(b);
    free(y);
    return 0;
}
"""

def run(args):
    print("$", " ".join(map(str, args)), flush=True)
    subprocess.run(args, check=True)

def main():
    assert (REF / "manifest.json").is_file()
    assert (REF / "wrapped_model_state.pt").is_file()

    manifest = json.loads((REF / "manifest.json").read_text())
    norms = [
        x for x in manifest["trace"]
        if x["type"] == "LayerNorm"
    ]
    assert len(norms) == 30, len(norms)

    state = torch.load(
        REF / "wrapped_model_state.pt",
        map_location="cpu",
        weights_only=True,
    )

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    c_file = HERE / "swint_layernorm.c"
    c_file.write_text(C_SOURCE)

    native = WORK / "swint_layernorm_native"
    riscv = WORK / "swint_layernorm_riscv"

    run([
        "gcc", "-O2", "-std=c11",
        "-Wall", "-Wextra", "-ffp-contract=off",
        str(c_file), "-lm", "-o", str(native),
    ])

    run([
        "riscv64-linux-gnu-gcc",
        "-O2", "-std=c11", "-static",
        "-march=rv64gc", "-mabi=lp64d",
        "-ffp-contract=off",
        str(c_file), "-lm", "-o", str(riscv),
    ])

    records = []

    for index, entry in enumerate(norms):
        name = entry["name"]

        x = np.asarray(
            np.load(REF / entry["input"]["file"]),
            dtype=np.float32,
        )
        expected = np.asarray(
            np.load(REF / entry["output"]["file"]),
            dtype=np.float32,
        )

        cols = x.shape[-1]
        rows = x.size // cols

        weight = state[name + ".weight"].cpu().numpy().astype("float32")
        bias = state[name + ".bias"].cpu().numpy().astype("float32")

        assert weight.size == bias.size == cols
        assert x.shape == expected.shape

        prefix = WORK / f"norm_{index:02d}"

        inputs = [
            prefix.with_suffix(".input.bin"),
            prefix.with_suffix(".weight.bin"),
            prefix.with_suffix(".bias.bin"),
        ]

        x.tofile(inputs[0])
        weight.tofile(inputs[1])
        bias.tofile(inputs[2])

        errors = {}

        for platform, command in (
            ("native", [str(native)]),
            ("riscv_qemu", ["qemu-riscv64", str(riscv)]),
        ):
            output_file = WORK / f"norm_{index:02d}_{platform}.bin"

            subprocess.run(
                command + [
                    *map(str, inputs),
                    str(output_file),
                    str(rows),
                    str(cols),
                ],
                check=True,
            )

            actual = np.fromfile(output_file, dtype=np.float32)
            assert actual.size == expected.size

            actual = actual.reshape(expected.shape)
            diff = np.abs(actual - expected)

            mismatches = int(np.count_nonzero(
                ~np.isclose(actual, expected, atol=3e-5, rtol=1e-4)
            ))

            errors[platform] = {
                "max_abs_error": float(diff.max()),
                "mismatches": mismatches,
            }

            if mismatches:
                raise AssertionError(
                    f"{platform} failed {name}: "
                    f"{mismatches} mismatches, maxerr={diff.max()}"
                )

        records.append({
            "module": name,
            "shape": list(expected.shape),
            "native": errors["native"],
            "riscv_qemu": errors["riscv_qemu"],
        })

        print(
            f"PASS {index+1:02d}/30 {name:<39} "
            f"native={errors['native']['max_abs_error']:.3g} "
            f"qemu={errors['riscv_qemu']['max_abs_error']:.3g}",
            flush=True,
        )

    results = {
        "model": "Mahdi Swin-T CIFAR100",
        "operator": "LayerNorm",
        "reference_calls": len(norms),
        "native_passed": len(records),
        "riscv_qemu_passed": len(records),
        "tolerance": {"atol": 3e-5, "rtol": 1e-4},
        "all_mismatches": 0,
        "full_model_riscv_inference": False,
        "physical_fpga_tested": False,
        "records": records,
    }

    (EVD / "results.json").write_text(
        json.dumps(results, indent=2) + "\n"
    )

    print("\nPASS: All 30 LayerNorm calls on native Linux")
    print("PASS: All 30 LayerNorm calls on RISC-V QEMU")
    print("Evidence:", EVD / "results.json")

if __name__ == "__main__":
    main()

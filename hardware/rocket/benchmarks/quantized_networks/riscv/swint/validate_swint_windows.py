from pathlib import Path
import json
import subprocess
import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-windows-riscv-20261029"
EVD = REPO / "results/model_execution/swint_windows_riscv_20261029"

C_SOURCE = r"""
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static float *readf(const char *path, size_t n) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }
    float *a = malloc(n * sizeof(float));
    if (!a) exit(2);
    if (fread(a, sizeof(float), n, f) != n || fgetc(f) != EOF) {
        fprintf(stderr, "Invalid input: %s\n", path);
        exit(2);
    }
    fclose(f);
    return a;
}

int main(int argc, char **argv) {
    if (argc != 8) return 2;

    size_t S = strtoull(argv[5], NULL, 10);
    size_t C = strtoull(argv[6], NULL, 10);
    size_t shift = strtoull(argv[7], NULL, 10);

    if (!S || !C || S % 7 || shift > 3) return 2;

    int partition = strcmp(argv[1], "partition") == 0;
    int residual = strcmp(argv[1], "residual") == 0;
    if (!partition && !residual) return 2;

    size_t count = S*S*C;
    size_t nw = S/7;

    float *a = readf(argv[2], count);
    float *b = residual ? readf(argv[3], count) : NULL;
    float *y = malloc(count * sizeof(float));
    if (!y) return 2;

    for (size_t wr = 0; wr < nw; wr++)
    for (size_t wc = 0; wc < nw; wc++)
    for (size_t r = 0; r < 7; r++)
    for (size_t c = 0; c < 7; c++)
    for (size_t d = 0; d < C; d++) {
        size_t h = (wr*7 + r + shift) % S;
        size_t w = (wc*7 + c + shift) % S;
        size_t spatial = (h*S + w)*C + d;
        size_t window = (((wr*nw+wc)*49+r*7+c)*C)+d;

        if (partition)
            y[window] = a[spatial];
        else
            y[spatial] = b[spatial] + a[window];
    }

    FILE *f = fopen(argv[4], "wb");
    if (!f) { perror(argv[4]); return 2; }
    if (fwrite(y, sizeof(float), count, f) != count) return 2;
    if (fclose(f)) return 2;

    free(a);
    free(b);
    free(y);
    return 0;
}
"""

def execute(args):
    subprocess.run(list(map(str, args)), check=True)

def load(info):
    return np.ascontiguousarray(
        np.load(REF / info["file"]), dtype=np.float32
    )

def verify(file, expected, label):
    actual = np.fromfile(file, dtype=np.float32)
    assert actual.size == expected.size, label
    actual = actual.reshape(expected.shape)

    good = np.isclose(
        actual, expected, atol=3e-5, rtol=1e-4
    )
    mismatches = int(good.size - np.count_nonzero(good))
    error = float(np.max(np.abs(actual - expected)))

    if mismatches:
        raise AssertionError(
            f"{label}: mismatches={mismatches}, maxerr={error}"
        )

    return {"max_abs_error": error, "mismatches": mismatches}

def main():
    manifest = json.loads((REF / "manifest.json").read_text())
    trace = manifest["trace"]
    by_name = {x["name"]: x for x in trace}

    blocks = [
        x for x in trace
        if x["type"] == "SwinTransformerBlock"
    ]
    assert len(blocks) == 12

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    source = HERE / "swint_windows.c"
    source.write_text(C_SOURCE)

    native = WORK / "windows_native"
    riscv = WORK / "windows_riscv"

    execute([
        "gcc", "-O2", "-std=c11",
        "-Wall", "-Wextra", "-ffp-contract=off",
        source, "-o", native
    ])
    execute([
        "riscv64-linux-gnu-gcc", "-O2", "-std=c11",
        "-static", "-march=rv64gc", "-mabi=lp64d",
        "-ffp-contract=off", source, "-o", riscv
    ])

    records = []

    for index, block in enumerate(blocks):
        name = block["name"]

        original = load(block["input"])
        norm1 = load(by_name[name + ".norm1"]["output"])
        attn_input = load(by_name[name + ".attn"]["input"])
        attn_output = load(by_name[name + ".attn"]["output"])
        expected_residual = load(by_name[name + ".norm2"]["input"])

        _, S, W, C = original.shape
        assert S == W
        assert norm1.shape == original.shape
        assert attn_input.shape == attn_output.shape
        assert expected_residual.size == original.size

        block_index = int(name.split(".blocks.")[1])
        shift = 3 if block_index % 2 and S > 7 else 0

        prefix = WORK / f"block_{index:02d}"

        paths = {
            "norm": Path(str(prefix) + "_norm.bin"),
            "attn": Path(str(prefix) + "_attn.bin"),
            "original": Path(str(prefix) + "_original.bin"),
        }

        norm1.tofile(paths["norm"])
        attn_output.tofile(paths["attn"])
        original.tofile(paths["original"])

        for platform, runner in (
            ("native", [native]),
            ("riscv_qemu", ["qemu-riscv64", riscv])
        ):
            partition_file = WORK / f"block_{index:02d}_{platform}_partition.bin"
            residual_file = WORK / f"block_{index:02d}_{platform}_residual.bin"

            execute([
                *runner, "partition",
                paths["norm"], "none", partition_file,
                S, C, shift
            ])

            p = verify(
                partition_file, attn_input,
                f"{name}/{platform}/partition"
            )

            execute([
                *runner, "residual",
                paths["attn"], paths["original"],
                residual_file, S, C, shift
            ])

            r = verify(
                residual_file, expected_residual,
                f"{name}/{platform}/residual"
            )

            records.append({
                "block": name,
                "platform": platform,
                "shift": shift,
                "partition": p,
                "residual": r
            })

        print(
            f"PASS {index+1:02d}/12 {name} "
            f"shift={shift}: native + RISC-V QEMU",
            flush=True
        )

    assert len(records) == 24

    result = {
        "model": "Mahdi Swin-T CIFAR100",
        "blocks": 12,
        "platform_validations": 24,
        "numerical_checks": 48,
        "all_mismatches": 0,
        "full_model_riscv_inference": False,
        "records": records
    }

    path = EVD / "results.json"
    path.write_text(json.dumps(result, indent=2) + "\n")

    print("\nPASS: 12/12 window partitions on both platforms")
    print("PASS: 12/12 window reconstruction and residual checks")
    print("PASS: 48/48 numerical checks")
    print("Evidence:", path)

if __name__ == "__main__":
    main()

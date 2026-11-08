from pathlib import Path
import json
import subprocess
import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-gelu-riscv-20261029"
EVD = REPO / "results/model_execution/swint_gelu_riscv_20261029"

def run(args):
    subprocess.run([str(a) for a in args], check=True)

def tensor(info):
    return np.ascontiguousarray(
        np.load(REF / info["file"]),
        dtype=np.float32
    )

def main():
    manifest = json.loads(
        (REF / "manifest.json").read_text()
    )

    activations = [
        x for x in manifest["trace"]
        if x["type"] == "GELU"
    ]

    assert len(activations) == 12, len(activations)

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    source = HERE / "swint_gelu.c"
    native = WORK / "gelu_native"
    riscv = WORK / "gelu_riscv"

    run([
        "gcc", "-O2", "-std=c11",
        "-Wall", "-Wextra", "-ffp-contract=off",
        source, "-lm", "-o", native
    ])

    run([
        "riscv64-linux-gnu-gcc",
        "-O2", "-std=c11", "-static",
        "-march=rv64gc", "-mabi=lp64d",
        "-ffp-contract=off",
        source, "-lm", "-o", riscv
    ])

    records = []

    for index, entry in enumerate(activations):
        name = entry["name"]
        x = tensor(entry["input"])
        expected = tensor(entry["output"])

        assert x.shape == expected.shape
        assert np.all(np.isfinite(x))

        input_file = WORK / f"gelu_{index:02d}_input.bin"
        x.tofile(input_file)

        checks = {}

        for platform, launcher in (
            ("native", [native]),
            ("riscv_qemu", ["qemu-riscv64", riscv])
        ):
            output_file = (
                WORK / f"gelu_{index:02d}_{platform}.bin"
            )

            run([
                *launcher,
                input_file,
                output_file,
                x.size
            ])

            actual = np.fromfile(
                output_file, dtype=np.float32
            ).reshape(expected.shape)

            difference = np.abs(actual - expected)
            mismatch = int(np.count_nonzero(
                ~np.isclose(
                    actual, expected,
                    atol=3e-5,
                    rtol=1e-4
                )
            ))

            max_error = float(difference.max())

            checks[platform] = {
                "mismatches": mismatch,
                "max_abs_error": max_error
            }

            if mismatch:
                raise AssertionError(
                    f"{name}/{platform}: "
                    f"{mismatch} mismatches, "
                    f"maxerr={max_error}"
                )

        records.append({
            "module": name,
            "shape": list(x.shape),
            "native": checks["native"],
            "riscv_qemu": checks["riscv_qemu"]
        })

        print(
            f"PASS {index+1:02d}/12 {name}: "
            "native + RISC-V QEMU",
            flush=True
        )

    result = {
        "model": "Mahdi Swin-T CIFAR100",
        "operator": "GELU exact-erf approximation",
        "reference_calls": 12,
        "native_passed": 12,
        "riscv_qemu_passed": 12,
        "all_mismatches": 0,
        "tolerance": {
            "atol": 3e-5,
            "rtol": 1e-4
        },
        "full_model_riscv_inference": False,
        "records": records
    }

    destination = EVD / "results.json"
    destination.write_text(
        json.dumps(result, indent=2) + "\n"
    )

    print("\nPASS: 12/12 GELU operations on native Linux")
    print("PASS: 12/12 GELU operations on RISC-V QEMU")
    print("Evidence:", destination)

if __name__ == "__main__":
    main()

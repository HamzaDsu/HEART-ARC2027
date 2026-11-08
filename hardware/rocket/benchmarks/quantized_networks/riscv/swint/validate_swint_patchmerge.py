from pathlib import Path
import json
import subprocess
import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-patchmerge-riscv-20261029"
EVD = REPO / "results/model_execution/swint_patchmerge_riscv_20261029"

def run(args):
    subprocess.run(list(map(str, args)), check=True)

def tensor(info):
    return np.ascontiguousarray(
        np.load(REF / info["file"]), dtype=np.float32
    )

def main():
    manifest = json.loads((REF / "manifest.json").read_text())
    trace = manifest["trace"]
    by_name = {x["name"]: x for x in trace}

    merges = [x for x in trace if x["type"] == "PatchMerging"]
    assert len(merges) == 3

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    src = HERE / "swint_patchmerge.c"
    native = WORK / "patchmerge_native"
    riscv = WORK / "patchmerge_riscv"

    run(["gcc", "-O2", "-std=c11", "-Wall", "-Wextra",
         src, "-o", native])

    run(["riscv64-linux-gnu-gcc", "-O2", "-std=c11",
         "-static", "-march=rv64gc", "-mabi=lp64d",
         src, "-o", riscv])

    records = []

    for index, entry in enumerate(merges):
        name = entry["name"]
        original = tensor(entry["input"])
        expected = tensor(by_name[name + ".norm"]["input"])

        assert original.ndim == 4
        assert original.shape[0] == 1

        _, H, W, C = original.shape
        assert expected.shape == (1, H//2, W//2, 4*C)

        input_file = WORK / f"merge_{index:02d}_input.bin"
        original.tofile(input_file)

        for platform, launcher in (
            ("native", [native]),
            ("riscv_qemu", ["qemu-riscv64", riscv]),
        ):
            output_file = WORK / f"merge_{index:02d}_{platform}.bin"

            run([
                *launcher, input_file, output_file,
                H, W, C
            ])

            actual = np.fromfile(
                output_file, dtype=np.float32
            ).reshape(expected.shape)

            mismatches = int(np.count_nonzero(actual != expected))

            records.append({
                "module": name,
                "platform": platform,
                "input_shape": list(original.shape),
                "output_shape": list(expected.shape),
                "mismatches": mismatches,
            })

            if mismatches:
                raise AssertionError(
                    f"{name} {platform}: {mismatches} mismatches"
                )

        print(
            f"PASS {index+1}/3 {name}: native + RISC-V QEMU",
            flush=True
        )

    assert len(records) == 6

    result = {
        "model": "Mahdi Swin-T CIFAR100",
        "operator": "PatchMerging tensor rearrangement",
        "patchmerge_stages": 3,
        "platform_checks": 6,
        "all_mismatches": 0,
        "normalization_and_reduction_included": False,
        "full_riscv_inference": False,
        "records": records,
    }

    path = EVD / "results.json"
    path.write_text(json.dumps(result, indent=2) + "\n")

    print("\nPASS: All 3 PatchMerging rearrangements")
    print("PASS: Native Linux and RISC-V QEMU")
    print("Evidence:", path)

if __name__ == "__main__":
    main()

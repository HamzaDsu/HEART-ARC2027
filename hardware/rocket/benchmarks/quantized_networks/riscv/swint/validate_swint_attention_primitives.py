from pathlib import Path
import json
import subprocess
import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-attention-riscv-20261029"
EVD = REPO / "results/model_execution/swint_attention_primitives_riscv_20261029"

def run(command):
    print("$", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True)

def tensor(info):
    return np.ascontiguousarray(
        np.load(REF / info["file"]), dtype=np.float32
    )

def verify(path, expected, label):
    actual = np.fromfile(path, dtype=np.float32)
    assert actual.size == expected.size, label
    actual = actual.reshape(expected.shape)
    atol, rtol = 3e-5, 2e-4
    ok = np.isclose(actual, expected, atol=atol, rtol=rtol)
    mismatches = int(np.size(ok) - np.count_nonzero(ok))
    error = float(np.max(np.abs(actual - expected)))
    if mismatches:
        raise AssertionError(
            f"{label}: mismatches={mismatches}, max_error={error}"
        )
    return {"max_abs_error": error, "mismatches": mismatches}

def main():
    manifest = json.loads((REF / "manifest.json").read_text())
    trace = manifest["trace"]
    by_name = {item["name"]: item for item in trace}
    attention = [
        item for item in trace if item["type"] == "WindowAttention"
    ]
    assert len(attention) == 12

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    source = HERE / "swint_attention_primitives.c"
    native = WORK / "attention_native"
    riscv = WORK / "attention_riscv"

    run(["gcc", "-O2", "-std=c11", "-ffp-contract=off",
         source, "-lm", "-o", native])
    run(["riscv64-linux-gnu-gcc", "-O2", "-std=c11",
         "-march=rv64gc", "-mabi=lp64d", "-static",
         "-ffp-contract=off", source, "-lm", "-o", riscv])

    records = []

    for index, module in enumerate(attention):
        name = module["name"]
        qkv = tensor(by_name[name + ".qkv"]["output"])
        scores = tensor(by_name[name + ".softmax"]["input"])
        probabilities = tensor(by_name[name + ".softmax"]["output"])
        expected_value = tensor(by_name[name + ".proj"]["input"])

        B, H, N, N2 = probabilities.shape
        assert N == N2 == 49
        C = expected_value.shape[-1]
        assert qkv.shape == (B, N, 3*C)
        assert expected_value.shape == (B, N, C)
        assert scores.shape == probabilities.shape

        prefix = WORK / f"block_{index:02d}"
        paths = {
            "qkv": Path(str(prefix) + "_qkv.bin"),
            "scores": Path(str(prefix) + "_scores.bin"),
            "prob": Path(str(prefix) + "_prob.bin"),
        }

        qkv.tofile(paths["qkv"])
        scores.tofile(paths["scores"])
        probabilities.tofile(paths["prob"])

        for platform, runner in (
            ("native", [native]),
            ("riscv_qemu", ["qemu-riscv64", riscv]),
        ):
            soft_out = Path(str(prefix) + f"_{platform}_soft.bin")
            value_out = Path(str(prefix) + f"_{platform}_value.bin")

            subprocess.run([
                *map(str, runner),
                "softmax", str(paths["scores"]), str(soft_out),
                str(B*H*N), str(N)
            ], check=True)

            soft_result = verify(
                soft_out, probabilities, f"{name}/{platform}/softmax"
            )

            subprocess.run([
                *map(str, runner),
                "value", str(paths["qkv"]), str(paths["prob"]),
                str(value_out), str(B), str(H), str(N), str(C)
            ], check=True)

            value_result = verify(
                value_out, expected_value, f"{name}/{platform}/value"
            )

            records.append({
                "block": name,
                "platform": platform,
                "softmax": soft_result,
                "attention_value": value_result,
            })

        print(f"PASS {index+1:02d}/12 {name}: native + RISC-V QEMU",
              flush=True)

    assert len(records) == 24

    results = {
        "model": "Mahdi Swin-T CIFAR100",
        "timm_version": "0.9.16",
        "attention_blocks": 12,
        "platform_validations": 24,
        "kernel_checks": 48,
        "all_mismatches": 0,
        "softmax": "Validated against captured Softmax output",
        "attention_value": "Validated against projection input",
        "attention_scores_and_masks": "Not yet independently implemented",
        "full_model_riscv_inference": False,
        "physical_fpga_tested": False,
        "records": records,
    }

    destination = EVD / "results.json"
    destination.write_text(json.dumps(results, indent=2) + "\n")

    print("\nPASS: 12/12 Softmax calls on both platforms")
    print("PASS: 12/12 attention-value products on both platforms")
    print("PASS: 48/48 numerical checks")
    print("Evidence:", destination)

if __name__ == "__main__":
    main()

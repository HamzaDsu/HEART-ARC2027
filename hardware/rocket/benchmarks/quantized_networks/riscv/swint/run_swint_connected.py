from pathlib import Path
import argparse
import json
import subprocess

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]

REF = (
    Path.home() /
    "health-slm-model-validation/swint-full-reference-export-20261029"
)
RUNTIME = (
    Path.home() /
    "health-slm-model-validation/swint-full-runtime-20261029"
)
WORK = (
    Path.home() /
    "health-slm-model-validation/swint-connected-runs-20261029"
)
EVIDENCE = (
    REPO /
    "results/model_execution/swint_full_c_riscv_qemu_20261029"
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--platform",
        choices=["native", "riscv"],
        default="native",
    )
    args = parser.parse_args()

    ref = json.loads((REF / "manifest.json").read_text())
    runtime = json.loads(
        (EVIDENCE / "runtime_manifest.json").read_text()
    )

    layers = runtime["quantized_layers"]
    assert len(layers) == 53
    assert len(runtime["layernorm_layers"]) == 30
    assert len(runtime["attention_layers"]) == 12

    head = next(
        entry for entry in ref["trace"]
        if entry["name"] == "head.0"
    )

    head_shape = head["input"]["shape"]

    if len(head_shape) == 2:
        head_mode = 0
    elif len(head_shape) in (3, 4):
        head_mode = 1
    else:
        raise ValueError(
            f"Unexpected head.0 input shape: {head_shape}"
        )

    WORK.mkdir(parents=True, exist_ok=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)

    config = WORK / "swint_config.txt"

    with config.open("w") as f:
        f.write(f"{len(layers)} {head_mode}\n")
        for layer in layers:
            f.write(
                f"{layer['name']} "
                f"{layer['activation_bits']} "
                f"{int(layer['calibrated'])} "
                f"{layer['activation_scale']:.9g} "
                f"{layer['activation_zero_point']:.9g}\n"
            )

    print("===== CONNECTED SWIN-T CONFIGURATION =====", flush=True)
    print("Quantized layers:", len(layers), flush=True)
    print("Head.0 reference shape:", head_shape, flush=True)
    print("Head mode:", head_mode, flush=True)
    print("Platform:", args.platform, flush=True)

    source = HERE / "swint_connected.c"
    executable = WORK / f"swint_connected_{args.platform}"

    compiler = (
        "gcc" if args.platform == "native"
        else "riscv64-linux-gnu-gcc"
    )

    command = [
        compiler, "-O2", "-std=c11",
        "-Wall", "-Wextra", "-Werror",
        "-ffp-contract=off",
    ]

    if args.platform == "riscv":
        command += [
            "-march=rv64gc", "-mabi=lp64d", "-static"
        ]

    command += [str(source), "-lm", "-o", str(executable)]

    subprocess.run(command, check=True)

    dump = WORK / f"dumps_{args.platform}"
    dump.mkdir(exist_ok=True)

    output = WORK / f"logits_{args.platform}.bin"
    stdout = WORK / f"{args.platform}_stdout.txt"
    stderr = WORK / f"{args.platform}_stderr.txt"

    invocation = (
        [str(executable)]
        if args.platform == "native"
        else ["qemu-riscv64", str(executable)]
    )

    invocation += [
        str(RUNTIME),
        str(config),
        str(output),
        str(dump),
    ]

    print(
        "\n===== RUNNING CONNECTED SWIN-T INFERENCE =====",
        flush=True,
    )

    with stdout.open("w") as so, stderr.open("w") as se:
        result = subprocess.run(
            invocation,
            stdout=so,
            stderr=se,
            check=False,
        )

    print(stderr.read_text())
    print(stdout.read_text())

    if result.returncode != 0:
        raise RuntimeError(
            f"Connected executable exited with {result.returncode}"
        )

    trace = {
        entry["name"]: entry
        for entry in ref["trace"]
        if entry["type"] == "SwinTransformerBlock"
    }

    assert len(trace) == 12, len(trace)

    print("===== REFERENCE BLOCK COMPARISON =====")

    records = []

    for stage, blocks in enumerate([2, 2, 6, 2]):
        for block in range(blocks):
            name = f"layers.{stage}.blocks.{block}"

            expected = np.load(
                REF / trace[name]["output"]["file"],
                allow_pickle=False,
            ).astype(np.float32)

            actual_file = dump / f"{name}.bin"
            actual = np.fromfile(
                actual_file, dtype=np.float32
            )

            assert actual.size == expected.size, name
            actual = actual.reshape(expected.shape)

            valid = np.isclose(
                actual, expected,
                atol=5e-3,
                rtol=5e-3,
            )

            mismatches = int(valid.size - valid.sum())
            max_error = float(
                np.max(np.abs(actual - expected))
            )

            records.append({
                "block": name,
                "mismatches": mismatches,
                "max_abs_error": max_error,
                "passed": mismatches == 0,
            })

            status = "PASS" if mismatches == 0 else "FAIL"

            print(
                f"{status} {name}: "
                f"mismatches={mismatches}, "
                f"max_error={max_error:.7g}"
            )

    reference_logits = np.fromfile(
        RUNTIME / "reference_logits.bin",
        dtype=np.float32,
    )
    actual_logits = np.fromfile(
        output, dtype=np.float32,
    )

    assert reference_logits.shape == (100,)
    assert actual_logits.shape == (100,)

    logits_close = np.isclose(
        actual_logits,
        reference_logits,
        atol=1e-2,
        rtol=5e-3,
    )

    logit_mismatches = int(
        logits_close.size - logits_close.sum()
    )

    max_logit_error = float(
        np.max(np.abs(actual_logits - reference_logits))
    )

    predicted_class = int(actual_logits.argmax())
    expected_class = int(reference_logits.argmax())

    passed = (
        all(item["passed"] for item in records)
        and logit_mismatches == 0
        and predicted_class == expected_class
    )

    summary = {
        "model": "Mahdi Swin-T CIFAR100",
        "execution": "connected C model",
        "platform": args.platform,
        "reference_class": expected_class,
        "predicted_class": predicted_class,
        "logit_mismatches": logit_mismatches,
        "max_logit_error": max_logit_error,
        "blocks_checked": len(records),
        "blocks_passed": sum(
            int(x["passed"]) for x in records
        ),
        "validation_passed": passed,
        "block_records": records,
        "physical_fpga_tested": False,
    }

    destination = (
        EVIDENCE /
        f"connected_{args.platform}_validation.json"
    )

    destination.write_text(
        json.dumps(summary, indent=2) + "\n"
    )

    print("\n===== CONNECTED INFERENCE SUMMARY =====")
    print("Reference class:", expected_class)
    print("C predicted class:", predicted_class)
    print("Block checks passed:", summary["blocks_passed"], "/12")
    print("Logit mismatches:", logit_mismatches)
    print("Maximum logit error:", max_logit_error)
    print("Validation passed:", passed)
    print("Evidence:", destination)

    if not passed:
        raise SystemExit(
            "FAIL: Numerical differences detected. "
            "See the block comparison above."
        )

    print(
        f"PASS: Complete Swin-T inference on {args.platform}"
    )


if __name__ == "__main__":
    main()

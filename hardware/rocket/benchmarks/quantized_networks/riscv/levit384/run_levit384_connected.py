from pathlib import Path
import argparse
import json
import subprocess

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]

REF = Path.home() / "health-slm-model-validation/levit384-full-reference-export-20261029"
RUNTIME = Path.home() / "health-slm-model-validation/levit384-full-runtime-20261029"
WORK = Path.home() / "health-slm-model-validation/levit384-connected-runs-20261029"
EVD = REPO / "results/model_execution/levit384_full_c_riscv_qemu_20261029"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--platform", choices=["native", "riscv"],
        default="native"
    )
    args = parser.parse_args()

    reference = json.loads(
        (REF / "manifest.json").read_text()
    )
    runtime = json.loads(
        (RUNTIME / "runtime_manifest.json").read_text()
    )

    qlayers = runtime["quantized_layers"]
    attention = runtime["attention_layers"]
    downs = runtime["downsample_layers"]

    assert len(qlayers) == 62
    assert len(attention) == 14
    assert len(downs) == 2
    assert len(runtime["batchnorm_layers"]) == 62

    # Check assumptions encoded in the C engine.
    for layer in qlayers:
        if layer["type"] != "IntegerConv2d":
            continue

        conv = layer["conv"]
        assert layer["weights"]["shape"][2:] == [3, 3]
        assert conv["stride"] == [2, 2]
        assert conv["padding"] == [1, 1]
        assert conv["dilation"] == [1, 1]
        assert conv["groups"] == 1

    for bn in runtime["batchnorm_layers"]:
        assert abs(bn["epsilon"] - 1e-5) < 1e-9

    for entry in attention:
        heads, nq, nk = entry["bias"]["shape"]
        assert heads == entry["num_heads"]
        assert nq > 0 and nk > 0

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    config = WORK / "levit384_config.txt"

    with config.open("w") as f:
        f.write(
            f"{len(qlayers)} {len(attention)} {len(downs)}\n"
        )

        for layer in qlayers:
            f.write(
                f"{layer['name']} "
                f"{layer['activation_bits']} "
                f"{int(layer['calibrated'])} "
                f"{layer['activation_scale']:.9g} "
                f"{layer['activation_zero_point']:.9g}\n"
            )

        for item in attention:
            heads, nq, nk = item["bias"]["shape"]

            f.write(
                f"{item['name']} "
                f"{heads} "
                f"{item['key_dim']} "
                f"{item['val_dim']} "
                f"{item['scale']:.9g} "
                f"{nq} {nk}\n"
            )

        for stage in (1, 2):
            entry = next(
                x for x in downs
                if x["name"].startswith(f"stages.{stage}.")
            )

            resolution = entry["resolution"]
            assert len(resolution) == 2
            assert resolution[0] == resolution[1]
            assert entry["stride"] == 2

            pool = entry["pool_type"]
            assert pool in (None, "AvgPool2d"), pool

            f.write(
                f"{stage} {resolution[0]} "
                f"{entry['stride']} "
                f"{int(pool is not None)}\n"
            )

    print("===== LEVIT384 RUNTIME CONFIGURATION =====", flush=True)
    print("Quantized layers:", len(qlayers), flush=True)
    print("Attention modules:", len(attention), flush=True)
    print("Platform:", args.platform, flush=True)

    executable = WORK / f"levit384_connected_{args.platform}"

    compiler = (
        "gcc" if args.platform == "native"
        else "riscv64-linux-gnu-gcc"
    )

    build = [
        compiler, "-O2", "-std=c11",
        "-Wall", "-Wextra", "-Werror",
        "-ffp-contract=off"
    ]

    if args.platform == "riscv":
        build += [
            "-march=rv64gc", "-mabi=lp64d", "-static"
        ]

    build += [
        str(HERE / "levit384_connected.c"),
        "-lm", "-o", str(executable)
    ]

    subprocess.run(build, check=True)

    dumps = WORK / f"dumps_{args.platform}"
    dumps.mkdir(exist_ok=True)

    output = WORK / f"logits_{args.platform}.bin"
    stdout = WORK / f"{args.platform}_stdout.txt"
    stderr = WORK / f"{args.platform}_stderr.txt"

    command = (
        [str(executable)]
        if args.platform == "native"
        else ["qemu-riscv64", str(executable)]
    )

    command += [
        str(RUNTIME),
        str(config),
        str(output),
        str(dumps)
    ]

    print("\n===== EXECUTE COMPLETE LEVIT384 =====", flush=True)

    with stdout.open("w") as out, stderr.open("w") as err:
        result = subprocess.run(
            command, stdout=out, stderr=err,
            check=False
        )

    print(stderr.read_text(), flush=True)
    print(stdout.read_text(), flush=True)

    if result.returncode != 0:
        raise SystemExit(
            f"FAIL: C forward exited with code {result.returncode}"
        )

    print("===== COMPARE QUANTIZED LAYER OUTPUTS =====")

    trace = {
        item["name"]: item
        for item in reference["trace"]
        if item["type"] in (
            "IntegerLinear", "IntegerConv2d"
        )
    }

    assert len(trace) == 62

    records = []

    for layer in qlayers:
        name = layer["name"]
        expected = np.load(
            REF / trace[name]["output"]["file"],
            allow_pickle=False
        ).astype(np.float32)

        dump_name = name.replace(".", "_") + ".bin"
        actual_file = dumps / dump_name

        actual = np.fromfile(
            actual_file, dtype=np.float32
        )

        assert actual.size == expected.size, (
            name, actual.size, expected.size
        )

        actual = actual.reshape(expected.shape)

        difference = np.abs(actual - expected)

        valid = np.isclose(
            actual, expected,
            atol=2e-4, rtol=3e-4
        )

        mismatches = int(np.count_nonzero(~valid))
        max_error = float(np.max(difference))

        records.append({
            "name": name,
            "mismatches": mismatches,
            "max_abs_error": max_error,
            "passed": mismatches == 0
        })

        print(
            f"{'PASS' if mismatches == 0 else 'FAIL'} "
            f"{name}: mismatches={mismatches}, "
            f"max_error={max_error:.8g}",
            flush=True
        )

    actual_logits = np.fromfile(
        output, dtype=np.float32
    )
    expected_logits = np.load(
        REF / reference["logits"]["file"],
        allow_pickle=False
    ).reshape(-1)

    assert actual_logits.shape == (10,)
    assert expected_logits.shape == (10,)

    logits_close = np.isclose(
        actual_logits, expected_logits,
        atol=1e-2, rtol=5e-3
    )

    logit_mismatches = int(
        np.count_nonzero(~logits_close)
    )

    predicted = int(actual_logits.argmax())
    reference_class = int(expected_logits.argmax())

    passed_layers = sum(
        int(r["passed"]) for r in records
    )

    validated = (
        passed_layers == 62
        and logit_mismatches == 0
        and predicted == reference_class
    )

    summary = {
        "model": "Mahdi LeViT384 CIFAR10",
        "execution": "full_connected_C",
        "platform": args.platform,
        "forward_execution_completed": True,
        "reference_class": reference_class,
        "predicted_class": predicted,
        "quantized_layers_checked": 62,
        "quantized_layers_passed": passed_layers,
        "logit_mismatches": logit_mismatches,
        "max_logit_error": float(
            np.max(np.abs(actual_logits - expected_logits))
        ),
        "reference_validation_passed": validated,
        "records": records,
        "physical_fpga_tested": False,
    }

    destination = (
        EVD / f"connected_{args.platform}_validation.json"
    )

    destination.write_text(
        json.dumps(summary, indent=2) + "\n"
    )

    print("\n===== FINAL VALIDATION SUMMARY =====")
    print("Reference class:", reference_class)
    print("C predicted class:", predicted)
    print("Quantized layers passing:", passed_layers, "/62")
    print("Logit mismatches:", logit_mismatches)
    print("Strict validation:", validated)
    print("Evidence:", destination)

    if not validated:
        raise SystemExit(
            "Numerical validation failed; inspect the earliest "
            "divergent layer above."
        )

    print(
        f"PASS: Complete LeViT384 inference on {args.platform}"
    )


if __name__ == "__main__":
    main()

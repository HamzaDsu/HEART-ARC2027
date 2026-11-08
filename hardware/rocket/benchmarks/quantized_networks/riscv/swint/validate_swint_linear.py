from pathlib import Path
import argparse
import json
import subprocess

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-linear-riscv-20261029"
EVD = REPO / "results/model_execution/swint_linear_riscv_20261029"


def tensor(info):
    return np.asarray(
        np.load(REF / info["file"]), dtype=np.float32
    )


def run(args):
    subprocess.run([str(a) for a in args], check=True)


def quantize_weights(layer):
    attrs = layer["attributes"]
    data = layer["tensors"]

    weights = torch.from_numpy(
        tensor(data["weight_float"]).copy()
    )
    assert weights.ndim == 2

    bits = attrs["weight_bits"]
    assert len(bits) == weights.shape[0]
    assert all(2 <= int(b) <= 8 for b in bits)

    qweight = torch.empty_like(weights, dtype=torch.int8)
    scales = torch.empty(
        weights.shape[0], dtype=torch.float32
    )

    for bw in sorted(set(bits)):
        indices = torch.tensor(
            [i for i, b in enumerate(bits) if b == bw],
            dtype=torch.long,
        )

        group = weights[indices]
        qmax = 2 ** (int(bw) - 1) - 1
        qmin = -(2 ** (int(bw) - 1))

        scale = (
            group.abs().amax(dim=1) / qmax
        ).clamp(min=1e-8)

        qweight[indices] = torch.round(
            group / scale[:, None]
        ).clamp(qmin, qmax).to(torch.int8)

        scales[indices] = scale

    saved_scales = torch.from_numpy(
        tensor(data["scale_w"]).copy()
    ).reshape(-1)

    assert torch.allclose(
        scales, saved_scales, rtol=1e-5, atol=1e-7
    ), layer["name"]

    if "bias_float" in data:
        bias = tensor(data["bias_float"]).reshape(-1)
    else:
        bias = np.zeros(weights.shape[0], np.float32)

    return qweight.numpy(), saved_scales.numpy(), bias


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--limit", type=int, default=3)
    args = parser.parse_args()

    manifest = json.loads(
        (REF / "manifest.json").read_text()
    )

    trace = {
        x["name"]: x for x in manifest["trace"]
    }

    layers = [
        x for x in manifest["quantization"]
        if x["type"] == "IntegerLinear"
    ]
    assert len(layers) == 52, len(layers)

    # Test smaller operations first.
    layers.sort(
        key=lambda x:
        int(np.prod(x["tensors"]["weight_float"]["shape"]))
        * (
            int(np.prod(trace[x["name"]]["input"]["shape"]))
            // int(x["tensors"]["weight_float"]["shape"][-1])
        )
    )

    selected = layers if args.all else layers[:args.limit]
    assert selected

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    source = HERE / "swint_integer_linear.c"
    native = WORK / "linear_native"
    riscv = WORK / "linear_riscv"

    run([
        "gcc", "-O2", "-std=c11",
        "-Wall", "-Wextra", "-ffp-contract=off",
        source, "-lm", "-o", native,
    ])

    run([
        "riscv64-linux-gnu-gcc", "-O2",
        "-std=c11", "-Wall", "-Wextra",
        "-static", "-march=rv64gc", "-mabi=lp64d",
        "-ffp-contract=off",
        source, "-lm", "-o", riscv,
    ])

    records = []

    for index, layer in enumerate(selected):
        name = layer["name"]
        x = tensor(trace[name]["input"])
        expected = tensor(trace[name]["output"])

        weights, scales, bias = quantize_weights(layer)

        cout, cin = weights.shape
        rows = x.size // cin

        assert x.shape[-1] == cin
        assert expected.shape == (*x.shape[:-1], cout)
        assert np.all(np.isfinite(x))

        attrs = layer["attributes"]
        bits = int(attrs["act_bit_width"])
        calibrated = bool(attrs["calibrated"])

        if calibrated:
            sx = float(tensor(
                layer["tensors"]["act_scale"]
            ).item())
            zp = float(tensor(
                layer["tensors"]["act_zero_point"]
            ).item())
        else:
            sx, zp = 0.0, 0.0

        prefix = WORK / f"layer_{index:02d}"

        files = [
            Path(str(prefix) + suffix)
            for suffix in (
                "_input.bin", "_weights.bin",
                "_scales.bin", "_bias.bin",
            )
        ]

        x.tofile(files[0])
        weights.tofile(files[1])
        scales.tofile(files[2])
        bias.tofile(files[3])

        errors = {}

        for platform, launcher in (
            ("native", [native]),
            ("riscv_qemu", ["qemu-riscv64", riscv]),
        ):
            output = Path(
                str(prefix) + f"_{platform}.bin"
            )

            run([
                *launcher, *files, output,
                rows, cin, cout,
                bits, int(calibrated), sx, zp,
            ])

            actual = np.fromfile(
                output, dtype=np.float32
            )
            assert actual.size == expected.size
            actual = actual.reshape(expected.shape)

            mismatches = int(np.count_nonzero(
                ~np.isclose(
                    actual, expected,
                    atol=2e-4, rtol=3e-4,
                )
            ))

            maxerr = float(
                np.max(np.abs(actual - expected))
            )

            errors[platform] = {
                "mismatches": mismatches,
                "max_abs_error": maxerr,
            }

            if mismatches:
                raise AssertionError(
                    f"{name}/{platform}: "
                    f"mismatches={mismatches}, "
                    f"maxerr={maxerr}"
                )

        records.append({
            "name": name,
            "rows": rows,
            "in_features": cin,
            "out_features": cout,
            "calibrated": calibrated,
            "native": errors["native"],
            "riscv_qemu": errors["riscv_qemu"],
        })

        print(
            f"PASS {index+1:02d}/{len(selected)} "
            f"{name}: native + RISC-V QEMU",
            flush=True,
        )

    summary = {
        "model": "Mahdi Swin-T CIFAR100",
        "operator": "IntegerLinear",
        "total_layers": len(layers),
        "tested_layers": len(records),
        "complete_operator_validation": (
            len(records) == len(layers)
        ),
        "full_model_riscv_inference": False,
        "records": records,
    }

    output = EVD / (
        "results.json" if args.all
        else "smoke_results.json"
    )
    output.write_text(
        json.dumps(summary, indent=2) + "\n"
    )

    print(
        f"\nPASS: {len(records)}/{len(selected)} "
        "linear layers on native and RISC-V QEMU"
    )
    print("Evidence:", output)


if __name__ == "__main__":
    main()

from pathlib import Path
import json
import subprocess
import numpy as np
import torch

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-patch-conv-riscv-20261029"
EVD = REPO / "results/model_execution/swint_patch_conv_riscv_20261029"

def tensor(info):
    return torch.from_numpy(
        np.load(REF / info["file"]).copy()
    ).float()

def main():
    manifest = json.loads((REF / "manifest.json").read_text())

    layer = next(
        x for x in manifest["quantization"]
        if x["name"] == "patch_embed.proj"
    )
    trace = next(
        x for x in manifest["trace"]
        if x["name"] == "patch_embed.proj"
    )

    x = tensor(trace["input"])
    expected = tensor(trace["output"]).numpy()

    attrs = layer["attributes"]
    data = layer["tensors"]
    w = tensor(data["weight_float"])

    assert tuple(x.shape) == (1, 3, 224, 224)
    assert tuple(w.shape) == (96, 3, 4, 4)
    assert tuple(expected.shape) == (1, 96, 56, 56)

    for key, value in (
        ("stride", (4, 4)),
        ("padding", (0, 0)),
        ("dilation", (1, 1)),
    ):
        assert tuple(attrs[key]) == value, (key, attrs[key])

    assert attrs["groups"] == 1

    bits = attrs["weight_bits"]
    assert len(bits) == 96

    weight_q = torch.empty_like(w, dtype=torch.int8)
    scale = torch.empty(96, dtype=torch.float32)

    for bw in sorted(set(bits)):
        idx = torch.tensor(
            [i for i, b in enumerate(bits) if b == bw],
            dtype=torch.long,
        )
        group = w[idx]
        qmax = 2 ** (int(bw)-1)-1
        qmin = -(2 ** (int(bw)-1))

        s = (
            group.abs().reshape(len(idx), -1)
            .amax(dim=1) / qmax
        ).clamp(min=1e-8)

        weight_q[idx] = torch.round(
            group / s[:, None, None, None]
        ).clamp(qmin, qmax).to(torch.int8)
        scale[idx] = s

    saved_scale = tensor(data["scale_w"]).reshape(-1)
    assert torch.allclose(
        scale, saved_scale, rtol=1e-5, atol=1e-7
    )

    actbits = int(attrs["act_bit_width"])
    qmax = 2 ** actbits - 1

    if attrs["calibrated"]:
        sx = tensor(data["act_scale"]).reshape(())
        zp = tensor(data["act_zero_point"]).reshape(())
    else:
        sx = ((x.max()-x.min())/qmax).clamp(min=1e-8)
        zp = torch.round(-x.min()/sx).clamp(0, qmax)

    xq = torch.round(x/sx + zp).clamp(0, qmax) - zp
    xq = xq.to(torch.int16).numpy()

    bias = (
        tensor(data["bias_float"]).numpy()
        if "bias_float" in data
        else np.zeros(96, dtype=np.float32)
    )

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    paths = [
        WORK / "input_q.bin",
        WORK / "weight_q.bin",
        WORK / "scale.bin",
        WORK / "bias.bin",
    ]

    xq.tofile(paths[0])
    weight_q.numpy().tofile(paths[1])
    saved_scale.numpy().tofile(paths[2])
    bias.tofile(paths[3])

    source = HERE / "swint_patch_conv.c"
    native = WORK / "patch_conv_native"
    riscv = WORK / "patch_conv_riscv"

    for compiler, output, flags in (
        ("gcc", native, []),
        (
            "riscv64-linux-gnu-gcc", riscv,
            ["-march=rv64gc", "-mabi=lp64d", "-static"]
        ),
    ):
        subprocess.run(
            [
                compiler, "-O2", "-std=c11",
                "-ffp-contract=off", *flags,
                str(source), "-o", str(output)
            ],
            check=True,
        )

    records = []

    for platform, command in (
        ("native", [str(native)]),
        ("riscv_qemu", ["qemu-riscv64", str(riscv)]),
    ):
        output = WORK / f"output_{platform}.bin"

        subprocess.run(
            [
                *command, *map(str, paths),
                str(output), "224", "224", "3", "96",
                str(float(sx)),
            ],
            check=True,
        )

        actual = np.fromfile(
            output, dtype=np.float32
        ).reshape(expected.shape)

        good = np.isclose(
            actual, expected, atol=2e-4, rtol=3e-4
        )
        mismatch = int(good.size - np.count_nonzero(good))
        maxerr = float(np.max(np.abs(actual - expected)))

        print(
            f"{platform}: mismatches={mismatch} "
            f"max_error={maxerr:.8g}",
            flush=True,
        )

        records.append({
            "platform": platform,
            "mismatches": mismatch,
            "max_abs_error": maxerr,
        })

    result = {
        "model": "Mahdi Swin-T CIFAR100",
        "operator": "Quantized patch embedding convolution",
        "shape": list(expected.shape),
        "input_quantization": "Reproduced from CPU reference in Python",
        "native_and_riscv_validation": True,
        "all_mismatches": sum(x["mismatches"] for x in records),
        "full_riscv_inference": False,
        "records": records,
    }

    destination = EVD / "results.json"
    destination.write_text(
        json.dumps(result, indent=2) + "\n"
    )

    assert result["all_mismatches"] == 0, (
        "Patch convolution numerical mismatch"
    )

    print("PASS: Swin-T patch convolution on both platforms")
    print("Evidence:", destination)

if __name__ == "__main__":
    main()

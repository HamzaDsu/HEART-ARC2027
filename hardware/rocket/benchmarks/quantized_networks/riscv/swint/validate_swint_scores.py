from pathlib import Path
import json
import subprocess

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-attention-scores-riscv-20261029"
EVD = REPO / "results/model_execution/swint_attention_scores_riscv_20261029"

def command(args):
    print("$", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True)

def get(info):
    return np.ascontiguousarray(
        np.load(REF / info["file"]), dtype=np.float32
    )

def main():
    manifest = json.loads((REF / "manifest.json").read_text())
    by_name = {e["name"]: e for e in manifest["trace"]}
    attention = [
        e for e in manifest["trace"]
        if e["type"] == "WindowAttention"
    ]
    assert len(attention) == 12

    state = torch.load(
        REF / "wrapped_model_state.pt",
        map_location="cpu",
        weights_only=True,
    )

    WORK.mkdir(parents=True, exist_ok=True)
    EVD.mkdir(parents=True, exist_ok=True)

    source = HERE / "swint_attention_scores.c"
    native = WORK / "scores_native"
    riscv = WORK / "scores_riscv"

    command([
        "gcc", "-O2", "-std=c11", "-Wall", "-Wextra",
        "-ffp-contract=off", source, "-lm", "-o", native,
    ])
    command([
        "riscv64-linux-gnu-gcc", "-O2", "-std=c11",
        "-Wall", "-Wextra", "-static",
        "-march=rv64gc", "-mabi=lp64d",
        "-ffp-contract=off", source, "-lm", "-o", riscv,
    ])

    records = []

    for i, entry in enumerate(attention):
        name = entry["name"]
        block = name.removesuffix(".attn")

        qkv = get(by_name[name + ".qkv"]["output"])
        expected = get(by_name[name + ".softmax"]["input"])

        spatial_shape = by_name[block]["input"]["shape"]
        assert spatial_shape[0] == 1
        assert spatial_shape[1] == spatial_shape[2]
        side = spatial_shape[1]

        bias = state[
            name + ".relative_position_bias_table"
        ].cpu().numpy().astype("float32")

        B, N, threeC = qkv.shape
        _, H, N2, N3 = expected.shape
        C = threeC // 3

        assert N == N2 == N3 == 49
        assert threeC == 3*C
        assert expected.shape == (B, H, 49, 49)
        assert bias.shape == (169, H)
        assert B == (side // 7)**2

        block_index = int(block.split(".blocks.")[1])
        shift = 3 if block_index % 2 and side > 7 else 0

        prefix = WORK / f"block_{i:02d}"
        qkv_path = Path(str(prefix) + "_qkv.bin")
        bias_path = Path(str(prefix) + "_bias.bin")

        qkv.tofile(qkv_path)
        bias.tofile(bias_path)

        for platform, launcher in (
            ("native", [native]),
            ("riscv_qemu", ["qemu-riscv64", riscv]),
        ):
            output = Path(str(prefix) + "_" + platform + ".bin")

            command([
                *launcher, qkv_path, bias_path,
                output, B, H, C, side, shift,
            ])

            actual = np.fromfile(
                output, dtype=np.float32
            ).reshape(expected.shape)

            mismatch = int(np.count_nonzero(
                ~np.isclose(
                    actual, expected,
                    atol=1e-4, rtol=2e-4,
                )
            ))
            maxerr = float(np.max(np.abs(actual - expected)))

            records.append({
                "block": name,
                "platform": platform,
                "shift": shift,
                "max_abs_error": maxerr,
                "mismatches": mismatch,
            })

            if mismatch:
                raise AssertionError(
                    f"{name} {platform}: "
                    f"mismatches={mismatch}, maxerr={maxerr}"
                )

        print(
            f"PASS {i+1:02d}/12 {name} "
            f"shift={shift}: native + RISC-V QEMU",
            flush=True,
        )

    result = {
        "model": "Mahdi Swin-T CIFAR100",
        "timm_version": "0.9.16",
        "attention_blocks": 12,
        "platform_checks": len(records),
        "all_mismatches": 0,
        "atol": 1e-4,
        "rtol": 2e-4,
        "full_riscv_inference": False,
        "records": records,
    }

    path = EVD / "results.json"
    path.write_text(json.dumps(result, indent=2) + "\n")

    print("\nPASS: 12/12 attention-score blocks on native and RISC-V QEMU")
    print("Evidence:", path)

if __name__ == "__main__":
    main()

from pathlib import Path
import json
import subprocess

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-connected-runs-20261029"
EVD = REPO / "results/model_execution/swint_full_c_riscv_qemu_20261029"

m = json.loads((REF / "manifest.json").read_text())
trace = {x["name"]: x for x in m["trace"]}

def load(name):
    info = trace[name]["output"]
    return np.load(
        REF / info["file"], allow_pickle=False
    ).astype(np.float32)

prefix = "layers.0.blocks.0.mlp"
fc1 = load(prefix + ".fc1")
reference = load(prefix + ".act")

assert fc1.shape == reference.shape

quant = next(
    x for x in m["quantization"]
    if x["name"] == prefix + ".fc2"
)

attrs = quant["attributes"]
assert not attrs["calibrated"]

bits = int(attrs["act_bit_width"])
qmax = 2 ** bits - 1

def codes(data):
    x = torch.from_numpy(
        np.ascontiguousarray(data)
    )
    sx = ((x.max() - x.min()) / qmax).clamp(min=1e-8)
    zp = (-x.min() / sx).round().clamp(0, qmax)
    q = (x / sx + zp).round().clamp(0, qmax)
    return q.to(torch.int32).numpy()

reference_codes = codes(reference)

WORK.mkdir(parents=True, exist_ok=True)
EVD.mkdir(parents=True, exist_ok=True)

exe = WORK / "swint_gelu_precision_probe"
subprocess.run([
    "gcc", "-O2", "-std=c11",
    "-ffp-contract=off",
    str(HERE / "swint_gelu_precision_probe.c"),
    "-lm", "-o", str(exe),
], check=True)

input_file = WORK / "gelu_probe_fc1.bin"
fc1.tofile(input_file)

names = [
    "original_float_erf",
    "double_precision_erf",
    "float_erfc",
    "double_precision_erfc",
    "float_with_double_erf",
]

results = []

print("===== GELU PRECISION COMPARISON =====")
print("Input elements:", fc1.size)
print("Activation bits:", bits)

for mode, name in enumerate(names):
    output = WORK / f"gelu_probe_mode_{mode}.bin"

    subprocess.run([
        str(exe), str(input_file),
        str(output), str(fc1.size), str(mode)
    ], check=True)

    actual = np.fromfile(
        output, dtype=np.float32
    ).reshape(reference.shape)

    actual_codes = codes(actual)
    different = np.argwhere(
        actual_codes != reference_codes
    )

    max_error = float(
        np.max(np.abs(actual - reference))
    )

    record = {
        "mode": mode,
        "name": name,
        "quantized_code_differences": int(len(different)),
        "max_gelu_abs_error": max_error,
    }
    results.append(record)

    print(
        f"MODE {mode}: {name} | "
        f"code_differences={len(different)} | "
        f"max_gelu_error={max_error:.9g}"
    )

    for position in different[:3]:
        idx = tuple(int(i) for i in position)
        print(
            f"  {idx}: reference_code="
            f"{reference_codes[idx]}, "
            f"candidate_code={actual_codes[idx]}"
        )

best = min(
    results,
    key=lambda r: (
        r["quantized_code_differences"],
        r["max_gelu_abs_error"],
    ),
)

summary = {
    "model": "Mahdi Swin-T CIFAR100",
    "operator": prefix + ".act",
    "downstream": prefix + ".fc2",
    "reference_codes": int(reference_codes.size),
    "results": results,
    "best_candidate": best,
    "connected_model_validated": False,
}

destination = EVD / "gelu_precision_probe.json"
destination.write_text(
    json.dumps(summary, indent=2) + "\n"
)

print("\n===== BEST CANDIDATE =====")
print("Mode:", best["mode"])
print("Formula:", best["name"])
print(
    "Remaining code differences:",
    best["quantized_code_differences"],
)
print("Evidence:", destination)

if best["quantized_code_differences"] == 0:
    print("READY: Test this formula in the connected engine")
else:
    print("NEEDS INVESTIGATION: No exact quantized-code match")

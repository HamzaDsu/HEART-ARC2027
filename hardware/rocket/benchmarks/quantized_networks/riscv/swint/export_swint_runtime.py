from pathlib import Path
import hashlib
import json

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
OUT = Path.home() / "health-slm-model-validation/swint-full-runtime-20261029"
EVD = REPO / "results/model_execution/swint_full_c_riscv_qemu_20261029"

assert (REF / "manifest.json").is_file()
assert (REF / "wrapped_model_state.pt").is_file()
assert not OUT.exists(), f"Output exists: {OUT}"

manifest = json.loads((REF / "manifest.json").read_text())
state = torch.load(
    REF / "wrapped_model_state.pt",
    map_location="cpu",
    weights_only=True,
)

assert len(manifest["quantization"]) == 53
assert manifest["predicted_class"] == 39

OUT.mkdir(parents=True)
EVD.mkdir(parents=True, exist_ok=True)

def load(info):
    return np.asarray(
        np.load(REF / info["file"], allow_pickle=False),
        dtype=np.float32,
    )

def save(name, array):
    path = OUT / name
    array = np.ascontiguousarray(array)
    array.tofile(path)
    return {
        "file": name,
        "shape": list(array.shape),
        "dtype": str(array.dtype),
        "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }

def safe(name):
    return name.replace(".", "_")

quantized = []

for layer in manifest["quantization"]:
    name = layer["name"]
    kind = layer["type"]
    attrs = layer["attributes"]
    data = layer["tensors"]

    weights = torch.from_numpy(
        load(data["weight_float"]).copy()
    )

    bits = [int(v) for v in attrs["weight_bits"]]
    assert len(bits) == weights.shape[0]
    assert all(2 <= b <= 8 for b in bits)

    qweights = torch.empty_like(weights, dtype=torch.int8)
    scales = torch.empty(weights.shape[0], dtype=torch.float32)

    for bw in sorted(set(bits)):
        indices = torch.tensor(
            [i for i, b in enumerate(bits) if b == bw],
            dtype=torch.long,
        )
        group = weights[indices]

        qmax = 2 ** (bw - 1) - 1
        qmin = -(2 ** (bw - 1))

        channel_scales = (
            group.abs().reshape(len(indices), -1)
            .amax(dim=1) / qmax
        ).clamp(min=1e-8)

        broadcast = channel_scales.reshape(
            len(indices), *([1] * (weights.ndim - 1))
        )

        qweights[indices] = torch.round(
            group / broadcast
        ).clamp(qmin, qmax).to(torch.int8)

        scales[indices] = channel_scales

    expected_scales = load(data["scale_w"]).reshape(-1)

    np.testing.assert_allclose(
        scales.numpy(),
        expected_scales,
        rtol=1e-5,
        atol=1e-7,
        err_msg=name,
    )

    bias = (
        load(data["bias_float"]).reshape(-1)
        if "bias_float" in data
        else np.zeros(weights.shape[0], dtype=np.float32)
    )

    prefix = safe(name)

    record = {
        "name": name,
        "operator": kind,
        "weight_bits": bits,
        "activation_bits": int(attrs["act_bit_width"]),
        "calibrated": bool(attrs["calibrated"]),
        "activation_scale": float(
            load(data["act_scale"]).reshape(-1)[0]
        ),
        "activation_zero_point": float(
            load(data["act_zero_point"]).reshape(-1)[0]
        ),
        "attributes": {
            key: attrs[key]
            for key in ("stride", "padding", "dilation", "groups")
            if key in attrs
        },
        "weights": save(
            prefix + "_weights.bin", qweights.numpy()
        ),
        "scales": save(
            prefix + "_scales.bin", expected_scales
        ),
        "bias": save(
            prefix + "_bias.bin", bias
        ),
    }

    quantized.append(record)
    print("EXPORTED:", name, flush=True)

norms = []
seen = set()

for entry in manifest["trace"]:
    if entry["type"] != "LayerNorm":
        continue

    name = entry["name"]
    if name in seen:
        continue
    seen.add(name)

    weight = state[name + ".weight"].cpu().numpy()
    bias = state[name + ".bias"].cpu().numpy()

    norms.append({
        "name": name,
        "weight": save(safe(name) + "_ln_weight.bin", weight),
        "bias": save(safe(name) + "_ln_bias.bin", bias),
        "epsilon": 1e-5,
    })

attention = []

for entry in manifest["trace"]:
    if entry["type"] != "WindowAttention":
        continue

    name = entry["name"]
    bias = state[
        name + ".relative_position_bias_table"
    ].cpu().numpy()

    attention.append({
        "name": name,
        "relative_position_bias": save(
            safe(name) + "_relative_bias.bin", bias
        ),
    })

assert len(quantized) == 53
assert len(norms) == 30
assert len(attention) == 12

input_image = load(manifest["input"])
logits = load(manifest["logits"])

assert input_image.shape == (1, 3, 224, 224)
assert logits.shape == (1, 100)
assert int(logits.argmax()) == 39

summary = {
    "model": "Mahdi Swin-T CIFAR100",
    "timm_version": "0.9.16",
    "stage_block_counts": [2, 2, 6, 2],
    "input": save("input.bin", input_image),
    "reference_logits": save("reference_logits.bin", logits),
    "reference_predicted_class": 39,
    "quantized_layer_count": len(quantized),
    "layernorm_count": len(norms),
    "attention_block_count": len(attention),
    "quantized_layers": quantized,
    "layernorm_layers": norms,
    "attention_layers": attention,
    "full_model_native_passed": False,
    "full_model_riscv_passed": False,
    "physical_fpga_tested": False,
}

destination = EVD / "runtime_manifest.json"
destination.write_text(json.dumps(summary, indent=2) + "\n")

total_bytes = sum(
    p.stat().st_size for p in OUT.glob("*.bin")
)

print("\n===== SWIN-T RUNTIME EXPORT =====")
print("Quantized layers:", len(quantized))
print("LayerNorm modules:", len(norms))
print("Attention blocks:", len(attention))
print("Parameter/input data bytes:", total_bytes)
print("Reference prediction:", 39)
print("Runtime data:", OUT)
print("Manifest:", destination)
print("PASS: Runtime parameters exported and verified")
print("NOTE: Full connected inference not yet executed")

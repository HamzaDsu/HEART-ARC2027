import os
os.environ.setdefault(
    "PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION", "python"
)

from pathlib import Path
from collections import Counter
import hashlib
import json
import sys

import numpy as np
import torch
import torch.nn as nn

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]

BASE = (
    Path.home() /
    "health-slm-model-validation/mahdi-four-models/levit384"
)
OUT = (
    Path.home() /
    "health-slm-model-validation/levit384-full-reference-export-20261029"
)
EVD = (
    REPO /
    "results/model_execution/levit384_cpu_reference_20261029"
)

sys.path.insert(0, str(BASE))
from models.levit import levit_cifar
from models.integer_inference import IntegerModelWrapper

torch.set_num_threads(4)
torch.manual_seed(20261029)

checkpoint_path = BASE / "LeViT384_CIFAR10_uniform8.pth"
weight_path = BASE / "LeViT384_ImageNet_delta5_weight.json"
activation_path = BASE / "LeViT384_ImageNet_delta5_activation.json"

for p in (checkpoint_path, weight_path, activation_path):
    assert p.is_file(), p

OUT.mkdir(parents=True, exist_ok=True)
EVD.mkdir(parents=True, exist_ok=True)
(OUT / "trace").mkdir(exist_ok=True)

def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def save(name, data):
    p = OUT / name
    a = np.asarray(
        data.detach().cpu().numpy(),
        dtype=np.float32
    )
    np.save(p, a)
    return {"file": name, "shape": list(a.shape)}

print("===== LOAD MAHDI LEVIT384 =====", flush=True)

checkpoint = torch.load(
    checkpoint_path,
    map_location="cpu",
    weights_only=False
)
source_state = checkpoint["model_state_dict"]

model = levit_cifar(num_classes=10)
target_state = model.state_dict()

matched = {
    k: v for k, v in source_state.items()
    if k in target_state
    and target_state[k].shape == v.shape
}

missing = sorted(set(target_state) - set(matched))

print("Checkpoint tensors:", len(source_state), flush=True)
print("Architecture tensors:", len(target_state), flush=True)
print("Matched tensors:", len(matched), flush=True)
print("Missing tensors:", len(missing), flush=True)
print("Missing examples:", missing[:10], flush=True)

assert len(matched) >= int(0.95 * len(target_state)), (
    "Too many checkpoint tensors failed to match"
)
assert "head.weight" in matched, (
    "Classifier weights were not loaded"
)

model.load_state_dict(matched, strict=False)
model.eval()

w_cfg = json.loads(weight_path.read_text())
a_cfg = json.loads(activation_path.read_text())

module_lookup = dict(model.named_modules())

compatible = [
    name for name in w_cfg
    if name in module_lookup
    and isinstance(module_lookup[name], (nn.Conv2d, nn.Linear))
]
unmatched = sorted(set(w_cfg) - set(compatible))

print("\n===== QUANTIZATION COMPATIBILITY =====")
print("Weight configuration entries:", len(w_cfg))
print("Activation configuration entries:", len(a_cfg))
print("Compatible quantized layers:", len(compatible))
print("Unmatched configuration entries:", unmatched[:20])

assert compatible, "No configured quantization layers matched"

# Mahdi's LeViT wrapper replaces timm's original classifier
# with a plain nn.Linear head and disables the distillation head.
# These two original timm names are therefore intentionally skipped.
expected_unmatched = {"head.linear", "head_dist.linear"}

assert set(unmatched) == expected_unmatched, (
    f"Unexpected unmatched quantization entries: {unmatched}"
)

assert isinstance(model.head, nn.Linear)
assert isinstance(model.head_dist, nn.Identity)
assert len(compatible) == 62

print("Expected skipped head entries:", unmatched)
print("PASS: Quantization configuration matches modified LeViT")


wrapper = IntegerModelWrapper(
    model,
    w_cfg,
    a_cfg,
    reorder_channels=True
)
wrapper.eval()

print("Actual replaced layers:", len(wrapper.int_layers))

assert len(wrapper.int_layers) == len(compatible)

# Match the original script: no calibration call.
assert not any(
    layer.calibrated
    for layer in wrapper.int_layers.values()
)

print("\n===== PREPARE DETERMINISTIC INPUT =====")

generator = torch.Generator(device="cpu")
generator.manual_seed(20261029)

x = torch.randn(
    (1, 3, 224, 224),
    generator=generator,
    dtype=torch.float32
)

input_record = save("input.npy", x)

calls = []
handles = []
quant_names = set(wrapper.int_layers)

def make_hook(name):
    def hook(module, inputs, output):
        first = inputs[0] if inputs else None

        if not isinstance(output, torch.Tensor):
            return

        entry = {
            "name": name,
            "type": type(module).__name__,
            "input_shape": (
                list(first.shape)
                if isinstance(first, torch.Tensor)
                else None
            ),
            "output_shape": list(output.shape)
        }

        if name in quant_names:
            index = sum(
                item["name"] == name for item in calls
            )
            safe_name = name.replace(".", "_")

            entry["input"] = save(
                f"trace/{safe_name}_{index}_input.npy",
                first
            )
            entry["output"] = save(
                f"trace/{safe_name}_{index}_output.npy",
                output
            )

        calls.append(entry)
    return hook

for name, module in wrapper.model.named_modules():
    if name:
        handles.append(
            module.register_forward_hook(make_hook(name))
        )

print("\n===== EXECUTE ORIGINAL INTEGER WRAPPER =====", flush=True)

try:
    with torch.no_grad():
        output = wrapper(x)
finally:
    for handle in handles:
        handle.remove()

assert tuple(output.shape) == (1, 10)
assert torch.isfinite(output).all()

logits_record = save("logits.npy", output)
predicted = int(output.argmax(dim=1).item())

quantization = []

for name, layer in wrapper.int_layers.items():
    record = {
        "name": name,
        "type": type(layer).__name__,
        "weight_shape": list(layer.weight_float.shape),
        "weight_bits": [int(b) for b in layer.weight_bits],
        "act_bits": [int(b) for b in layer.act_bits],
        "act_bit_width": int(layer.act_bit_width),
        "calibrated": bool(layer.calibrated),
        "act_scale": float(layer.act_scale.item()),
        "act_zero_point": float(layer.act_zero_point.item()),
        "perm_indices": [int(i) for i in layer.perm_indices]
    }

    if isinstance(layer, type(wrapper.int_layers[
        next(k for k, v in wrapper.int_layers.items()
             if hasattr(v, "stride"))
    ])) and hasattr(layer, "stride"):
        record.update({
            "stride": list(layer.stride),
            "padding": list(layer.padding),
            "dilation": list(layer.dilation),
            "groups": int(layer.groups)
        })

    quantization.append(record)

torch.save(
    wrapper.state_dict(),
    OUT / "wrapped_model_state.pt"
)

summary = {
    "model": "Mahdi LeViT384 CIFAR10",
    "execution": "original IntegerModelWrapper",
    "device": "cpu",
    "input": input_record,
    "logits": logits_record,
    "predicted_class": predicted,
    "checkpoint": {
        "filename": checkpoint_path.name,
        "sha256": sha256(checkpoint_path),
        "source_tensors": len(source_state),
        "matched_tensors": len(matched),
        "architecture_tensors": len(target_state),
        "missing_keys": missing
    },
    "quantization_config": {
        "weight_json": weight_path.name,
        "weight_sha256": sha256(weight_path),
        "activation_json": activation_path.name,
        "activation_sha256": sha256(activation_path),
        "reorder_channels": True,
        "calibration_applied": False
    },
    "unmatched_configuration_entries": unmatched,
    "classifier_quantized": False,
    "quantized_layer_count": len(quantization),
    "module_call_count": len(calls),
    "module_type_counts": dict(
        Counter(item["type"] for item in calls)
    ),
    "quantization": quantization,
    "trace": calls,
    "native_c_execution_passed": False,
    "riscv_qemu_execution_passed": False,
    "physical_fpga_tested": False
}

(OUT / "manifest.json").write_text(
    json.dumps(summary, indent=2) + "\n"
)

public_summary = {
    key: summary[key]
    for key in (
        "model", "execution", "device",
        "predicted_class", "checkpoint",
        "quantization_config", "quantized_layer_count",
        "module_call_count", "module_type_counts",
        "unmatched_configuration_entries",
        "classifier_quantized",
        "native_c_execution_passed",
        "riscv_qemu_execution_passed",
        "physical_fpga_tested"
    )
}

(EVD / "reference_summary.json").write_text(
    json.dumps(public_summary, indent=2) + "\n"
)

print("\n===== LEVIT384 CPU REFERENCE RESULTS =====")
print("Output shape:", list(output.shape))
print("Predicted class:", predicted)
print("Top logit:", float(output.max()))
print("Quantized layers:", len(quantization))
print("Recorded module calls:", len(calls))
print("Runtime reference:", OUT)
print("Git evidence:", EVD / "reference_summary.json")
print("PASS: LeViT384 CPU reference captured")
print("NOTE: C and RISC-V inference not yet executed")

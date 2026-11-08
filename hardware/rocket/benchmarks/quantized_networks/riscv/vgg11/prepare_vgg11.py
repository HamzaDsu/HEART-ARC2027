from pathlib import Path
import json
import hashlib
import numpy as np

root = Path.home() / "health-slm-model-validation"
reference = root / "vgg11-full-reference-export-20261029"
output = root / "vgg11-c-parameters-20261029"

assert not output.exists(), f"Already exists: {output}"

manifest = json.loads((reference / "manifest.json").read_text())
assert manifest["quantized_layers"] == 11
assert manifest["module_calls"] == 37

modules = [
    m for m in manifest["modules"]
    if m["type"] in ("IntegerConv2d", "IntegerLinear")
]
assert len(modules) == 11

def load_tensor(info):
    path = reference / info["file"]
    assert path.is_file(), path

    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest == info["sha256"]

    array = np.fromfile(path, dtype=np.dtype(info["dtype"]))
    return array.reshape(info["shape"])

prepared = []

# Validate everything before creating output files.
for layer in modules:
    tensors = layer["tensors"]
    attributes = layer["attributes"]

    weights = load_tensor(tensors["weight_float"])
    scale = load_tensor(tensors["scale_w"])

    bits = attributes["weight_bits"]
    assert len(bits) == weights.shape[0]
    assert scale.size == weights.shape[0]
    assert attributes["calibrated"] is True

    qweights = np.empty(weights.shape, dtype=np.int8)

    for channel, width in enumerate(bits):
        width = int(width)
        assert 2 <= width <= 8

        qmin = -(2 ** (width - 1))
        qmax = 2 ** (width - 1) - 1

        values = weights[channel] / scale[channel]
        qweights[channel] = np.clip(
            np.rint(values), qmin, qmax
        ).astype(np.int8)

    act_scale = load_tensor(tensors["act_scale"])
    zero_point = load_tensor(tensors["act_zero_point"])

    assert act_scale.size == zero_point.size == 1
    assert float(act_scale[0]) > 0

    prepared.append({
        "name": layer["name"],
        "type": layer["type"],
        "attributes": attributes,
        "qweights": qweights,
        "weight_scales": scale.astype(np.float32),
        "activation_scale": float(act_scale[0]),
        "activation_zero_point": float(zero_point[0]),
    })

output.mkdir(parents=True)

records = []

for index, item in enumerate(prepared):
    prefix = f"layer_{index:02d}"

    weight_file = prefix + "_weights.bin"
    scale_file = prefix + "_scales.bin"

    item["qweights"].tofile(output / weight_file)
    item["weight_scales"].tofile(output / scale_file)

    records.append({
        "name": item["name"],
        "operator": item["type"],
        "weight_shape": list(item["qweights"].shape),
        "weight_file": weight_file,
        "scale_file": scale_file,
        "activation_scale": item["activation_scale"],
        "activation_zero_point": item["activation_zero_point"],
        "attributes": item["attributes"],
    })

summary = {
    "model": "Mahdi VGG11 CIFAR10",
    "quantized_layers": len(records),
    "reference_module_calls": manifest["module_calls"],
    "predicted_class": manifest["predicted_class"],
    "true_label": manifest["reference_label"],
    "accuracy_assessed": manifest["reference_label"] >= 0,
    "layers": records,
    "full_riscv_inference": False,
}

(output / "quantized_manifest.json").write_text(
    json.dumps(summary, indent=2) + "\n"
)

print("===== VGG11 QUANTIZED PARAMETERS =====")
for item in records:
    print(
        f"{item['name']:<22} "
        f"{item['operator']:<17} "
        f"{item['weight_shape']}"
    )

print()
print("PASS: 11/11 quantized layers prepared")
print("PASS: Reference manifest validated")
print("PASS: Integer weight binaries generated")
print("Output:", output)
print("NOTE: Full RISC-V inference not yet executed")

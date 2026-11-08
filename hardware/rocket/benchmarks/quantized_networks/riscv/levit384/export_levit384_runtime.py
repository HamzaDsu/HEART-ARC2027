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
BASE = Path.home() / "health-slm-model-validation/mahdi-four-models/levit384"
REF = Path.home() / "health-slm-model-validation/levit384-full-reference-export-20261029"
OUT = Path.home() / "health-slm-model-validation/levit384-full-runtime-20261029"
EVD = REPO / "results/model_execution/levit384_full_c_riscv_qemu_20261029"

sys.path.insert(0, str(BASE))

from models.levit import levit_cifar
from models.integer_inference import IntegerModelWrapper

assert (REF / "wrapped_model_state.pt").is_file()
assert (REF / "manifest.json").is_file()
assert not OUT.exists(), f"Runtime directory exists: {OUT}"

reference = json.loads((REF / "manifest.json").read_text())

assert reference["quantized_layer_count"] == 62
assert reference["predicted_class"] == 3

print("===== RESTORE LEVIT384 CPU MODEL =====", flush=True)

model = levit_cifar(num_classes=10)

w_cfg = json.loads(
    (BASE / "LeViT384_ImageNet_delta5_weight.json").read_text()
)
a_cfg = json.loads(
    (BASE / "LeViT384_ImageNet_delta5_activation.json").read_text()
)

wrapper = IntegerModelWrapper(
    model, w_cfg, a_cfg, reorder_channels=True
)

assert len(wrapper.int_layers) == 62

state = torch.load(
    REF / "wrapped_model_state.pt",
    map_location="cpu",
    weights_only=True,
)

wrapper.load_state_dict(state, strict=True)

# Rebuild timm attention caches from restored checkpoint parameters.
wrapper.train(True)
wrapper.eval()

assert not any(
    layer.calibrated for layer in wrapper.int_layers.values()
)

OUT.mkdir(parents=True)
EVD.mkdir(parents=True, exist_ok=True)


def save(name, value):
    path = OUT / name

    if isinstance(value, torch.Tensor):
        value = value.detach().cpu().numpy()

    data = np.ascontiguousarray(value)

    if data.dtype.kind == "f":
        data = data.astype("<f4", copy=False)
    elif data.dtype == np.dtype("int8"):
        pass
    else:
        raise TypeError((name, data.dtype))

    data.tofile(path)

    return {
        "file": name,
        "shape": list(data.shape),
        "dtype": str(data.dtype),
        "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def safe(name):
    return name.replace(".", "__")


trace = reference["trace"]

quant_trace = {
    item["name"]: item
    for item in trace
    if item["type"] in ("IntegerLinear", "IntegerConv2d")
}

assert len(quant_trace) == 62

quantized = []

print("\n===== EXPORT 62 QUANTIZED LAYERS =====", flush=True)

for name, layer in wrapper.int_layers.items():
    weights = layer.weight_float.detach().cpu().float()
    bits = [int(v) for v in layer.weight_bits]

    assert len(bits) == weights.shape[0]
    assert all(2 <= b <= 8 for b in bits), name

    qweights = torch.empty_like(weights, dtype=torch.int8)
    scales = torch.empty(
        weights.shape[0], dtype=torch.float32
    )

    for bw in sorted(set(bits)):
        indices = torch.tensor(
            [i for i, b in enumerate(bits) if b == bw],
            dtype=torch.long,
        )

        group = weights[indices]

        qmax = 2 ** (bw - 1) - 1
        qmin = -(2 ** (bw - 1))

        channel_scale = (
            group.abs()
            .reshape(len(indices), -1)
            .amax(dim=1) / qmax
        ).clamp(min=1e-8)

        broadcast = channel_scale.reshape(
            len(indices),
            *([1] * (weights.ndim - 1))
        )

        qweights[indices] = torch.round(
            group / broadcast
        ).clamp(qmin, qmax).to(torch.int8)

        scales[indices] = channel_scale

    torch.testing.assert_close(
        scales,
        layer.scale_w.cpu().float(),
        rtol=1e-5,
        atol=1e-7,
        msg=name,
    )

    bias = (
        layer.bias_float
        if layer.bias_float is not None
        else torch.zeros(weights.shape[0])
    )

    prefix = safe(name)

    record = {
        "name": name,
        "type": type(layer).__name__,
        "input_shape": quant_trace[name]["input_shape"],
        "output_shape": quant_trace[name]["output_shape"],
        "activation_bits": int(layer.act_bit_width),
        "calibrated": bool(layer.calibrated),
        "activation_scale": float(layer.act_scale.item()),
        "activation_zero_point": float(
            layer.act_zero_point.item()
        ),
        "weight_bit_counts": dict(Counter(bits)),
        "weights": save(
            prefix + "_weights.bin", qweights
        ),
        "scales": save(
            prefix + "_scales.bin", layer.scale_w
        ),
        "bias": save(prefix + "_bias.bin", bias),
    }

    if hasattr(layer, "stride"):
        record["conv"] = {
            "stride": list(layer.stride),
            "padding": list(layer.padding),
            "dilation": list(layer.dilation),
            "groups": int(layer.groups),
        }

    quantized.append(record)
    print("EXPORTED:", name, flush=True)


print("\n===== EXPORT BATCHNORM AND ATTENTION =====", flush=True)

batchnorm = []
attention = []
downsample = []

for name, module in wrapper.model.named_modules():

    if isinstance(module, (nn.BatchNorm1d, nn.BatchNorm2d)):
        channels = module.num_features
        prefix = safe(name)

        gamma = (
            module.weight
            if module.weight is not None
            else torch.ones(channels)
        )
        beta = (
            module.bias
            if module.bias is not None
            else torch.zeros(channels)
        )

        batchnorm.append({
            "name": name,
            "type": type(module).__name__,
            "channels": channels,
            "epsilon": float(module.eps),
            "gamma": save(prefix + "_gamma.bin", gamma),
            "beta": save(prefix + "_beta.bin", beta),
            "mean": save(
                prefix + "_mean.bin", module.running_mean
            ),
            "variance": save(
                prefix + "_variance.bin", module.running_var
            ),
        })

    if type(module).__name__ in (
        "Attention", "AttentionDownsample"
    ):
        assert not module.use_conv, name

        bias = module.get_attention_biases(
            torch.device("cpu")
        )

        record = {
            "name": name,
            "type": type(module).__name__,
            "num_heads": int(module.num_heads),
            "key_dim": int(module.key_dim),
            "val_dim": int(module.val_dim),
            "scale": float(module.scale),
            "bias": save(
                safe(name) + "_attention_bias.bin",
                bias,
            ),
        }

        if hasattr(module, "val_attn_dim"):
            record["val_attn_dim"] = int(
                module.val_attn_dim
            )

        attention.append(record)

        print(
            "ATTENTION:",
            name,
            "bias_shape=",
            list(bias.shape),
            flush=True,
        )

    if type(module).__name__ == "Downsample":
        downsample.append({
            "name": name,
            "resolution": list(module.resolution),
            "stride": int(module.stride),
            "pool_type": (
                type(module.pool).__name__
                if module.pool is not None else None
            ),
        })


assert len(quantized) == 62, len(quantized)
assert len(batchnorm) == 62, len(batchnorm)
assert len(attention) == 14, len(attention)
assert len(downsample) == 2, len(downsample)

assert isinstance(wrapper.model.head, nn.Linear)
assert isinstance(wrapper.model.head_dist, nn.Identity)

image = np.load(
    REF / reference["input"]["file"],
    allow_pickle=False,
)

logits = np.load(
    REF / reference["logits"]["file"],
    allow_pickle=False,
)

assert image.shape == (1, 3, 224, 224)
assert logits.shape == (1, 10)
assert int(logits.argmax()) == 3

head = wrapper.model.head

manifest = {
    "model": "Mahdi LeViT384 CIFAR10",
    "input": save("input.bin", image),
    "reference_logits": save(
        "reference_logits.bin", logits
    ),
    "reference_predicted_class": 3,
    "quantized_layers": quantized,
    "batchnorm_layers": batchnorm,
    "attention_layers": attention,
    "downsample_layers": downsample,
    "classifier": {
        "quantized": False,
        "weight": save(
            "head_weight.bin", head.weight
        ),
        "bias": save(
            "head_bias.bin", head.bias
        ),
    },
    "graph": [
        {
            "name": item["name"],
            "type": item["type"],
            "input_shape": item["input_shape"],
            "output_shape": item["output_shape"],
        }
        for item in trace
    ],
    "full_native_c_passed": False,
    "full_riscv_qemu_passed": False,
    "physical_fpga_tested": False,
}

(OUT / "runtime_manifest.json").write_text(
    json.dumps(manifest, indent=2) + "\n"
)

summary = {
    "model": manifest["model"],
    "quantized_convolutions": 4,
    "quantized_linear_layers": 58,
    "batchnorm_layers": len(batchnorm),
    "attention_layers": len(attention),
    "attention_downsampling_layers": sum(
        item["type"] == "AttentionDownsample"
        for item in attention
    ),
    "downsample_modules": downsample,
    "classifier_quantized": False,
    "reference_predicted_class": 3,
    "runtime_data_bytes": sum(
        p.stat().st_size for p in OUT.glob("*.bin")
    ),
    "full_native_c_passed": False,
    "full_riscv_qemu_passed": False,
    "physical_fpga_tested": False,
}

destination = EVD / "runtime_export_summary.json"
destination.write_text(
    json.dumps(summary, indent=2) + "\n"
)

print("\n===== LEVIT384 RUNTIME EXPORT =====")
print("Quantized layers:", len(quantized))
print("BatchNorm layers:", len(batchnorm))
print("Attention modules:", len(attention))
print("Downsample selectors:", len(downsample))
print("Runtime data bytes:", summary["runtime_data_bytes"])
print("Local parameter directory:", OUT)
print("Git evidence:", destination)
print("PASS: LeViT384 runtime parameters exported")
print("NOTE: Full C/RISC-V inference not yet executed")

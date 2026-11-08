"""Profile executed integer convolution and linear layers.

Reports dense-equivalent MAC estimates, not measured hardware operations.
Does not count separate attention matrix multiplications.
"""

import argparse
import json
import os
import runpy
import sys
from pathlib import Path

import torch
from torch.nn.modules.module import (
    register_module_forward_hook,
    register_module_forward_pre_hook,
)

ROOT = Path(__file__).resolve().parents[2]
WORKSPACE = Path.home() / "quantized-network-benchmarks"

parser = argparse.ArgumentParser()
parser.add_argument(
    "--model",
    required=True,
    choices=["resnet18", "vgg11", "swint", "levit384"],
)
args = parser.parse_args()

name = args.model
folder = WORKSPACE / name
script = folder / "npu_inference_integer.py"

profile_path = (
    ROOT
    / "benchmarks/quantized_networks/profiles"
    / f"{name}.json"
)

profile = json.loads(profile_path.read_text())

known_layers = {
    layer["name"]: layer
    for layer in profile["layers"]
}

module_names = {}
records = []


def first_tensor(value):
    if torch.is_tensor(value):
        return value

    if isinstance(value, (tuple, list)):
        for item in value:
            result = first_tensor(item)
            if result is not None:
                return result

    if isinstance(value, dict):
        for item in value.values():
            result = first_tensor(item)
            if result is not None:
                return result

    return None


def before_forward(module, inputs):
    layers = getattr(module, "int_layers", None)

    if isinstance(layers, dict):
        for layer_name, layer_module in layers.items():
            module_names[id(layer_module)] = layer_name


def after_forward(module, inputs, output):
    layer_name = module_names.get(id(module))

    if layer_name is None:
        return

    x = first_tensor(inputs)
    y = first_tensor(output)

    if x is None or y is None:
        return

    info = known_layers.get(layer_name)
    weight_shape = info["shape"] if info else None

    macs = None
    operation = "unresolved"

    if weight_shape and len(weight_shape) == 4:
        operation = "convolution"

        if y.ndim == 4:
            macs = (
                y.numel()
                * weight_shape[1]
                * weight_shape[2]
                * weight_shape[3]
            )

    elif weight_shape and len(weight_shape) == 2:
        operation = "linear"
        macs = y.numel() * weight_shape[1]

    records.append({
        "layer": layer_name,
        "operation": operation,
        "input_shape": list(x.shape),
        "output_shape": list(y.shape),
        "weight_shape": weight_shape,
        "dense_equivalent_macs": macs,
        "output_bytes": y.numel() * y.element_size(),
    })


pre_handle = register_module_forward_pre_hook(before_forward)
post_handle = register_module_forward_hook(after_forward)

original_cwd = Path.cwd()
original_argv = sys.argv[:]

try:
    os.chdir(folder)
    sys.path.insert(0, str(folder))
    sys.argv = [str(script), "--mode", "mqf"]

    torch.manual_seed(0)

    namespace = runpy.run_path(
        str(script),
        run_name="__integer_profiler__",
    )

    namespace["main"]()

finally:
    pre_handle.remove()
    post_handle.remove()
    os.chdir(original_cwd)
    sys.argv = original_argv

if not records:
    raise RuntimeError(
        "No integer layers captured; inspect wrapper hook mapping."
    )

total_macs = sum(
    row["dense_equivalent_macs"] or 0
    for row in records
)

total_output_bytes = sum(
    row["output_bytes"] for row in records
)

max_output_bytes = max(
    row["output_bytes"] for row in records
)

unresolved = [
    row["layer"]
    for row in records
    if row["dense_equivalent_macs"] is None
]

observed = {row["layer"] for row in records}
not_executed = sorted(set(known_layers) - observed)

result = {
    "model": name,
    "architecture": profile["architecture"],
    "observed_integer_layer_calls": len(records),
    "observed_unique_integer_layers": len(observed),
    "dense_equivalent_macs": total_macs,
    "dense_equivalent_gmacs": round(total_macs / 1e9, 4),
    "summed_integer_layer_output_mib": round(
        total_output_bytes / 1048576, 3
    ),
    "largest_integer_layer_output_mib": round(
        max_output_bytes / 1048576, 3
    ),
    "unresolved_layers": unresolved,
    "configured_layers_not_observed": not_executed,
    "layers": records,
    "limitations": [
        "MACs estimated from executed layer shapes.",
        "Attention matrix multiplications may be excluded.",
        "Normalization, elementwise operations and pooling excluded.",
        "Summed outputs are not peak live activation memory.",
        "This is Python execution, not RISC-V or FPGA execution.",
    ],
}

output_dir = (
    ROOT / "results/optimizer/integer_execution_profiles"
)
output_dir.mkdir(parents=True, exist_ok=True)

destination = output_dir / f"{name}.json"
destination.write_text(
    json.dumps(result, indent=2) + "\n"
)

print(f"\n===== {name.upper()} WORKLOAD PROFILE =====")
print("Observed integer calls:", len(records))
print("Unique integer layers:", len(observed))
print("Dense-equivalent GMACs:", result["dense_equivalent_gmacs"])
print(
    "Summed output MiB:",
    result["summed_integer_layer_output_mib"]
)
print(
    "Largest output MiB:",
    result["largest_integer_layer_output_mib"]
)
print("Unresolved layers:", len(unresolved))
print("Configured layers not observed:", len(not_executed))
print("Saved:", destination)

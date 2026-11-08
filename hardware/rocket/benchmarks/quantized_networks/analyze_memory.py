#!/usr/bin/env python3
"""Estimate ideal packed weight storage from quantization configs."""

import argparse
import csv
import json
from pathlib import Path

import torch

parser = argparse.ArgumentParser()
parser.add_argument(
    "--workspace",
    type=Path,
    default=Path.home() / "quantized-network-benchmarks",
)
args = parser.parse_args()

workspace = args.workspace.expanduser().resolve()
models = ("resnet18", "vgg11", "swint", "levit384")
rows = []

for model in models:
    folder = workspace / model

    config_path = next(folder.glob("*_delta5_weight.json"))
    checkpoint_path = next(folder.glob("*_uniform8.pth"))

    config = json.loads(config_path.read_text())

    data = torch.load(
        checkpoint_path,
        map_location="cpu",
        weights_only=True,
    )

    state = data.get(
        "model_state_dict",
        data.get("state_dict", data),
    )

    mixed_bits = 0
    uniform_bits = 0
    covered_params = 0
    unmatched = []

    for layer, precisions in config.items():
        tensor = state.get(f"{layer}.weight")

        if (
            tensor is None
            or tensor.ndim < 1
            or len(precisions) != tensor.shape[0]
        ):
            unmatched.append(layer)
            continue

        per_channel = tensor.numel() // tensor.shape[0]

        mixed_bits += sum(precisions) * per_channel
        uniform_bits += tensor.numel() * 8
        covered_params += tensor.numel()

    total_weights = sum(
        value.numel()
        for key, value in state.items()
        if key.endswith(".weight")
        and torch.is_tensor(value)
    )

    reduction = (
        100 * (1 - mixed_bits / uniform_bits)
        if uniform_bits else 0.0
    )

    row = {
        "network": model,
        "matched_layers": len(config) - len(unmatched),
        "configured_layers": len(config),
        "covered_parameters": covered_params,
        "parameter_coverage_pct": round(
            100 * covered_params / total_weights, 2
        ) if total_weights else 0,
        "uniform8_mib": round(
            uniform_bits / 8 / 1048576, 3
        ),
        "configured_mib": round(
            mixed_bits / 8 / 1048576, 3
        ),
        "reduction_pct": round(reduction, 2),
        "unmatched_layers": ";".join(unmatched),
    }

    rows.append(row)

    print(f"\n===== {model.upper()} =====")
    print(f"Matched layers: {row['matched_layers']}/{len(config)}")
    print(f"8-bit storage: {row['uniform8_mib']} MiB")
    print(f"Configured storage: {row['configured_mib']} MiB")
    print(f"Reduction: {row['reduction_pct']}%")
    print(f"Unmatched: {unmatched}")

results = workspace / "results"
results.mkdir(parents=True, exist_ok=True)

output = results / "weight_memory_analysis.csv"

with output.open("w", newline="") as file:
    writer = csv.DictWriter(file, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)

print(f"\nMemory analysis saved: {output}")
print("All four network analyses completed.")

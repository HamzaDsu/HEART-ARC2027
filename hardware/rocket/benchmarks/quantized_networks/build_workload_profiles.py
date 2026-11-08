import json
from pathlib import Path

import torch

ROOT = Path.home() / "quantized-network-benchmarks"
OUTPUT = Path("benchmarks/quantized_networks/profiles")
OUTPUT.mkdir(parents=True, exist_ok=True)

NETWORKS = {
    "resnet18": "cnn",
    "vgg11": "cnn",
    "swint": "vision_transformer",
    "levit384": "hybrid_vision_transformer",
}

for name, architecture in NETWORKS.items():
    folder = ROOT / name

    config_path = next(folder.glob("*_delta5_weight.json"))
    checkpoint_path = next(folder.glob("*_uniform8.pth"))

    config = json.loads(config_path.read_text())
    checkpoint = torch.load(
        checkpoint_path,
        map_location="cpu",
        weights_only=True,
    )

    state = checkpoint.get(
        "model_state_dict",
        checkpoint.get("state_dict", checkpoint),
    )

    total_weights = sum(
        tensor.numel()
        for key, tensor in state.items()
        if key.endswith(".weight")
        and torch.is_tensor(tensor)
    )

    matched_params = 0
    packed_bits = 0
    unmatched = []
    layer_profiles = []

    for layer, bitwidths in config.items():
        tensor = state.get(f"{layer}.weight")

        if (
            tensor is None
            or not torch.is_tensor(tensor)
            or tensor.ndim == 0
            or len(bitwidths) != tensor.shape[0]
        ):
            unmatched.append(layer)
            continue

        params_per_channel = tensor.numel() // tensor.shape[0]
        tensor_bits = sum(bitwidths) * params_per_channel

        matched_params += tensor.numel()
        packed_bits += tensor_bits

        layer_profiles.append({
            "name": layer,
            "shape": list(tensor.shape),
            "parameters": tensor.numel(),
            "average_bits": round(
                sum(bitwidths) / len(bitwidths), 3
            ),
        })

    profile = {
        "model_name": name,
        "architecture": architecture,
        "format": "pytorch",
        "total_weight_parameters": total_weights,
        "matched_weight_parameters": matched_params,
        "weight_coverage_pct": round(
            100 * matched_params / total_weights, 2
        ) if total_weights else 0,
        "average_bits_matched": round(
            packed_bits / matched_params, 3
        ) if matched_params else None,
        "packed_weights_matched_mib": round(
            packed_bits / 8 / 1048576, 3
        ),
        "unmatched_config_layers": unmatched,
        "layers": layer_profiles,
        "analysis_status": "checkpoint_metadata_estimate",
        "limitations": [
            "Packed storage covers matched weight tensors only.",
            "Activation memory and runtime buffers excluded.",
            "Inference operations and performance not measured.",
        ],
    }

    destination = OUTPUT / f"{name}.json"
    destination.write_text(
        json.dumps(profile, indent=2) + "\n"
    )

    print(
        f"{name}: "
        f"{len(layer_profiles)} matched layers, "
        f"{profile['weight_coverage_pct']}% coverage, "
        f"{profile['packed_weights_matched_mib']} MiB"
    )

print(f"\nProfiles saved to: {OUTPUT}")

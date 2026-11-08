import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROFILES = ROOT / "benchmarks/quantized_networks/profiles"
MEASUREMENTS = ROOT / "results/optimizer/measurements"

REFERENCES = {
    "baseline": "nexysvideo_baseline.json",
    "optimized": "nexysvideo_optimized.json",
}

def read_json(path):
    return json.loads(path.read_text())

def tile_kib(layer):
    shape = layer["shape"]
    channels = min(64, shape[0])
    elements_per_channel = math.prod(shape[1:])
    bits = layer["average_bits"]
    return channels * elements_per_channel * bits / 8192

def evaluate(profile, reference):
    cache_kib = reference["configuration"]["dcache_kib"]
    layers = profile["layers"]

    matched = sum(layer["parameters"] for layer in layers)
    fitting = sum(
        layer["parameters"]
        for layer in layers
        if tile_kib(layer) <= cache_kib
    )

    return {
        "cache_kib": cache_kib,
        "scratchpad_kib": reference["configuration"]["scratchpad_kib"],
        "weight_tile_fit_proxy_pct": round(
            100 * fitting / matched, 2
        ) if matched else 0,
        "measured_fpga_resources": reference["measured_resources"],
        "validation": "metadata_screening_not_execution",
    }

references = {
    name: read_json(MEASUREMENTS / filename)
    for name, filename in REFERENCES.items()
}

assert all(
    ref["fpga_part"] == "xc7a200tsbg484-1"
    for ref in references.values()
)

results = {}

for name in ("resnet18", "vgg11", "swint", "levit384"):
    profile = read_json(PROFILES / f"{name}.json")

    results[name] = {
        "architecture": profile["architecture"],
        "packed_weights_mib": profile["packed_weights_matched_mib"],
        "coverage_pct": profile["weight_coverage_pct"],
        "candidates": {
            key: evaluate(profile, reference)
            for key, reference in references.items()
        },
    }

    print(f"\n===== {name.upper()} =====")
    print("Architecture:", profile["architecture"])
    print("Packed matched weights:", profile["packed_weights_matched_mib"], "MiB")

    for key, candidate in results[name]["candidates"].items():
        print(
            key,
            "D-cache:", candidate["cache_kib"], "KiB",
            "weight-tile fit proxy:",
            candidate["weight_tile_fit_proxy_pct"], "%"
        )

output = ROOT / "results/optimizer/four_model_nexys_screening.json"
output.write_text(json.dumps({
    "status": "preliminary_model_aware_screening",
    "board": "nexysvideo",
    "limitations": [
        "Weight-tile fit is a metadata proxy, not a cache hit rate.",
        "No activation shapes, measured MAC counts or runtime performance.",
        "Neither FPGA configuration has executed these networks.",
        "Hardware resource measurements are configuration-specific, not model-specific."
    ],
    "models": results,
}, indent=2) + "\n")

print("\nResults saved:", output)

"""Check sensitivity of compute-weighted cache eligibility to tile size."""

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results/optimizer"
PROFILES = ROOT / "benchmarks/quantized_networks/profiles"

MODELS = ("resnet18", "vgg11", "swint", "levit384")
TILE_SIZES = (16, 32, 64, 128)

CACHE_CONFIGS = {
    "baseline": 32,
    "optimized": 16,
}


def load(path):
    return json.loads(path.read_text())


def score(metadata, execution, cache_kib, tile_channels):
    layers = {x["name"]: x for x in metadata["layers"]}
    total_macs = 0
    eligible_macs = 0

    for call in execution["layers"]:
        layer = layers[call["layer"]]
        shape = layer["shape"]
        macs = call["dense_equivalent_macs"]

        assert macs is not None
        assert len(shape) >= 2

        channels = min(tile_channels, shape[0])
        tile_kib = (
            channels
            * math.prod(shape[1:])
            * layer["average_bits"]
            / 8192
        )

        total_macs += macs

        if tile_kib <= cache_kib:
            eligible_macs += macs

    assert total_macs > 0
    return eligible_macs / total_macs


existing = load(
    RESULTS / "four_model_workload_aware_pareto.json"
)

report = {}

for name in MODELS:
    metadata = load(PROFILES / f"{name}.json")
    execution = load(
        RESULTS / "integer_execution_profiles" / f"{name}.json"
    )

    report[name] = {}

    print(f"\n===== {name.upper()} =====")
    print("Tile channels | Baseline % | Optimized %")

    for tile_size in TILE_SIZES:
        values = {
            label: score(
                metadata,
                execution,
                cache_kib,
                tile_size,
            )
            for label, cache_kib in CACHE_CONFIGS.items()
        }

        # The larger cache should never have lower eligibility.
        assert values["baseline"] >= values["optimized"]

        for value in values.values():
            assert 0 <= value <= 1

        if tile_size == 64:
            previous = {
                item["name"]: item["score"]
                for item in existing["models"][name]["candidates"]
            }

            for label in CACHE_CONFIGS:
                assert abs(
                    values[label] - previous[label]
                ) < 0.000002, (
                    f"64-channel score mismatch: {name}/{label}"
                )

        report[name][str(tile_size)] = values

        print(
            f"{tile_size:13d} | "
            f"{values['baseline'] * 100:10.2f} | "
            f"{values['optimized'] * 100:11.2f}"
        )

destination = RESULTS / "four_model_tile_sensitivity.json"

destination.write_text(
    json.dumps({
        "status": "exploratory_tile_sensitivity",
        "models": report,
        "assumed_tile_channels": list(TILE_SIZES),
        "limitations": [
            "Not measured cache hit rates or inference speed.",
            "Only convolution and linear MACs included in scoring.",
            "Assumes fixed output-channel tile granularity.",
            "Activation traffic and attention BMMs excluded.",
            "No FPGA execution measurements.",
        ],
    }, indent=2) + "\n"
)

print("\nPASS: 64-channel results reproduce previous evaluation")
print("Saved:", destination)

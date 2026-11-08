"""Pareto screening for quantized vision-network profiles.

Uses measured FPGA resources and metadata-based cache-fit estimates.
Does not predict or measure inference throughput.
"""

import json
import math
from pathlib import Path

from optimizer.optimization.pareto import pareto_front

ROOT = Path(__file__).resolve().parents[2]
PROFILES = ROOT / "benchmarks/quantized_networks/profiles"
MEASUREMENTS = ROOT / "results/optimizer/measurements"

CONFIGS = {
    "baseline": "nexysvideo_baseline.json",
    "optimized": "nexysvideo_optimized.json",
}


def load(path):
    return json.loads(path.read_text())


def cache_fit_proxy(profile, cache_kib):
    total = 0
    fitting = 0

    for layer in profile["layers"]:
        shape = layer["shape"]
        parameters = layer["parameters"]

        if not shape or shape[0] <= 0:
            continue

        channels = min(64, shape[0])
        elements = math.prod(shape[1:])
        bits = layer["average_bits"]

        tile_kib = channels * elements * bits / 8192

        total += parameters

        if tile_kib <= cache_kib:
            fitting += parameters

    return fitting / total if total else 0.0


def main():
    references = {
        name: load(MEASUREMENTS / filename)
        for name, filename in CONFIGS.items()
    }

    for reference in references.values():
        assert reference["fpga_part"] == "xc7a200tsbg484-1"

    results = {}

    for name in ("resnet18", "vgg11", "swint", "levit384"):
        profile = load(PROFILES / f"{name}.json")

        candidates = []

        for label, reference in references.items():
            config = reference["configuration"]

            proxy = cache_fit_proxy(
                profile, config["dcache_kib"]
            )

            candidates.append({
                "name": label,
                "configuration": config,
                "resource_estimates": reference[
                    "measured_resources"
                ],
                "score": round(proxy, 6),
                "score_type": "weight_tile_fit_fraction",
                "validation": "metadata_screening_only",
            })

        front = pareto_front(candidates)

        results[name] = {
            "architecture": profile["architecture"],
            "evaluated_candidates": candidates,
            "pareto_front": front,
            "limitations": [
                "Score is not throughput or a cache-hit rate.",
                "Scratchpad performance is not modeled.",
                "Activation traffic and runtime are not modeled.",
                "FPGA resources are measured, not model-specific.",
            ],
        }

        print(f"\n===== {name.upper()} =====")

        for candidate in candidates:
            print(
                f"{candidate['name']}: "
                f"tile-fit proxy={candidate['score'] * 100:.2f}%, "
                f"BRAM={candidate['resource_estimates']['bram36_equivalent']}, "
                f"LUTs={candidate['resource_estimates']['luts']}"
            )

        print(
            "Pareto configurations:",
            [candidate["name"] for candidate in front]
        )

    output = ROOT / (
        "results/optimizer/four_model_pareto_screening.json"
    )

    output.parent.mkdir(parents=True, exist_ok=True)

    output.write_text(
        json.dumps({
            "status": "metadata_based_pareto_screening",
            "board": "nexysvideo",
            "models": results,
        }, indent=2) + "\n"
    )

    print("\nResults saved:", output)


if __name__ == "__main__":
    main()

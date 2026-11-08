"""Workload-aware FPGA Pareto screening for quantized vision models.

Measured hardware resources; metadata-derived cache suitability.
Not a latency, throughput, or power predictor.
"""

import json
import math
from pathlib import Path

from optimizer.optimization.pareto import pareto_front

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results/optimizer"
PROFILES = ROOT / "benchmarks/quantized_networks/profiles"

MODELS = ("resnet18", "vgg11", "swint", "levit384")

CONFIGS = {
    "baseline": "nexysvideo_baseline.json",
    "optimized": "nexysvideo_optimized.json",
}


def load(path):
    return json.loads(path.read_text())


def evaluate(profile, execution, reference, name):
    metadata = {
        layer["name"]: layer
        for layer in profile["layers"]
    }

    cache_kib = reference["configuration"]["dcache_kib"]

    total_macs = 0
    eligible_macs = 0
    eligible_layers = 0

    for layer in execution["layers"]:
        info = metadata.get(layer["layer"])

        if info is None:
            raise ValueError(
                f"Missing quantization metadata: {layer['layer']}"
            )

        macs = layer["dense_equivalent_macs"]

        if macs is None:
            raise ValueError(
                f"Unresolved MAC count: {layer['layer']}"
            )

        shape = info["shape"]
        channels = min(64, shape[0])

        tile_kib = (
            channels
            * math.prod(shape[1:])
            * info["average_bits"]
            / 8192
        )

        total_macs += macs

        if tile_kib <= cache_kib:
            eligible_macs += macs
            eligible_layers += 1

    fit = eligible_macs / total_macs if total_macs else 0.0

    return {
        "name": name,
        "configuration": reference["configuration"],
        "resource_estimates": reference["measured_resources"],
        "score": round(fit, 6),
        "score_type": "compute_weighted_weight_tile_eligibility",
        "eligible_integer_layers": eligible_layers,
        "evaluated_integer_layers": len(execution["layers"]),
        "validation": "metadata_proxy_with_measured_fpga_resources",
    }


def main():
    references = {
        name: load(RESULTS / "measurements" / filename)
        for name, filename in CONFIGS.items()
    }

    for reference in references.values():
        assert reference["fpga_part"] == "xc7a200tsbg484-1"

    results = {}

    for name in MODELS:
        profile = load(PROFILES / f"{name}.json")

        execution = load(
            RESULTS / "integer_execution_profiles" / f"{name}.json"
        )

        combined = load(
            RESULTS / "combined_workload_profiles" / f"{name}.json"
        )

        candidates = [
            evaluate(profile, execution, reference, label)
            for label, reference in references.items()
        ]

        front = pareto_front(candidates)

        results[name] = {
            "architecture": profile["architecture"],
            "workload": combined,
            "candidates": candidates,
            "pareto_front": [item["name"] for item in front],
        }

        print(f"\n===== {name.upper()} =====")
        print(
            "Accounted GMACs:",
            combined["accounted_gmacs"]
        )

        for candidate in candidates:
            print(
                f"{candidate['name']}: "
                f"D-cache={candidate['configuration']['dcache_kib']} KiB, "
                f"compute-weighted tile eligibility="
                f"{candidate['score'] * 100:.2f}%, "
                f"BRAM={candidate['resource_estimates']['bram36_equivalent']}"
            )

        print(
            "Pareto configurations:",
            results[name]["pareto_front"]
        )

    destination = (
        RESULTS / "four_model_workload_aware_pareto.json"
    )

    destination.write_text(
        json.dumps({
            "status": "exploratory_workload_aware_pareto",
            "board": "nexysvideo",
            "models": results,
            "limitations": [
                "Cache eligibility is not a cache hit rate.",
                "Only integer conv/linear MACs weight the cache score.",
                "Attention BMMs are recorded but not used in cache scoring.",
                "64-output-channel tiles are an assumed granularity.",
                "Scratchpad use and memory traffic are unmeasured.",
                "FPGA resource measurements are configuration-specific.",
                "No physical FPGA inference or latency measurements.",
            ],
        }, indent=2) + "\n"
    )

    print("\nSaved:", destination)


if __name__ == "__main__":
    main()

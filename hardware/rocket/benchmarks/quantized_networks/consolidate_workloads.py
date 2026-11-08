"""Consolidate measured execution shapes and estimated operations."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results/optimizer"
PROFILES = ROOT / "benchmarks/quantized_networks/profiles"
OUTPUT = RESULTS / "combined_workload_profiles"

OUTPUT.mkdir(parents=True, exist_ok=True)

MODELS = ("resnet18", "vgg11", "swint", "levit384")


def load(path):
    return json.loads(path.read_text())


for name in MODELS:
    metadata = load(PROFILES / f"{name}.json")
    execution = load(
        RESULTS / "integer_execution_profiles" / f"{name}.json"
    )

    conv_macs = sum(
        row["dense_equivalent_macs"] or 0
        for row in execution["layers"]
        if row["operation"] == "convolution"
    )

    linear_macs = sum(
        row["dense_equivalent_macs"] or 0
        for row in execution["layers"]
        if row["operation"] == "linear"
    )

    bmm_macs = 0
    mm_macs = None
    overlap_check = "not_applicable"

    if name in ("swint", "levit384"):
        attention = load(
            RESULTS / "attention_execution_profiles"
            / f"{name}.json"
        )

        operators = attention["operators"]

        bmm_macs = sum(
            row["flops"] for row in operators
            if row["operator"] == "aten::bmm"
        ) / 2

        mm_macs = sum(
            row["flops"] for row in operators
            if row["operator"] == "aten::mm"
        ) / 2

        difference = abs(mm_macs - linear_macs)

        overlap_check = (
            "consistent_with_integer_linear_operations"
            if difference <= max(1e7, linear_macs * 0.05)
            else "requires_further_operator_attribution"
        )

    accounted = conv_macs + linear_macs + bmm_macs

    result = {
        "model_name": name,
        "architecture": metadata["architecture"],
        "packed_matched_weights_mib": metadata[
            "packed_weights_matched_mib"
        ],
        "weight_coverage_pct": metadata[
            "weight_coverage_pct"
        ],
        "conv_gmacs": round(conv_macs / 1e9, 6),
        "linear_gmacs": round(linear_macs / 1e9, 6),
        "bmm_gmacs": round(bmm_macs / 1e9, 6),
        "accounted_gmacs": round(accounted / 1e9, 6),
        "profiler_mm_gmacs": (
            round(mm_macs / 1e9, 6)
            if mm_macs is not None else None
        ),
        "mm_overlap_check": overlap_check,
        "summed_integer_outputs_mib": execution[
            "summed_integer_layer_output_mib"
        ],
        "largest_integer_output_mib": execution[
            "largest_integer_layer_output_mib"
        ],
        "validation": "cpu_execution_metadata_estimation",
        "limitations": [
            "Not a measured FPGA workload.",
            "MACs estimated from observed execution shapes.",
            "BMM counts assume two FLOPs per MAC.",
            "MM operations not added to avoid possible overlap.",
            "Other operations may remain uncounted.",
            "Summed outputs are not peak activation memory.",
            "No measured FPGA inference latency.",
        ],
    }

    destination = OUTPUT / f"{name}.json"
    destination.write_text(
        json.dumps(result, indent=2) + "\n"
    )

    print(f"\n===== {name.upper()} =====")
    print("Architecture:", result["architecture"])
    print("Convolution GMACs:", result["conv_gmacs"])
    print("Linear GMACs:", result["linear_gmacs"])
    print("BMM GMACs:", result["bmm_gmacs"])
    print("Accounted GMACs:", result["accounted_gmacs"])
    print("MM overlap check:", overlap_check)
    print("Saved:", destination)

print("\n===== COMPLETE: 4/4 WORKLOAD PROFILES =====")

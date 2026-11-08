"""Analyze activation tensor sizes and illustrative data traffic.

Traffic assumes one input read and one output write per
observed integer-layer call, with no reuse or cache modeling.
This is NOT measured DRAM traffic or peak live memory.
"""

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "results/optimizer"

MODELS = ("resnet18", "vgg11", "swint", "levit384")

FORMATS = {
    "int8": 1,
    "fp16": 2,
    "fp32": 4,
}

MIB = 1024 * 1024


def load(path):
    return json.loads(path.read_text())


report = {}

for name in MODELS:
    execution = load(
        RESULTS / "integer_execution_profiles" / f"{name}.json"
    )

    layers = execution["layers"]

    if not layers:
        raise ValueError(f"No execution layers for {name}")

    model_result = {
        "integer_layer_calls": len(layers),
        "scenarios": {},
    }

    print(f"\n===== {name.upper()} =====")

    for fmt, bytes_per_element in FORMATS.items():
        total_input_bytes = 0
        total_output_bytes = 0
        largest_output_bytes = 0
        largest_input_output_pair_bytes = 0

        for layer in layers:
            input_elements = math.prod(layer["input_shape"])
            output_elements = math.prod(layer["output_shape"])

            input_bytes = input_elements * bytes_per_element
            output_bytes = output_elements * bytes_per_element

            total_input_bytes += input_bytes
            total_output_bytes += output_bytes

            largest_output_bytes = max(
                largest_output_bytes,
                output_bytes,
            )

            largest_input_output_pair_bytes = max(
                largest_input_output_pair_bytes,
                input_bytes + output_bytes,
            )

        scenario = {
            "total_input_mib": round(
                total_input_bytes / MIB, 4
            ),
            "total_output_mib": round(
                total_output_bytes / MIB, 4
            ),
            "illustrative_layer_io_mib": round(
                (total_input_bytes + total_output_bytes) / MIB,
                4,
            ),
            "largest_output_mib": round(
                largest_output_bytes / MIB, 4
            ),
            "largest_input_output_pair_mib": round(
                largest_input_output_pair_bytes / MIB, 4
            ),
        }

        model_result["scenarios"][fmt] = scenario

        print(
            f"{fmt.upper():5} "
            f"layer I/O={scenario['illustrative_layer_io_mib']:.4f} MiB, "
            f"largest output={scenario['largest_output_mib']:.4f} MiB"
        )

    report[name] = model_result

destination = RESULTS / "four_model_activation_traffic.json"

destination.write_text(
    json.dumps({
        "status": "illustrative_activation_traffic_scenarios",
        "models": report,
        "assumptions": [
            "One input read and output write per integer-layer call.",
            "All tensor elements use the selected scenario byte width.",
            "No cache reuse, spatial tiling, or hardware transfers modeled.",
        ],
        "limitations": [
            "These are hypothetical storage-width scenarios.",
            "They do not establish actual runtime activation precision.",
            "Does not count attention BMM tensor traffic.",
            "Does not include weights or temporary buffers.",
            "No peak live memory or measured DRAM bandwidth.",
            "No physical FPGA execution.",
        ],
    }, indent=2) + "\n"
)

print("\n===== COMPLETE: 4/4 NETWORKS =====")
print("Saved:", destination)

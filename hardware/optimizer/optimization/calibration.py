"""FPGA resource calibration using measured implementations."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CALIBRATION_DIR = ROOT / "results/optimizer/calibration"

RESOURCE_KEYS = (
    "luts",
    "flip_flops",
    "bram36_equivalent",
    "dsp",
)


def estimate_calibrated_resources(
    candidate,
    fpga_part,
    calibration_dir=CALIBRATION_DIR,
):
    """Return measured or interpolated resources, or None."""

    # Candidate validation is metadata, not a hardware parameter.
    # Preserve strict matching for all actual configuration fields.
    candidate = {
        key: value
        for key, value in candidate.items()
        if key != "validation"
    }

    for path in sorted(Path(calibration_dir).glob("*.json")):
        data = json.loads(path.read_text())

        if data.get("fpga_part") != fpga_part:
            continue

        dimension = data.get("calibration_dimension")

        if dimension not in candidate:
            continue

        matching = []

        for observation in data.get("observations", []):
            config = observation["configuration"]

            # All parameters except the calibrated dimension
            # must match exactly.
            if set(config) != set(candidate):
                continue

            if any(
                candidate[key] != value
                for key, value in config.items()
                if key != dimension
            ):
                continue

            matching.append(observation)

        matching.sort(
            key=lambda item: item["configuration"][dimension]
        )

        value = candidate[dimension]

        # Exact measured configuration.
        for observation in matching:
            if observation["configuration"][dimension] == value:
                return {
                    "resource_estimates": dict(
                        observation["measured_resources"]
                    ),
                    "validation": "measured_implementation",
                    "source": str(path),
                }

        # Interpolate only between measured configurations.
        for lower, upper in zip(matching, matching[1:]):
            x0 = lower["configuration"][dimension]
            x1 = upper["configuration"][dimension]

            if not (x0 < value < x1):
                continue

            ratio = (value - x0) / (x1 - x0)

            estimated = {}

            for key in RESOURCE_KEYS:
                y0 = lower["measured_resources"][key]
                y1 = upper["measured_resources"][key]

                estimated[key] = round(
                    y0 + ratio * (y1 - y0)
                )

            return {
                "resource_estimates": estimated,
                "validation": "measured_interpolation",
                "source": str(path),
                "calibration_dimension": dimension,
                "measured_range": [x0, x1],
            }

    # Insufficient or incompatible measurements.
    return None

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MEASUREMENTS = ROOT / "results/optimizer/measurements"


def select_reference(fpga_part):
    references = []

    for path in sorted(MEASUREMENTS.glob("*.json")):
        data = json.loads(path.read_text())

        if "fpga_part" in data and "measured_resources" in data:
            references.append(data)

    if not references:
        return {
            "status": "no_reference_available",
            "reference": None,
            "confidence": "none"
        }

    # Prefer an exact FPGA-device match.
    for reference in references:
        if reference["fpga_part"] == fpga_part:
            return {
                "status": "same_device_reference",
                "reference": reference,
                "reference_part": fpga_part,
                "confidence": "limited"
            }

    # Fallback for preliminary cross-device exploration.
    # This is NOT calibrated for the target FPGA.
    reference = references[0]

    return {
        "status": "cross_device_reference",
        "reference": reference,
        "reference_part": reference["fpga_part"],
        "target_part": fpga_part,
        "confidence": "very_low",
        "warning": (
            "Measurements come from another FPGA device. "
            "Resource, timing, and power estimates are "
            "not validated for the target."
        )
    }

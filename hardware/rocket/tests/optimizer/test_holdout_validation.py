import json
from pathlib import Path

import pytest

from optimizer.optimization.calibration import (
    estimate_calibrated_resources,
)

ROOT = Path(__file__).resolve().parents[2]

HOLDOUT = (
    ROOT / "results/optimizer/validation/"
    "arty100t_64k_holdout.json"
)

CALIBRATION = (
    ROOT / "results/optimizer/calibration/"
    "arty100t_scratchpad.json"
)


def test_64k_holdout_accuracy():
    data = json.loads(HOLDOUT.read_text())

    assert data["validation_type"] == "independent_64k_holdout"
    assert data["configuration"]["scratchpad_kib"] == 64
    assert data["bitstream_generated"] is True
    assert data["specified_timing_constraints_met"] is True
    assert data["physical_board_tested"] is False

    prediction = estimate_calibrated_resources(
        data["configuration"],
        data["fpga_part"],
    )

    assert prediction is not None
    assert prediction["validation"] == "measured_interpolation"

    expected_measured = {
        "luts": 42728,
        "flip_flops": 24549,
        "bram36_equivalent": 56,
        "dsp": 24,
    }

    assert data["measured_resources"] == expected_measured

    for resource, actual in expected_measured.items():
        predicted = prediction["resource_estimates"][resource]
        saved = data["comparison"][resource]

        assert saved["predicted"] == predicted
        assert saved["measured"] == actual

        error = abs(predicted - actual) / actual * 100

        assert saved["percentage_error"] == pytest.approx(
            error, abs=0.0001
        )

    assert data["comparison"]["luts"]["percentage_error"] < 1
    assert data["comparison"]["flip_flops"]["percentage_error"] < 1
    assert data["comparison"]["bram36_equivalent"]["absolute_error"] == 0
    assert data["comparison"]["dsp"]["absolute_error"] == 0


def test_64k_not_used_for_calibration():
    data = json.loads(CALIBRATION.read_text())

    sizes = sorted(
        observation["configuration"]["scratchpad_kib"]
        for observation in data["observations"]
    )

    assert sizes == [0, 128]
    assert 64 not in sizes

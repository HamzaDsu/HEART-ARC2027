import json

from optimizer.optimization.calibration import (
    estimate_calibrated_resources,
)
from optimizer.optimization.recommender import estimate


CONFIG = {
    "cores": 1,
    "icache_kib": 16,
    "dcache_kib": 16,
    "scratchpad_kib": 128,
    "l2_enabled": False,
    "target_clock_mhz": 50,
}

PART = "xc7a100tcsg324-1"

LIMITS = {
    "luts": 63400,
    "flip_flops": 126800,
    "bram36_sites": 135,
    "dsp_sites": 240,
}

MEASURED_0 = {
    "luts": 42106,
    "flip_flops": 24392,
    "bram36_equivalent": 40,
    "dsp": 24,
}

MEASURED_128 = {
    "luts": 42781,
    "flip_flops": 24550,
    "bram36_equivalent": 72,
    "dsp": 24,
}


def test_calibration_matching_and_interpolation(tmp_path):
    observations = []

    for size, resources in [
        (0, MEASURED_0),
        (128, MEASURED_128),
    ]:
        observations.append({
            "configuration": {
                **CONFIG,
                "scratchpad_kib": size,
            },
            "measured_resources": resources,
        })

    dataset = {
        "fpga_part": PART,
        "calibration_dimension": "scratchpad_kib",
        "observations": observations,
    }

    (tmp_path / "calibration.json").write_text(
        json.dumps(dataset)
    )

    def predict(size, config=None, part=PART):
        return estimate_calibrated_resources(
            config or {**CONFIG, "scratchpad_kib": size},
            part,
            calibration_dir=tmp_path,
        )

    # Exact measurement matches
    zero = predict(0)
    assert zero["validation"] == "measured_implementation"
    assert zero["resource_estimates"] == MEASURED_0

    full = predict(128)
    assert full["validation"] == "measured_implementation"
    assert full["resource_estimates"] == MEASURED_128

    # Intermediate interpolation
    middle = predict(64)

    assert middle["validation"] == "measured_interpolation"
    assert middle["resource_estimates"] == {
        "luts": 42444,
        "flip_flops": 24471,
        "bram36_equivalent": 56,
        "dsp": 24,
    }

    # Candidate generator adds non-hardware validation metadata.
    generated_candidate = predict(
        64,
        config={
            **CONFIG,
            "scratchpad_kib": 64,
            "validation": "not_synthesized",
        },
    )

    assert generated_candidate is not None
    assert generated_candidate["validation"] == "measured_interpolation"
    assert (
        generated_candidate["resource_estimates"]
        == middle["resource_estimates"]
    )

    # No extrapolation
    assert predict(256) is None

    # No cross-device calibration
    assert predict(64, part="different-fpga") is None

    # No calibration for changed processor parameters
    assert predict(
        64,
        config={
            **CONFIG,
            "scratchpad_kib": 64,
            "dcache_kib": 32,
        },
    ) is None


def test_main_estimator_integration(monkeypatch):
    reference = {
        "configuration": CONFIG,
        "measured_resources": MEASURED_128,
    }

    def fake_calibration(candidate, fpga_part):
        if fpga_part != PART:
            return None

        if candidate["scratchpad_kib"] != 64:
            return None

        return {
            "resource_estimates": {
                "luts": 42444,
                "flip_flops": 24471,
                "bram36_equivalent": 56,
                "dsp": 24,
            },
            "validation": "measured_interpolation",
            "source": "test-calibration",
        }

    monkeypatch.setattr(
        "optimizer.optimization.recommender."
        "estimate_calibrated_resources",
        fake_calibration,
    )

    candidate = {**CONFIG, "scratchpad_kib": 64}

    result = estimate(candidate, reference, LIMITS, PART)

    assert result is not None
    assert result["resource_validation"] == "measured_interpolation"
    assert result["resource_estimates"]["luts"] == 42444
    assert result["resource_estimates"]["bram36_equivalent"] == 56

    # Unsupported configuration uses the heuristic
    fallback = estimate(
        {**CONFIG, "scratchpad_kib": 256},
        reference,
        LIMITS,
        PART,
    )

    assert fallback is not None
    assert fallback["resource_validation"] == "heuristic_only"

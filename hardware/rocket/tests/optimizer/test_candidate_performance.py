import pytest

from optimizer.optimization.candidate_performance import (
    assess_candidate_performance,
)


CANDIDATE = {
    "cores": 1,
    "icache_kib": 16,
    "dcache_kib": 16,
    "scratchpad_kib": 0,
    "l2_enabled": False,
    "target_clock_mhz": 50,
}

WORKLOAD = {
    "parameters": 1_000_000_000,
    "context_length": 1024,
    "weights_mib": 100.0,
    "illustrative_f16_kv_cache_mib": 16.0,
}

FPGA = "xc7a100tcsg324-1"


def profile(**changes):
    result = {
        "fpga_part": FPGA,
        "configuration": CANDIDATE.copy(),
        "provenance": "user_assumption",
        "bandwidth_mib_s": 100,
        "compute_gops_s": 10,
    }
    result.update(changes)
    return result


def assess(rate_profile=None, fpga_part=FPGA):
    return assess_candidate_performance(
        CANDIDATE,
        WORKLOAD,
        past_tokens=128,
        fpga_part=fpga_part,
        rate_profile=rate_profile,
    )


def test_missing_hardware_rates():
    result = assess()

    assert result["status"] == "candidate_hardware_rates_missing"
    assert result["idealized_tokens_per_second"] is None
    assert result["eligible_for_automatic_ranking"] is False


def test_matching_configuration():
    result = assess(profile())

    assert result["status"] == "conditional_roofline_estimate"
    assert result["candidate_specific"] is True
    assert result["idealized_tokens_per_second"] > 0
    assert result["rate_provenance"] == "user_assumption"
    assert result["hardware_execution_measured"] is False
    assert result["eligible_for_automatic_ranking"] is False


def test_wrong_fpga_rejected():
    result = assess(
        profile(),
        fpga_part="different-fpga"
    )

    assert result["status"] == "rate_profile_mismatch"
    assert result["idealized_tokens_per_second"] is None


@pytest.mark.parametrize(
    "field,value",
    [
        ("cores", 2),
        ("dcache_kib", 32),
        ("scratchpad_kib", 64),
    ],
)
def test_wrong_configuration_rejected(field, value):
    modified = CANDIDATE.copy()
    modified[field] = value

    result = assess(profile(configuration=modified))

    assert result["status"] == "rate_profile_mismatch"
    assert result["eligible_for_automatic_ranking"] is False


def test_incomplete_rates():
    result = assess(profile(compute_gops_s=None))

    assert result["status"] == "hardware_rates_incomplete"
    assert result["idealized_tokens_per_second"] is None


def test_invalid_profile_type():
    with pytest.raises(ValueError):
        assess("invalid-profile")


def test_unsupported_provenance_rejected():
    with pytest.raises(ValueError):
        assess(profile(provenance="measured"))


def test_invalid_hardware_rate_rejected():
    with pytest.raises(ValueError):
        assess(profile(compute_gops_s=-1))


def test_higher_bandwidth_improves_idealized_bound():
    slow = assess(profile(bandwidth_mib_s=100))
    fast = assess(profile(bandwidth_mib_s=200))

    assert (
        fast["idealized_tokens_per_second"]
        > slow["idealized_tokens_per_second"]
    )

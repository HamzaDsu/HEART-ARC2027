import pytest

from optimizer.optimization.workload_performance import (
    estimate_decode_performance,
)


WORKLOAD = {
    "parameters": 1_000_000_000,
    "context_length": 1024,
    "weights_mib": 100.0,
    "illustrative_f16_kv_cache_mib": 16.0,
}


def estimate(**rates):
    return estimate_decode_performance(
        WORKLOAD, past_tokens=128, **rates
    )


def test_memory_bound():
    result = estimate(
        bandwidth_mib_s=100,
        compute_gops_s=10,
    )

    assert result["status"] == "conditional_roofline_estimate"
    assert result["bottleneck"] == "memory"
    assert result["memory_time_ms"] > result["compute_time_ms"]
    assert result["validation"] == "assumption_based_only"


def test_compute_bound():
    result = estimate(
        bandwidth_mib_s=10000,
        compute_gops_s=1,
    )

    assert result["bottleneck"] == "compute"
    assert result["compute_time_ms"] > result["memory_time_ms"]


def test_incomplete_hardware_rates():
    result = estimate(bandwidth_mib_s=100)

    assert result["status"] == "hardware_rates_incomplete"
    assert result["idealized_tokens_per_second"] is None


def test_missing_model_metadata():
    workload = {**WORKLOAD, "parameters": None}

    result = estimate_decode_performance(
        workload,
        past_tokens=128,
        bandwidth_mib_s=100,
        compute_gops_s=10,
    )

    assert result["status"] == "insufficient_data"


def test_higher_bandwidth_improves_memory_bound():
    slow = estimate(
        bandwidth_mib_s=100,
        compute_gops_s=1000,
    )
    fast = estimate(
        bandwidth_mib_s=200,
        compute_gops_s=1000,
    )

    assert (
        fast["idealized_tokens_per_second"]
        > slow["idealized_tokens_per_second"]
    )


def test_higher_compute_improves_compute_bound():
    slow = estimate(
        bandwidth_mib_s=10000,
        compute_gops_s=1,
    )
    fast = estimate(
        bandwidth_mib_s=10000,
        compute_gops_s=2,
    )

    assert (
        fast["idealized_tokens_per_second"]
        > slow["idealized_tokens_per_second"]
    )


@pytest.mark.parametrize(
    "invalid",
    [0, -1, True, float("nan"), float("inf")],
)
@pytest.mark.parametrize(
    "rate_name",
    ["bandwidth_mib_s", "compute_gops_s"],
)
def test_invalid_hardware_rates(invalid, rate_name):
    with pytest.raises(ValueError):
        estimate(**{rate_name: invalid})

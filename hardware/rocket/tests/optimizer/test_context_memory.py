import pytest

from optimizer.optimization.context_memory import estimate_runtime_memory


def test_health_slm_does_not_fit():
    workload = {
        "context_length": 2048,
        "weights_mib": 475.51,
        "illustrative_f16_kv_cache_mib": 44.0,
    }

    result = estimate_runtime_memory(
        workload,
        runtime_context_length=1024,
        available_ram_mib=256,
    )

    assert result["estimated_required_mib"] == 497.51
    assert result["fits_estimated_memory"] is False


def test_smollm2_fits_estimated_memory():
    workload = {
        "context_length": 8192,
        "weights_mib": 87.48,
        "illustrative_f16_kv_cache_mib": 180.0,
    }

    result = estimate_runtime_memory(
        workload,
        runtime_context_length=1024,
        available_ram_mib=256,
    )

    assert result["estimated_required_mib"] == 109.98
    assert result["fits_estimated_memory"] is True


def test_runtime_overhead_increases_memory():
    workload = {
        "context_length": 8192,
        "weights_mib": 87.48,
        "illustrative_f16_kv_cache_mib": 180.0,
    }

    result = estimate_runtime_memory(
        workload,
        runtime_context_length=1024,
        available_ram_mib=256,
        runtime_overhead_mib=64,
    )

    assert result["estimated_required_mib"] == 173.98


@pytest.mark.parametrize("context", [0, -1, 8193])
def test_invalid_context_rejected(context):
    workload = {
        "context_length": 8192,
        "weights_mib": 87.48,
        "illustrative_f16_kv_cache_mib": 180.0,
    }

    with pytest.raises(ValueError):
        estimate_runtime_memory(
            workload,
            runtime_context_length=context,
            available_ram_mib=256,
        )

"""Candidate-specific conditional performance assessment."""

from optimizer.optimization.workload_performance import (
    estimate_decode_performance,
)

CONFIG_FIELDS = (
    "cores",
    "icache_kib",
    "dcache_kib",
    "scratchpad_kib",
    "l2_enabled",
    "target_clock_mhz",
)


def assess_candidate_performance(
    candidate,
    workload,
    past_tokens,
    *,
    fpga_part,
    rate_profile=None,
):
    """Apply only explicitly matched hardware-rate assumptions."""

    configuration = {
        key: candidate[key] for key in CONFIG_FIELDS
    }

    result = {
        "configuration": configuration,
        "fpga_part": fpga_part,
        "status": "candidate_hardware_rates_missing",
        "candidate_specific": False,
        "eligible_for_automatic_ranking": False,
        "idealized_tokens_per_second": None,
        "hardware_execution_measured": False,
    }

    if rate_profile is None:
        return result

    if not isinstance(rate_profile, dict):
        raise ValueError("rate_profile must be a dictionary")

    if (
        rate_profile.get("fpga_part") != fpga_part
        or rate_profile.get("configuration") != configuration
    ):
        return {
            **result,
            "status": "rate_profile_mismatch",
        }

    if rate_profile.get("provenance") != "user_assumption":
        raise ValueError("Unsupported hardware-rate provenance")

    performance = estimate_decode_performance(
        workload,
        past_tokens,
        bandwidth_mib_s=rate_profile.get("bandwidth_mib_s"),
        compute_gops_s=rate_profile.get("compute_gops_s"),
    )

    return {
        **result,
        **performance,
        "configuration": configuration,
        "fpga_part": fpga_part,
        "rate_provenance": "user_assumption",
        "candidate_specific": True,
        "eligible_for_automatic_ranking": False,
        "hardware_execution_measured": False,
    }

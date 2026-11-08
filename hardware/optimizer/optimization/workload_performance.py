"""Experimental compute and memory performance envelope."""

import math

from optimizer.optimization.traffic_model import estimate_decode_traffic


def _validate_rate(value, name):
    if value is None:
        return

    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value <= 0
    ):
        raise ValueError(f"{name} must be a finite positive number")


def estimate_decode_performance(
    workload,
    past_tokens,
    *,
    bandwidth_mib_s=None,
    compute_gops_s=None,
):
    """Estimate an idealized decode performance envelope.

    Both hardware rates are assumptions, not measurements.
    The compute proxy approximates two operations per parameter
    and excludes attention overhead and other runtime costs.
    """

    _validate_rate(bandwidth_mib_s, "bandwidth_mib_s")
    _validate_rate(compute_gops_s, "compute_gops_s")

    traffic = estimate_decode_traffic(workload, past_tokens)
    parameters = workload.get("parameters")

    result = {
        "status": "insufficient_data",
        "traffic": traffic,
        "estimated_compute_ops": None,
        "memory_time_ms": None,
        "compute_time_ms": None,
        "idealized_time_ms": None,
        "idealized_tokens_per_second": None,
        "bottleneck": "unknown",
        "validation": "assumption_based_only",
    }

    if traffic["status"] != "estimated":
        return result

    if (
        isinstance(parameters, bool)
        or not isinstance(parameters, (int, float))
        or not math.isfinite(parameters)
        or parameters <= 0
    ):
        return result

    # Approximate dense matrix-vector work per generated token.
    operations = 2 * parameters
    result["estimated_compute_ops"] = operations

    if bandwidth_mib_s is not None:
        result["memory_time_ms"] = (
            traffic["total_traffic_mib"] / bandwidth_mib_s * 1000
        )

    if compute_gops_s is not None:
        result["compute_time_ms"] = (
            operations / (compute_gops_s * 1e9) * 1000
        )

    memory_ms = result["memory_time_ms"]
    compute_ms = result["compute_time_ms"]

    if memory_ms is None or compute_ms is None:
        result["status"] = "hardware_rates_incomplete"
        return result

    idealized_ms = max(memory_ms, compute_ms)

    if idealized_ms <= 0:
        return result

    result.update({
        "status": "conditional_roofline_estimate",
        "idealized_time_ms": round(idealized_ms, 4),
        "idealized_tokens_per_second": round(
            1000 / idealized_ms, 4
        ),
        "bottleneck": (
            "memory" if memory_ms > compute_ms
            else "compute" if compute_ms > memory_ms
            else "equal"
        ),
    })

    return result

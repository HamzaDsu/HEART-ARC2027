from optimizer.optimization.traffic_model import estimate_decode_traffic


def estimate_memory_bound(
    workload,
    past_tokens,
    sustained_bandwidth_mib_s=None,
):
    traffic = estimate_decode_traffic(workload, past_tokens)

    result = {
        "traffic": traffic,
        "status": "bandwidth_unknown",
        "transfer_time_ms": None,
        "memory_only_tokens_per_second": None,
        "confidence": "uncalibrated",
    }

    if traffic["status"] != "estimated":
        result["status"] = "traffic_unknown"
        return result

    if sustained_bandwidth_mib_s is None:
        return result

    if (
        isinstance(sustained_bandwidth_mib_s, bool)
        or not isinstance(sustained_bandwidth_mib_s, (int, float))
        or sustained_bandwidth_mib_s <= 0
    ):
        raise ValueError("Bandwidth must be a positive number")

    total = traffic["total_traffic_mib"]
    transfer_seconds = total / sustained_bandwidth_mib_s

    result.update({
        "status": "conditional_memory_bound",
        "assumed_bandwidth_mib_s": sustained_bandwidth_mib_s,
        "transfer_time_ms": round(transfer_seconds * 1000, 3),
        "memory_only_tokens_per_second": round(
            1 / transfer_seconds, 3
        ),
        "note": (
            "Idealized memory-only bound under the supplied "
            "sustained-bandwidth and traffic assumptions. "
            "Not measured inference performance."
        ),
    })

    return result

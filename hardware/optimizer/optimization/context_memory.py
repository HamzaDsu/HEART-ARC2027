def estimate_runtime_memory(
    workload,
    runtime_context_length,
    available_ram_mib,
    runtime_overhead_mib=0.0,
):
    max_context = workload["context_length"]

    if not isinstance(runtime_context_length, int) or isinstance(runtime_context_length, bool):
        raise ValueError("Runtime context must be an integer")

    if not 1 <= runtime_context_length <= max_context:
        raise ValueError(
            f"Runtime context must be between 1 and {max_context}"
        )

    if available_ram_mib <= 0 or runtime_overhead_mib < 0:
        raise ValueError("Invalid memory capacity or overhead")

    weights = workload["weights_mib"]
    full_kv = workload["illustrative_f16_kv_cache_mib"]

    runtime_kv = full_kv * runtime_context_length / max_context
    required = weights + runtime_kv + runtime_overhead_mib

    return {
        "runtime_context_length": runtime_context_length,
        "max_context_length": max_context,
        "weights_mib": round(weights, 2),
        "runtime_f16_kv_cache_mib": round(runtime_kv, 2),
        "runtime_overhead_mib": round(runtime_overhead_mib, 2),
        "estimated_required_mib": round(required, 2),
        "available_ram_mib": available_ram_mib,
        "estimated_headroom_mib": round(
            available_ram_mib - required, 2
        ),
        "fits_estimated_memory": required <= available_ram_mib,
        "validation": "metadata_based_estimate",
        "note": (
            "Assumes FP16 KV cache scaling linearly with context. "
            "Excludes unmodeled buffers, runtime allocations and "
            "hardware execution validation."
        ),
    }

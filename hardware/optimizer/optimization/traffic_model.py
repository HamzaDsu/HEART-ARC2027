def estimate_decode_traffic(workload, past_tokens):
    """Estimate single-token decode memory traffic.

    Assumptions:
    - Batch size 1
    - Dense inference
    - Weights read once per generated token
    - FP16 KV cache
    - No memory caching benefits included
    """

    context = workload.get("context_length")
    weights = workload.get("weights_mib")
    kv_full = workload.get("illustrative_f16_kv_cache_mib")

    if not isinstance(context, int) or context <= 0:
        raise ValueError("Invalid context length")

    if not isinstance(past_tokens, int) or isinstance(past_tokens, bool):
        raise ValueError("past_tokens must be an integer")

    if not 0 <= past_tokens < context:
        raise ValueError("past_tokens outside context")

    if weights is None or kv_full is None:
        return {
            "status": "insufficient_model_metadata",
            "total_traffic_mib": None,
        }

    kv_per_token = kv_full / context

    weight_reads = weights
    kv_reads = past_tokens * kv_per_token
    kv_writes = kv_per_token

    total = weight_reads + kv_reads + kv_writes

    return {
        "status": "estimated",
        "past_tokens": past_tokens,
        "weight_reads_mib": round(weight_reads, 3),
        "kv_reads_mib": round(kv_reads, 3),
        "kv_writes_mib": round(kv_writes, 3),
        "total_traffic_mib": round(total, 3),
        "confidence": "uncalibrated",
        "note": "Idealized traffic estimate, not measured bandwidth.",
    }

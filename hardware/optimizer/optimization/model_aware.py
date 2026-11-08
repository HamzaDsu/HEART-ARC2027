def model_fit(candidate, workload):
    hidden = workload.get("embedding_size")
    bits = workload.get("average_stored_bits_per_parameter")

    if not hidden or not bits or hidden <= 0 or bits <= 0:
        return {
            "factor": 1.0,
            "status": "insufficient_model_metadata"
        }

    # Illustrative FP32 activation vector.
    activation_kib = hidden * 4 / 1024

    # Illustrative quantized tile containing 64 output rows.
    weight_tile_kib = 64 * hidden * bits / (8 * 1024)

    cache_coverage = min(
        1.0, candidate["dcache_kib"] / activation_kib
    )
    scratchpad_coverage = min(
        1.0, candidate["scratchpad_kib"] / weight_tile_kib
    )

    # Scratchpad capacity alone does not prove a performance gain.
    # Keep scratchpad performance neutral until measured,
    # placement-aware latency estimates are available.
    factor = 0.85 + 0.15 * cache_coverage

    return {
        "factor": round(factor, 4),
        "activation_vector_kib": round(activation_kib, 2),
        "weight_tile_kib": round(weight_tile_kib, 2),
        "cache_coverage": round(cache_coverage, 4),
        "scratchpad_coverage": round(scratchpad_coverage, 4),
        "status": "exploratory_workload_heuristic"
    }

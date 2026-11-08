from optimizer.optimization.model_aware import model_fit
from optimizer.optimization.bandwidth_model import estimate_memory_bound


def evaluate_hardware_cost(
    candidate,
    workload,
    board_resources,
    resource_estimates=None,
    *,
    past_tokens=1024,
    bandwidth_mib_s=None,
    external_ram_mib=None,
):
    """
    Preliminary candidate-specific hardware assessment.

    This is NOT a measured latency/power prediction.
    """

    fit = model_fit(candidate, workload)

    memory = estimate_memory_bound(
        workload,
        past_tokens,
        sustained_bandwidth_mib_s=bandwidth_mib_s,
    )

    cores = candidate["cores"]
    icache = candidate["icache_kib"]
    dcache = candidate["dcache_kib"]
    scratchpad = candidate["scratchpad_kib"]

    # Minimum modeled on-chip memory footprint.
    # Excludes metadata, buffers, tags, and other memories.
    minimum_storage_kib = (
        scratchpad + cores * (icache + dcache)
    )

    bram_sites = board_resources.get("bram36_sites")

    # One RAMB36 site provides 36 Kibit = 4.5 KiB.
    # RAMB18 sites overlap RAMB36 sites; do not add both.
    raw_bram_kib = (
        bram_sites * 4.5
        if bram_sites is not None
        else None
    )

    storage_exceeds_raw_bram = (
        minimum_storage_kib > raw_bram_kib
        if raw_bram_kib is not None
        else None
    )

    # Compare existing heuristic estimates with actual
    # FPGA resource limits.
    limits = {
        "luts": "luts",
        "flip_flops": "flip_flops",
        "bram36_equivalent": "bram36_sites",
        "dsp": "dsp_sites",
    }

    utilization = {}
    exceeded = []

    if resource_estimates:
        for estimated_key, limit_key in limits.items():
            used = resource_estimates.get(estimated_key)
            available = board_resources.get(limit_key)

            if used is None or not available:
                continue

            ratio = used / available

            utilization[estimated_key] = round(ratio, 4)

            if used > available:
                exceeded.append(estimated_key)

    weights = workload.get("weights_mib")
    kv = workload.get("illustrative_f16_kv_cache_mib")

    required_ram = (
        weights + kv
        if weights is not None and kv is not None
        else None
    )

    resident_model_fits = (
        required_ram <= external_ram_mib
        if required_ram is not None
        and external_ram_mib is not None
        else None
    )

    return {
        "candidate": candidate,
        "model_working_set": fit,
        "estimated_memory": memory,
        "hardware_resources": {
            "minimum_storage_kib": minimum_storage_kib,
            "raw_bram_capacity_kib": raw_bram_kib,
            "minimum_storage_exceeds_raw_bram":
                storage_exceeds_raw_bram,
            "estimated_utilization": utilization,
            "estimated_resource_overflows": exceeded,
        },
        "resident_model_memory": {
            "required_mib": (
                round(required_ram, 2)
                if required_ram is not None else None
            ),
            "available_mib": external_ram_mib,
            "fits": resident_model_fits,
        },
        "confidence": "uncalibrated",
        "limitations": [
            "Resource estimates are heuristic.",
            "Working-set coverage does not predict cache misses.",
            "Clock timing for this candidate is unverified.",
            "Bandwidth estimates depend on supplied assumptions.",
            "No measured power or energy prediction.",
            "Streaming and model partitioning are not evaluated.",
        ],
    }

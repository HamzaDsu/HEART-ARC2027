"""Experimental RTL-guided V5 balanced selection.

Measurements represent 128-output convolution tiles, not
full-model latency or physical FPGA performance.
"""

import math

from optimizer.optimization.rtl_tile_calibration import load_measurements


VISION_MODELS = ("resnet18", "vgg11", "swint", "levit384")


def select_rtl_guided(model, ranked, candidates, tolerance_pct=0.1):
    if not math.isfinite(tolerance_pct) or tolerance_pct < 0:
        raise ValueError("Invalid performance tolerance")

    decision = {
        "mode": "experimental_rtl_guided_balanced",
        "status": "not_applied",
        "model": model,
        "tolerance_pct": tolerance_pct,
        "evidence_scope": "128_output_first_convolution_tile",
        "full_model_latency_validated": False,
        "physical_fpga_validated": False,
        "resource_basis": "optimizer_resource_cost_estimate",
    }

    if model not in VISION_MODELS or not ranked:
        decision["reason"] = "unsupported_model_or_empty_ranking"
        return ranked, decision

    baseline = ranked[0]
    cfg32 = baseline["configuration"]

    required = {
        "cores": 1,
        "icache_kib": 32,
        "dcache_kib": 32,
        "scratchpad_kib": 0,
        "l2_enabled": False,
        "target_clock_mhz": 50,
    }

    if any(cfg32.get(k) != v for k, v in required.items()):
        decision["reason"] = "baseline_not_eligible"
        return ranked, decision

    cfg16 = dict(cfg32)
    cfg16["icache_kib"] = 16
    cfg16["dcache_kib"] = 16

    matches = [
        x for x in candidates
        if x["configuration"] == cfg16
    ]

    if len(matches) != 1:
        decision["reason"] = "no_matching_16k_candidate"
        return ranked, decision

    alternative = matches[0]

    cost32 = float(baseline["resource_cost"])
    cost16 = float(alternative["resource_cost"])

    if not (
        math.isfinite(cost16)
        and math.isfinite(cost32)
        and cost16 < cost32
    ):
        decision["reason"] = "no_estimated_resource_saving"
        return ranked, decision

    measurements = load_measurements()

    cycles16 = measurements[(model, 16)]
    cycles32 = measurements[(model, 32)]

    slowdown = 100.0 * (cycles16 / cycles32 - 1)

    decision.update({
        "measured_cycles_16k": cycles16,
        "measured_cycles_32k": cycles32,
        "measured_slowdown_pct": round(slowdown, 6),
        "estimated_cost_16k": cost16,
        "estimated_cost_32k": cost32,
    })

    if slowdown > tolerance_pct:
        decision["reason"] = "slowdown_exceeds_tolerance"
        return ranked, decision

    decision["status"] = "selected_16k_experimentally"
    decision["reason"] = "measured_tile_tradeoff"

    selected = {
        **alternative,
        "rtl_guided_selection": decision,
    }

    return [selected] + [
        x for x in ranked
        if x["configuration"] != cfg16
    ], decision

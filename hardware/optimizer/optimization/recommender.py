import argparse
import json
import math
from pathlib import Path

from optimizer.optimization.candidates import generate_candidates
from optimizer.board_analyzer.vivado_resources import discover_resources
from optimizer.model_analyzer.workload_profile import build_workload_profile
from optimizer.optimization.model_aware import model_fit
from optimizer.optimization.reference_selector import select_reference
from optimizer.optimization.calibration import estimate_calibrated_resources
from optimizer.optimization.bandwidth_model import estimate_memory_bound
from optimizer.optimization.hardware_cost import evaluate_hardware_cost
from optimizer.optimization.pareto import pareto_front

REF_DIR = Path("results/optimizer/measurements")


def find_reference(fpga_part):
    for path in sorted(REF_DIR.glob("*.json")):
        data = json.loads(path.read_text())
        if data.get("fpga_part") == fpga_part:
            return data
    return None


def estimate(candidate, reference, limits, target_fpga_part=None):
    cfg = reference["configuration"]
    measured = reference["measured_resources"]

    cores = candidate["cores"]
    cache = candidate["dcache_kib"]
    scratchpad = candidate["scratchpad_kib"]
    l2 = candidate["l2_enabled"]
    clock = candidate["target_clock_mhz"]

    # Preliminary assumptions, NOT trained hardware predictions.
    area_scale = (
        1
        + 0.65 * (cores - cfg["cores"])
        + 0.04 * (cache - cfg["dcache_kib"]) / 8
        + (0.35 if l2 else 0)
    )

    luts = round(measured["luts"] * area_scale)
    ffs = round(
        measured["flip_flops"]
        * (1 + 0.65 * (cores - 1) + (0.20 if l2 else 0))
    )
    # Nexys Video BRAM model fitted to measured 1-core
    # cache points at 8, 16, 32 KiB and 2-core 8/32 KiB.
    #
    # Measured BRAM36:
    #   1 core,  8 KiB I/D: 40
    #   1 core, 16 KiB I/D: 40
    #   1 core, 32 KiB I/D: 48
    #   2 cores, 8 KiB I/D: 80
    #   2 cores, 32 KiB I/D: 96
    #
    # A separate 1-core, 32-KiB comparison with a 64-KiB
    # scratchpad showed +16 BRAM36. Applying that overhead
    # elsewhere is an unvalidated heuristic assumption.
    #
    # Predictions between measured cache sizes and at
    # other clocks/scratchpad sizes remain heuristic.
    if (
        target_fpga_part == "xc7a200tsbg484-1"
        and cores in (1, 2)
        and candidate["icache_kib"] == cache
        and cache in (8, 16, 32)
        and scratchpad in (0, 64, 128, 256)
        and clock in (25, 50)
        and not l2
    ):
        from math import ceil

        # Discrete cache geometry follows recorded BRAM
        # usage. The 2-core 16-KiB point is inferred.
        one_core_bram = {8: 40, 16: 40, 32: 48}[cache]

        # Scratchpad scaling is extrapolated from one
        # 1-core 64-KiB observation; not yet verified
        # for multi-core or larger scratchpads.
        bram = ceil(
            cores * one_core_bram
            + scratchpad / 4
        )
    else:
        # Legacy fallback outside the measured architecture
        # family. Not validated against physical hardware.
        bram = round(
            measured["bram36_equivalent"]
            + (scratchpad - cfg["scratchpad_kib"]) / 4.5
            + cores * 2 * (cache - cfg["dcache_kib"]) / 4.5
            + (48 if l2 else 0)
        )
    dsp = round(measured["dsp"] * cores)

    estimated = {
        "luts": luts,
        "flip_flops": ffs,
        "bram36_equivalent": max(0, bram),
        "dsp": dsp,
    }

    calibration = (
        estimate_calibrated_resources(candidate, target_fpga_part)
        if target_fpga_part is not None
        else None
    )

    if calibration is not None:
        estimated = calibration["resource_estimates"]

    available = {
        "luts": limits["luts"],
        "flip_flops": limits["flip_flops"],
        "bram36_equivalent": limits["bram36_sites"],
        "dsp": limits["dsp_sites"],
    }

    if any(estimated[k] > available[k] or estimated[k] < 0
           for k in available):
        return None

    # Dimensionless exploration proxies, not benchmark results.
    freq_ratio = clock / cfg["target_clock_mhz"]
    throughput_proxy = (
        freq_ratio * (1 + 0.15 * (cores - 1))
        * (1 + 0.015 * (cache / cfg["dcache_kib"] - 1))
    )
    switching_proxy = freq_ratio * (0.4 + 0.6 * area_scale)

    return {
        "configuration": candidate,
        "resource_estimates": estimated,
        "throughput_proxy": round(throughput_proxy, 4),
        "switching_proxy": round(switching_proxy, 4),
        "validation": "heuristic_only",
        "resource_validation": (
            calibration["validation"]
            if calibration is not None
            else "heuristic_only"
        ),
        "resource_calibration_source": (
            calibration["source"]
            if calibration is not None
            else None
        ),
    }



def rank_pareto(front, objective, limits):
    """Rank Pareto alternatives using uncalibrated metrics."""
    if not front:
        return []

    speeds = [x["score"] for x in front]
    luts = [x["resource_estimates"]["luts"] for x in front]
    brams = [x["resource_estimates"]["bram36_equivalent"] for x in front]

    def benefit(value, values, maximize=True):
        low, high = min(values), max(values)
        if high == low:
            return 1.0
        return (
            (value - low) / (high - low)
            if maximize
            else (high - value) / (high - low)
        )

    for item in front:
        r = item["resource_estimates"]

        if objective == "performance":
            score = item["score"]

        elif objective == "resources":
            score = -(
                0.5 * r["luts"] / max(limits["luts"], 1)
                + 0.5 * r["bram36_equivalent"]
                / max(limits["bram36_sites"], 1)
            )

        elif objective == "balanced":
            speed_gain = benefit(item["score"], speeds)
            lut_gain = benefit(r["luts"], luts, False)
            bram_gain = benefit(
                r["bram36_equivalent"], brams, False
            )

            # Minimize distance to ideal normalized objectives.
            score = -(
                (1 - speed_gain) ** 2
                + (1 - lut_gain) ** 2
                + (1 - bram_gain) ** 2
            ) ** 0.5

        else:
            raise ValueError(f"Unsupported Pareto objective: {objective}")

        item["pareto_objective_score"] = round(score, 6)

    return sorted(
        front,
        key=lambda x: (
            -x["pareto_objective_score"],
            -x["score"],
            x["resource_estimates"]["luts"],
        ),
    )


def recommend(
    model, board, objective, scope, ram_mib, top,
    *, runtime_context_length=None, runtime_overhead_mib=0.0,
):
    if runtime_context_length is None:
        generated = generate_candidates(model, board, ram_mib, scope)
    else:
        generated = generate_candidates(
            model, board, ram_mib, scope,
            runtime_context_length=runtime_context_length,
            runtime_overhead_mib=runtime_overhead_mib,
        )

    if generated["status"] != "candidate_generation_complete":
        return generated

    device = discover_resources(board)
    if device["status"] != "resources_discovered":
        return device

    workload = build_workload_profile(model)

    part = device["board"]["fpga_part"]
    reference_info = select_reference(part)
    reference = reference_info["reference"]
    cross_device = reference_info["status"] == "cross_device_reference"

    if reference is None:
        return {
            "status": "reference_data_required",
            "fpga_part": part,
            "candidate_count": generated["candidate_count"],
            "message": "No hardware reference measurements available."
        }

    ranked = []

    for candidate in generated["candidates"]:
        # Conservative ceiling from the measured reference design.
        # Lower clocks and other configurations remain unverified.
        reference_clock = reference["configuration"]["target_clock_mhz"]

        if not cross_device and candidate["target_clock_mhz"] > reference_clock:
            continue

        result = estimate(
            candidate, reference, device["resources"], part
        )

        if result is None:
            continue

        fit = model_fit(candidate, workload)
        result["model_fit"] = fit
        speed = result["throughput_proxy"] * fit["factor"]
        result["model_adjusted_throughput_proxy"] = round(speed, 4)
        switching = max(result["switching_proxy"], 0.001)

        if objective == "performance":
            score = speed
        elif objective == "power":
            score = -switching
        else:
            score = speed / math.sqrt(switching)

        result["priority_score"] = round(score, 5)
        result["matches_implemented_baseline"] = all(
            candidate[key] == value
            for key, value in reference["configuration"].items()
        )
        ranked.append(result)

    ranked.sort(key=lambda item: item["priority_score"], reverse=True)

    # Pareto optimization: maximize model-aware throughput proxy,
    # minimize estimated LUT and BRAM usage.
    pareto = pareto_front([
        {**item, "score": item["model_adjusted_throughput_proxy"]}
        for item in ranked
    ])

    if objective == "power":
        # Preserve legacy behavior, but this is NOT measured power.
        ordered_candidates = ranked
        selected_candidate = ranked[0] if ranked else None
    else:
        ordered_candidates = rank_pareto(
            pareto, objective, device["resources"]
        )
        selected_candidate = (
            ordered_candidates[0] if ordered_candidates else None
        )

    return {
        "status": "heuristic_ranking_complete",
        "objective": objective,
        "model": generated["model"],
        "workload_profile": workload,
        "runtime_memory_assessment": generated.get(
            "runtime_memory_assessment"
        ),
        "full_context_memory_status": generated.get("memory_status"),
        "board": generated["board"],
        "scope": scope,
        "generated_candidates": generated["candidate_count"],
        "screened_candidates": len(ranked),
        "reference_configuration": reference["configuration"],
        "reference_timing": reference["timing"],
        "reference_selection": {
            "status": reference_info["status"],
            "reference_part": reference_info.get("reference_part"),
            "target_part": part,
            "confidence": reference_info["confidence"],
        },
        "implemented_reference": {
            "configuration": reference["configuration"],
            "measured_resources": reference["measured_resources"],
            "timing": reference["timing"],
            "hardware_status": reference["hardware_status"],
        },
        "recommendation_status": (
            "cross_device_unvalidated" if cross_device
            else "estimated_not_synthesized"
        ),
        "frequency_limit_note": (
            "The clock ceiling comes from the implemented "
            "reference. Timing is not guaranteed for other designs."
        ),
        "recommended_exploration_candidate": (
            selected_candidate
        ),
        "pareto_candidate_count": len(pareto),
        "pareto_front": pareto,
        "selection_method": (
            "legacy_switching_proxy"
            if objective == "power"
            else "estimated_pareto_tradeoff"
        ),
        "selection_note": (
            "Switching proxy is not measured power."
            if objective == "power"
            else "Estimates are heuristic and not hardware validated."
        ),
        "top_candidates": ordered_candidates[:top],
        "confidence": "very_low" if cross_device else "low",
        "limitations": [
            "Hardware resource figures for new candidates are heuristic.",
            "No measured power or full-model inference performance.",
            "No timing or implementation guarantee for new candidates.",
            "Core-count scaling assumes potential parallel throughput.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(
        description="Experimental SLM-FPGA recommendation engine"
    )
    parser.add_argument("--model", required=True)
    parser.add_argument("--board", required=True)
    parser.add_argument(
        "--objective",
        choices=['performance', 'power', 'balanced', 'resources'],
        default="balanced",
    )
    parser.add_argument(
        "--scope",
        choices=["full-model", "kernel"],
        default="full-model",
    )
    parser.add_argument("--external-ram-mib", type=int)
    parser.add_argument("--top", type=int, default=5)
    parser.add_argument("--past-tokens", type=int)
    parser.add_argument("--assumed-bandwidth-mib-s", type=float)
    parser.add_argument("--runtime-context-length", type=int)
    parser.add_argument("--runtime-overhead-mib", type=float, default=0.0)
    parser.add_argument("--output")
    args = parser.parse_args()

    result = recommend(
        args.model,
        args.board,
        args.objective,
        args.scope,
        args.external_ram_mib,
        args.top,
        runtime_context_length=args.runtime_context_length,
        runtime_overhead_mib=args.runtime_overhead_mib,
    )

    if args.past_tokens is not None:
        if result.get("status") == "heuristic_ranking_complete":
            result["memory_bandwidth_analysis"] = estimate_memory_bound(
                result["workload_profile"],
                past_tokens=args.past_tokens,
                sustained_bandwidth_mib_s=args.assumed_bandwidth_mib_s,
            )

    if result.get("status") == "heuristic_ranking_complete":
        best = result.get("recommended_exploration_candidate")
        workload = result.get("workload_profile")

        if best and workload:
            context = workload.get("context_length")

            if isinstance(context, int) and context > 0:
                board_info = discover_resources(args.board)

                if board_info.get("status") == "resources_discovered":
                    tokens = (
                        args.past_tokens
                        if args.past_tokens is not None
                        else min(1024, context - 1)
                    )

                    best["hardware_cost"] = evaluate_hardware_cost(
                        candidate=best["configuration"],
                        workload=workload,
                        board_resources=board_info["resources"],
                        resource_estimates=best.get("resource_estimates"),
                        past_tokens=tokens,
                        bandwidth_mib_s=args.assumed_bandwidth_mib_s,
                        external_ram_mib=args.external_ram_mib,
                    )

    text = json.dumps(result, indent=2)
    print(text)

    if args.output:
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

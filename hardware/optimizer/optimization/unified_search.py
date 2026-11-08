"""Shared exploratory FPGA search for GGUF and vision networks.

Scores are estimates, not measured inference speed or power.
"""
import csv
import json
from itertools import product
from pathlib import Path

from optimizer.board_analyzer.vivado_resources import discover_resources
from optimizer.optimization.model_aware import model_fit
from optimizer.optimization.pareto import pareto_front
from optimizer.optimization.recommender import estimate
from optimizer.optimization.vision_workload_recommender import evaluate

ROOT = Path(__file__).resolve().parents[2]
MODELS = ("healthslm", "resnet18", "vgg11", "swint", "levit384")
OBJECTIVES = ("balanced", "performance", "resources", "power")
FIELDS = (
    "cores", "icache_kib", "dcache_kib", "scratchpad_kib",
    "l2_enabled", "target_clock_mhz"
)


def read(path):
    return json.loads(Path(path).read_text())


def run_search(board, gguf, output, model="all",
               ram_mib=None, context=None, top=5,
               rtl_guided=False, rtl_tolerance_pct=0.1):

    device = discover_resources(board)
    if device.get("status") != "resources_discovered":
        raise ValueError(f"Board discovery failed: {device}")

    part = device["board"]["fpga_part"]
    limits = device["resources"]

    folder = ROOT / "results/optimizer/measurements"
    refs = [read(p) for p in sorted(folder.glob("*.json"))]
    refs = [
        r for r in refs
        if r.get("fpga_part") == part
        and "configuration" in r
        and "measured_resources" in r
    ]

    if not refs:
        raise ValueError(f"No same-device Vivado reference for {part}")

    baseline = next(
        (r for r in refs
         if r["configuration"].get("scratchpad_kib") == 64),
        refs[0]
    )

    exact = {
        tuple(r["configuration"][f] for f in FIELDS): r
        for r in refs
    }

    clock_limit = min(
        50, int(baseline["configuration"]["target_clock_mhz"])
    )

    # Conservative exploration limits for the measured Nexys Video part.
    # Limits are not a promise of successful place-and-route.
    known_limits = {
        "xc7a200tsbg484-1": {
            "luts": 134600,
            "flip_flops": 269200,
            "bram36_equivalent": 365,
            "dsp": 740,
        }
    }
    if part not in known_limits:
        raise ValueError(
            f"No verified conservative budget for {part}. "
            "Add device capacities before exploratory ranking."
        )

    capacities = known_limits[part]
    fractions = {
        "luts": 0.85,
        "flip_flops": 0.90,
        "bram36_equivalent": 0.90,
        "dsp": 0.90,
    }
    minimum_measured_bram = min(
        float(r["measured_resources"]["bram36_equivalent"])
        for r in refs
    )

    rejected_margin = 0
    rejected_bram_anomaly = 0
    hardware = []

    for cores, cache, scratch, l2, mhz in product(
        (1, 2, 4),
        (8, 16, 32),
        (0, 64, 128, 256),
        (False, True),
        (25, 50)
    ):
        if mhz > clock_limit:
            continue

        if scratch > limits["bram36_sites"] * 4.5:
            continue

        cfg = {
            "cores": cores,
            "icache_kib": cache,
            "dcache_kib": cache,
            "scratchpad_kib": scratch,
            "l2_enabled": l2,
            "target_clock_mhz": mhz,
            "validation": "not_synthesized"
        }

        assessed = estimate(cfg, baseline, limits, part)

        if assessed is None:
            continue

        measured = exact.get(tuple(cfg[f] for f in FIELDS))
        resources = (
            measured["measured_resources"]
            if measured else assessed["resource_estimates"]
        )

        # Reject estimates exceeding conservative board margins.
        if any(
            float(resources[k]) > capacities[k] * fractions[k]
            for k in fractions
        ):
            rejected_margin += 1
            continue

        # Calibration sanity check, not a physical BRAM lower bound.
        # Very low predictions relative to both measured designs
        # are withheld from ranking pending estimator validation.
        if (
            measured is None
            and float(resources["bram36_equivalent"])
            < minimum_measured_bram * 0.50
        ):
            rejected_bram_anomaly += 1
            continue

        hardware.append({
            "configuration": cfg,
            "resource_estimates": (
                measured["measured_resources"]
                if measured else assessed["resource_estimates"]
            ),
            "throughput_proxy": float(
                assessed["throughput_proxy"]
            ),
            "switching_proxy": float(
                assessed["switching_proxy"]
            ),
            "resource_evidence": (
                "measured_vivado"
                if measured or assessed.get("resource_validation")
                == "measured_implementation"
                else "measured_interpolation"
                if assessed.get("resource_validation")
                == "measured_interpolation"
                else "heuristic_estimate"
            )
        })

    if not hardware:
        raise RuntimeError("No candidates passed resource screening")

    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)

    rows = []
    unique = {}
    base_resources = baseline["measured_resources"]

    selected_models = MODELS if model == "all" else (model,)

    for name in selected_models:

        if name == "healthslm":
            if gguf:
                from optimizer.model_analyzer.workload_profile import (
                    build_workload_profile
                )
                profile = build_workload_profile(
                    Path(gguf).expanduser()
                )
                source = "live_gguf_metadata"
            else:
                profile = read(
                    ROOT /
                    "results/optimizer/final_health_slm_balanced.json"
                )["workload_profile"]
                source = "saved_gguf_workload_profile"

            memory = {"status": "not_checked"}

            if ram_mib is not None and context is not None:
                from optimizer.optimization.context_memory import (
                    estimate_runtime_memory
                )
                memory = estimate_runtime_memory(
                    profile,
                    runtime_context_length=context,
                    available_ram_mib=ram_mib,
                    runtime_overhead_mib=0.0
                )

        else:
            profile = read(
                ROOT /
                f"benchmarks/quantized_networks/profiles/{name}.json"
            )
            execution = read(
                ROOT /
                f"results/optimizer/integer_execution_profiles/{name}.json"
            )
            source = "saved_quantized_vision_profiles"
            memory = {"status": "runtime_memory_not_validated"}

        scored = []

        for item in hardware:
            cfg = item["configuration"]
            resources = item["resource_estimates"]

            if name == "healthslm":
                fit = float(model_fit(cfg, profile)["factor"])
                metric = "model_fit_proxy"
            else:
                reference = {
                    "fpga_part": part,
                    "configuration": cfg,
                    "measured_resources": resources
                }
                fit = float(
                    evaluate(
                        profile, execution, reference, "candidate"
                    )["score"]
                )
                metric = "compute_weighted_tile_eligibility"

            cost = sum(
                weight * resources[key] /
                max(1.0, base_resources[key])
                for key, weight in (
                    ("luts", 0.35),
                    ("flip_flops", 0.15),
                    ("bram36_equivalent", 0.40),
                    ("dsp", 0.10)
                )
            )

            scored.append({
                **item,
                "score": item["throughput_proxy"] * fit,
                "workload_proxy": fit,
                "workload_proxy_type": metric,
                "resource_cost": round(cost, 6)
            })

        # Experimental model-specific RTL calibration preview.
        # The original selection scores remain unchanged.
        from optimizer.optimization.rtl_tile_calibration import attach_rtl_preview
        scored = attach_rtl_preview(name, scored)

        frontier = pareto_front(scored)
        max_score = max(x["score"] for x in scored)
        min_cost = min(x["resource_cost"] for x in scored)

        for objective in OBJECTIVES:
            pool = scored if objective == "power" else frontier

            if objective == "performance":
                ranked = sorted(
                    pool,
                    key=lambda x: (-x["score"], x["resource_cost"])
                )

            elif objective == "resources":
                ranked = sorted(
                    pool,
                    key=lambda x: (x["resource_cost"], -x["score"])
                )

            elif objective == "power":
                ranked = sorted(
                    pool,
                    key=lambda x: (
                        x["switching_proxy"], -x["score"]
                    )
                )

            else:
                ranked = sorted(
                    pool,
                    key=lambda x: -(
                        0.7 * x["score"] / max(max_score, 1e-9)
                        + 0.3 * min_cost /
                        max(x["resource_cost"], 1e-9)
                    )
                )

            # Optional RTL-guided selection.
            # Original ranking remains the default.
            if rtl_guided and objective == 'balanced':
                from optimizer.optimization.rtl_guided_selection import (
                    select_rtl_guided,
                )
                ranked, rtl_decision = select_rtl_guided(
                    name, ranked, scored, rtl_tolerance_pct
                )

            best = ranked[0]
            cfg = best["configuration"]

            report = {
                "status": "exploratory_heuristic_search",
                "model": name,
                "board": board,
                "fpga_part": part,
                "objective": objective,
                "model_source": source,
                "memory_assessment": memory,
                "resource_screened_candidates": len(hardware),
                "rejected_by_resource_margin": rejected_margin,
                "rejected_by_bram_sanity_check": rejected_bram_anomaly,
                "conservative_capacity": capacities,
                "maximum_resource_fraction": fractions,
                "recommended_exploration_candidate": best,
                "top_candidates": ranked[:top],
                "limitations": [
                    "No full-model Rocket RTL inference.",
                    "New configurations are not synthesized.",
                    "Performance is a proxy, not measured latency.",
                    "Switching proxy is not measured power.",
                    "Runtime memory and streaming require validation."
                ]
            }

            if rtl_guided and objective == "balanced":
                report["experimental_rtl_guidance"] = rtl_decision

            destination = output / f"{name}_{objective}.json"
            destination.write_text(
                json.dumps(report, indent=2) + "\n"
            )

            rows.append({
                "model": name,
                "objective": objective,
                "cores": cfg["cores"],
                "icache_kib": cfg["icache_kib"],
                "dcache_kib": cfg["dcache_kib"],
                "scratchpad_kib": cfg["scratchpad_kib"],
                "l2": cfg["l2_enabled"],
                "clock_mhz": cfg["target_clock_mhz"],
                "resource_evidence": best["resource_evidence"]
            })

            unique[tuple(cfg[k] for k in FIELDS)] = {
                k: cfg[k] for k in FIELDS
            }

            print(
                f"{name:10} {objective:11} -> "
                f"{cfg['cores']} core, "
                f"cache={cfg['dcache_kib']} KiB, "
                f"scratchpad={cfg['scratchpad_kib']} KiB, "
                f"L2={cfg['l2_enabled']}, "
                f"{cfg['target_clock_mhz']} MHz"
            )

    with (output / "summary.csv").open("w", newline="") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=rows[0].keys(),
            lineterminator="\n"
        )
        writer.writeheader()
        writer.writerows(rows)

    (output / "unique_hardware_configs.json").write_text(
        json.dumps(list(unique.values()), indent=2) + "\n"
    )

    print(
        f"Created {len(rows)} objective reports, "
        f"{len(unique)} distinct proposed configurations"
    )

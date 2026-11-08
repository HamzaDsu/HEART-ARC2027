import argparse
import json
from itertools import product
from pathlib import Path

from optimizer.feasibility import check_feasibility
from optimizer.model_analyzer.workload_profile import build_workload_profile
from optimizer.optimization.context_memory import estimate_runtime_memory


def generate_candidates(
    model_path,
    board_name,
    ram_mib=None,
    scope="full-model",
    *,
    runtime_context_length=None,
    runtime_overhead_mib=0.0,
):

    analysis = check_feasibility(model_path, board_name, ram_mib)

    if "memory_assessment" not in analysis:
        return {
            "status": analysis["status"],
            "candidates": [],
            "details": analysis,
        }

    memory_status = analysis["memory_assessment"]["status"]

    runtime_assessment = None

    if runtime_context_length is not None:
        if ram_mib is None:
            return {
                "status": "runtime_memory_requires_external_ram",
                "candidates": [],
                "note": "External RAM capacity must be supplied.",
            }

        workload = build_workload_profile(model_path)
        runtime_assessment = estimate_runtime_memory(
            workload,
            runtime_context_length=runtime_context_length,
            available_ram_mib=ram_mib,
            runtime_overhead_mib=runtime_overhead_mib,
        )

    estimated_fit = (
        runtime_assessment["fits_estimated_memory"]
        if runtime_assessment is not None
        else memory_status == "weights_may_fit_runtime_unverified"
    )

    if scope == "full-model" and not estimated_fit:
        return {
            "status": "resident_model_memory_blocked",
            "reason": (
                "estimated_runtime_memory_exceeds_ram"
                if runtime_assessment is not None
                else memory_status
            ),
            "runtime_memory_assessment": runtime_assessment,
            "candidates": [],
            "note": "Try a board with sufficient RAM. "
                    "Streaming is not yet evaluated.",
        }

    resources = analysis["fpga"]["resources"]

    # Theoretical BRAM storage capacity in KiB.
    bram_kib = resources["bram36_sites"] * 4.5

    candidates = []

    for cores, cache, scratchpad, l2, clock in product(
        [1, 2, 4],
        [8, 16, 32],
        [0, 64, 128, 256],
        [False, True],
        [25, 50, 75],
    ):
        # Early capacity screen only, not a resource-fit proof.
        if scratchpad > bram_kib:
            continue

        candidates.append({
            "cores": cores,
            "icache_kib": cache,
            "dcache_kib": cache,
            "scratchpad_kib": scratchpad,
            "l2_enabled": l2,
            "target_clock_mhz": clock,
            "validation": "not_synthesized",
        })

    return {
        "status": "candidate_generation_complete",
        "scope": scope,
        "model": analysis["model"]["name"],
        "board": analysis["fpga"]["board"],
        "memory_status": memory_status,
        "runtime_memory_assessment": runtime_assessment,
        "candidate_count": len(candidates),
        "candidates": candidates,
        "note": "Candidate search space only. FPGA fit, "
                "timing, power, and performance are unverified.",
    }


def main():
    parser = argparse.ArgumentParser(
        description="Generate candidate Rocket FPGA configurations"
    )
    parser.add_argument("--model", required=True)
    parser.add_argument("--board", required=True)
    parser.add_argument("--external-ram-mib", type=int)
    parser.add_argument(
        "--scope",
        choices=["full-model", "kernel"],
        default="full-model",
    )
    parser.add_argument("--output")

    args = parser.parse_args()

    result = generate_candidates(
        args.model,
        args.board,
        args.external_ram_mib,
        args.scope,
    )

    if args.output:
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result, indent=2) + "\n")

    summary = {
        key: value for key, value in result.items()
        if key != "candidates"
    }
    summary["example_candidates"] = result["candidates"][:5]
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()

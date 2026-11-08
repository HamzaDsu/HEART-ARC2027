import argparse
import json
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(
        description="Model-aware FPGA configuration optimizer"
    )

    parser.add_argument("--model", required=True)
    parser.add_argument("--board", required=True)
    parser.add_argument(
        "--objective",
        choices=["performance", "resources", "balanced", "power"],
        default="balanced",
    )
    parser.add_argument(
        "--scope",
        choices=["kernel", "full-model"],
        default="kernel",
    )
    parser.add_argument("--external-ram-mib", type=int)
    parser.add_argument("--past-tokens", type=int)
    parser.add_argument("--assumed-bandwidth-mib-s", type=float)
    parser.add_argument("--assumed-compute-gops-s", type=float)
    parser.add_argument("--candidate-rate-profile")
    parser.add_argument("--runtime-context-length", type=int)
    parser.add_argument("--runtime-overhead-mib", type=float, default=0.0)
    parser.add_argument("--top", type=int, default=3)
    parser.add_argument(
        "--benchmark-key",
        choices=["normal_0k", "sbus_64k", "sbus_128k"],
    )
    parser.add_argument("--benchmark-simulator")
    parser.add_argument("--benchmark-binary")
    parser.add_argument("--output")

    args = parser.parse_args()

    if args.runtime_context_length is not None:
        if args.runtime_context_length <= 0:
            parser.error("--runtime-context-length must be positive")
        if args.external_ram_mib is None:
            parser.error("--external-ram-mib is required for runtime memory analysis")

    if args.runtime_overhead_mib < 0:
        parser.error("--runtime-overhead-mib cannot be negative")

    if args.runtime_overhead_mib and args.runtime_context_length is None:
        parser.error("--runtime-overhead-mib requires --runtime-context-length")

    if not Path(args.model).expanduser().is_file():
        parser.error("Model file not found")

    if args.top < 1:
        parser.error("--top must be positive")

    command = [
        sys.executable,
        "-m",
        "optimizer.optimization.recommender",
        "--model", str(Path(args.model).expanduser()),
        "--board", args.board,
        "--objective", args.objective,
        "--scope", args.scope,
        "--top", str(args.top),
    ]

    for option, value in [
        ("--external-ram-mib", args.external_ram_mib),
        ("--past-tokens", args.past_tokens),
        ("--assumed-bandwidth-mib-s",
         args.assumed_bandwidth_mib_s),
    ]:
        if value is not None:
            command.extend([option, str(value)])

    if args.runtime_context_length is not None:
        command.extend([
            "--runtime-context-length",
            str(args.runtime_context_length),
            "--runtime-overhead-mib",
            str(args.runtime_overhead_mib),
        ])

    process = subprocess.run(
        command,
        capture_output=True,
        text=True,
        check=False,
    )

    if process.returncode:
        print("Optimizer execution failed.", file=sys.stderr)
        print(process.stderr or process.stdout, file=sys.stderr)
        return 1

    try:
        data = json.loads(process.stdout)
    except json.JSONDecodeError:
        print("Invalid optimizer JSON output.", file=sys.stderr)
        print(process.stdout, file=sys.stderr)
        return 1

    if args.runtime_context_length is not None:
        from optimizer.optimization.context_memory import estimate_runtime_memory

        workload = data.get("workload_profile")

        if isinstance(workload, dict):
            data["runtime_memory_analysis"] = estimate_runtime_memory(
                workload,
                runtime_context_length=args.runtime_context_length,
                available_ram_mib=args.external_ram_mib,
                runtime_overhead_mib=args.runtime_overhead_mib,
            )
        else:
            data["runtime_memory_analysis"] = {
                "status": "unavailable",
                "reason": "workload_profile_not_returned",
            }

    best = data.get("recommended_exploration_candidate")

    if isinstance(best, dict):
        best["performance_assessment"] = {
            "status": "heuristic_estimate",
            "throughput_proxy": best.get(
                "model_adjusted_throughput_proxy"
            ),
            "measured_model_cycles": None,
            "full_model_inference_measured": False,
            "note": (
                "Candidate performance remains heuristic. "
                "Kernel benchmarks are reported separately."
            ),
        }

    if args.benchmark_key is not None:
        import hashlib

        from optimizer.optimization.evidence_matcher import (
            match_q3k_artifacts,
        )

        if not args.benchmark_simulator or not args.benchmark_binary:
            parser.error(
                "--benchmark-key requires both "
                "--benchmark-simulator and --benchmark-binary"
            )

        base = (
            Path(__file__).resolve().parents[1]
            / "results/optimizer/measurements"
        )

        manifest_path = base / "verilator_q3k_artifact_manifest.json"
        evidence_path = base / "verilator_q3k_memory_evidence.json"

        manifest = json.loads(manifest_path.read_text())
        config = manifest["configurations"][args.benchmark_key]

        simulator = Path(args.benchmark_simulator).expanduser()
        binary = Path(args.benchmark_binary).expanduser()

        if not simulator.is_file() or not binary.is_file():
            parser.error("Benchmark simulator or binary not found")

        if (
            simulator.name
            != "simulator-chipyard.harness-" + config["simulator_config"]
            or binary.name != config["riscv_binary"]
        ):
            parser.error("Benchmark artifact filenames do not match")

        def digest(file):
            return hashlib.sha256(file.read_bytes()).hexdigest()

        matched = match_q3k_artifacts(
            evidence_path,
            manifest_path,
            key=args.benchmark_key,
            simulator_config=config["simulator_config"],
            simulator_sha256=digest(simulator),
            binary_sha256=digest(binary),
            scratchpad_kib=config["scratchpad_kib"],
        )

        if matched is None:
            parser.error("Benchmark artifact identity mismatch")

        data["benchmark_reference"] = {
            **matched,
            "scope": "Q3_K kernel in Verilator only",
            "relationship_to_recommendation": "separate_reference",
            "full_model_inference_measured": False,
        }

    elif args.benchmark_simulator or args.benchmark_binary:
        parser.error(
            "Benchmark artifact paths require --benchmark-key"
        )

    # Optional model-level compute/memory performance envelope.
    # These rates are supplied assumptions, not FPGA measurements.
    if (
        args.assumed_bandwidth_mib_s is not None
        or args.assumed_compute_gops_s is not None
    ):
        import math

        from optimizer.optimization.workload_performance import (
            estimate_decode_performance,
        )

        for name, value in (
            ("bandwidth", args.assumed_bandwidth_mib_s),
            ("compute", args.assumed_compute_gops_s),
        ):
            if value is not None and (
                not math.isfinite(value) or value <= 0
            ):
                parser.error(
                    f"Assumed {name} rate must be finite and positive"
                )

        workload = data.get("workload_profile")

        if not isinstance(workload, dict):
            data["decode_performance_envelope"] = {
                "status": "unavailable",
                "reason": "workload_profile_not_returned",
            }
        else:
            context = workload.get("context_length")

            if (
                type(context) is not int
                or context <= 0
            ):
                data["decode_performance_envelope"] = {
                    "status": "unavailable",
                    "reason": "invalid_model_context_length",
                }
            else:
                past_tokens = (
                    args.past_tokens
                    if args.past_tokens is not None
                    else min(1024, context - 1)
                )

                if not 0 <= past_tokens < context:
                    parser.error(
                        "--past-tokens outside model context"
                    )

                performance = estimate_decode_performance(
                    workload,
                    past_tokens,
                    bandwidth_mib_s=args.assumed_bandwidth_mib_s,
                    compute_gops_s=args.assumed_compute_gops_s,
                )

                data["decode_performance_envelope"] = {
                    **performance,
                    "past_tokens": past_tokens,
                    "assumed_bandwidth_mib_s":
                        args.assumed_bandwidth_mib_s,
                    "assumed_compute_gops_s":
                        args.assumed_compute_gops_s,
                    "scope": "model_level_idealized_decode",
                    "candidate_specific": False,
                    "eligible_for_automatic_ranking": False,
                }

    # Optional candidate-specific performance assessment.
    # Rate profiles contain user assumptions, not measured FPGA rates.
    # This assessment never modifies recommendation ranking.
    if args.candidate_rate_profile is not None:
        from optimizer.optimization.candidate_performance import (
            assess_candidate_performance,
        )

        try:
            rate_profile = json.loads(
                Path(args.candidate_rate_profile).read_text(
                    encoding="utf-8"
                )
            )
        except (OSError, json.JSONDecodeError) as error:
            parser.error(
                f"Cannot read candidate rate profile: {error}"
            )

        if not isinstance(rate_profile, dict):
            parser.error(
                "Candidate rate profile must be a JSON object"
            )

        best = data.get("recommended_exploration_candidate")
        workload = data.get("workload_profile")
        reference = data.get("reference_selection")

        fpga_part = (
            reference.get("target_part")
            if isinstance(reference, dict)
            else None
        )

        configuration = (
            best.get("configuration")
            if isinstance(best, dict)
            else None
        )

        context = (
            workload.get("context_length")
            if isinstance(workload, dict)
            else None
        )

        if (
            not isinstance(configuration, dict)
            or not isinstance(workload, dict)
            or not isinstance(fpga_part, str)
            or type(context) is not int
            or context <= 0
        ):
            data["candidate_performance_assessment"] = {
                "status": "unavailable",
                "reason": "candidate_workload_or_fpga_part_missing",
                "eligible_for_automatic_ranking": False,
            }
        else:
            past_tokens = (
                args.past_tokens
                if args.past_tokens is not None
                else min(1024, context - 1)
            )

            if not 0 <= past_tokens < context:
                parser.error(
                    "--past-tokens outside model context"
                )

            try:
                assessment = assess_candidate_performance(
                    configuration,
                    workload,
                    past_tokens,
                    fpga_part=fpga_part,
                    rate_profile=rate_profile,
                )
            except (ValueError, KeyError) as error:
                parser.error(
                    f"Invalid candidate performance profile: {error}"
                )

            data["candidate_performance_assessment"] = {
                **assessment,
                "past_tokens": past_tokens,
                "scope": "candidate_specific_idealized_decode",
                "eligible_for_automatic_ranking": False,
            }

    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(
            json.dumps(data, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"Full report: {output}")

    print("\n=== SLM FPGA OPTIMIZER ===")
    print("Model:", data.get("model"))
    print("Board:", data.get("board"))
    print("Objective:", args.objective)
    print("Status:", data.get("status"))
    print("Confidence:", data.get("confidence", "unknown"))

    if data.get("status") != "heuristic_ranking_complete":
        print("\nNo ranked recommendation available.")
        print(json.dumps(data, indent=2))
        return 2

    best = data.get("recommended_exploration_candidate")

    if not best:
        print("No candidate passed screening.")
        return 2

    cfg = best["configuration"]
    resources = best.get("resource_estimates", {})

    print("\nRECOMMENDED ROCKET CONFIGURATION")
    print("Cores:", cfg["cores"])
    print("I-cache:", cfg["icache_kib"], "KiB")
    print("D-cache:", cfg["dcache_kib"], "KiB")
    print("Scratchpad:", cfg["scratchpad_kib"], "KiB")
    print("L2 enabled:", cfg["l2_enabled"])
    print("Target clock:", cfg["target_clock_mhz"], "MHz")

    print("\nESTIMATED HARDWARE RESOURCES")
    for key in ["luts", "flip_flops", "bram36_equivalent", "dsp"]:
        print(f"{key}: {resources.get(key, 'unknown')}")

    print("\nOPTIMIZATION")
    print(
        "Throughput proxy:",
        best.get("model_adjusted_throughput_proxy"),
    )
    print(
        "Pareto alternatives:",
        data.get("pareto_candidate_count", 0),
    )

    cost = best.get("hardware_cost", {})
    resident = cost.get("resident_model_memory", {})

    if resident:
        print("\nFULL-CONTEXT MEMORY REFERENCE (ESTIMATED)")
        print("Required MiB:", resident.get("required_mib"))
        print("Available MiB:", resident.get("available_mib"))
        print("Fits:", resident.get("fits"))

    runtime = data.get("runtime_memory_analysis")

    if runtime:
        print("\nRUNTIME MEMORY ANALYSIS (ESTIMATED)")
        print("Context length:", runtime.get("runtime_context_length"))
        print("Weights MiB:", runtime.get("weights_mib"))
        print("KV cache MiB:", runtime.get("runtime_f16_kv_cache_mib"))
        print("Runtime overhead MiB:", runtime.get("runtime_overhead_mib"))
        print("Required MiB:", runtime.get("estimated_required_mib"))
        print("Available MiB:", runtime.get("available_ram_mib"))
        print("Headroom MiB:", runtime.get("estimated_headroom_mib"))
        print("Fits estimated memory:", runtime.get("fits_estimated_memory"))

    print("\nVALIDATION")
    print("Status:", data.get("recommendation_status"))
    print("These recommendations are heuristic estimates.")
    print("Candidate timing, power and performance are not verified.")

    if args.objective == "power":
        print("Power objective uses a switching proxy, not watts.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

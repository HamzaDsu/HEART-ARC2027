import argparse
import json

from optimizer.model_analyzer.gguf_analyzer import analyze_model
from optimizer.model_analyzer.workload_profile import build_workload_profile
from optimizer.board_analyzer.vivado_resources import discover_resources

MIB = 1024 * 1024


def check_feasibility(model_path, board_name, external_ram_mib=None):
    model = analyze_model(model_path)
    workload = build_workload_profile(model_path)
    device = discover_resources(board_name)

    if device["status"] != "resources_discovered":
        return {
            "status": device["status"],
            "details": device
        }

    resources = device["resources"]
    weights_bytes = int(model["tensor_storage_bytes"])
    weights_mib = weights_bytes / MIB
    kv_mib = workload["illustrative_f16_kv_cache_mib"]
    total_mib = weights_mib + kv_mib if kv_mib is not None else None

    # Raw theoretical FPGA BRAM capacity, not usable scratchpad size
    bram_mib = (
        resources["bram36_sites"] * 36 * 1024 / 8 / MIB
    )

    if external_ram_mib is None:
        memory_status = "external_ram_unknown"
    elif external_ram_mib * MIB < weights_bytes:
        memory_status = "insufficient_for_resident_weights"
    elif total_mib is not None and external_ram_mib < total_mib:
        memory_status = "fp16_full_context_kv_does_not_fit"
    else:
        memory_status = "weights_may_fit_runtime_unverified"

    return {
        "model": {
            "name": model["model_name"],
            "architecture": model["architecture"],
            "parameters": model["parameter_count"],
            "layers": model["layers"],
            "quantization": model["quantization_types"],
            "weights_mib": round(weights_mib, 2)
        },
        "fpga": {
            "board": device["board"]["board_name"],
            "part": device["board"]["fpga_part"],
            "default_config": device["board"]["default_config"],
            "resources": resources,
            "raw_bram_capacity_mib": round(bram_mib, 3)
        },
        "memory_assessment": {
            "external_ram_mib": external_ram_mib,
            "illustrative_f16_kv_cache_mib": kv_mib,
            "weights_plus_f16_kv_mib": round(total_mib, 2) if total_mib is not None else None,
            "status": memory_status,
            "note": (
                "Checks weights plus illustrative full-context FP16 KV memory. "
                "Runtime overhead and activation memory "
                "and streaming strategies are not evaluated."
            )
        }
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--board", required=True)
    parser.add_argument("--external-ram-mib", type=int)
    parser.add_argument("--output")
    args = parser.parse_args()

    if args.external_ram_mib is not None and args.external_ram_mib <= 0:
        parser.error("--external-ram-mib must be positive")

    result = check_feasibility(
        args.model,
        args.board,
        args.external_ram_mib
    )

    text = json.dumps(result, indent=2)
    print(text)

    if args.output:
        from pathlib import Path
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

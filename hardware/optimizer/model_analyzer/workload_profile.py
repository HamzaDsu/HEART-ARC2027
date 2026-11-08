import argparse
import json
from pathlib import Path

from gguf import GGUFReader

from optimizer.model_analyzer.gguf_analyzer import analyze_model

MIB = 1024 * 1024


def read_int(reader, key):
    field = reader.get_field(key)

    if field is None:
        return None

    value = field.contents()

    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def build_workload_profile(model_path):
    model = analyze_model(model_path)
    reader = GGUFReader(str(Path(model_path).expanduser()))

    arch = model["architecture"]
    params = model["parameter_count"]
    storage = model["tensor_storage_bytes"]

    heads = read_int(
        reader, f"{arch}.attention.head_count"
    )
    kv_heads = read_int(
        reader, f"{arch}.attention.head_count_kv"
    )
    key_dim = read_int(
        reader, f"{arch}.attention.key_length"
    )
    value_dim = read_int(
        reader, f"{arch}.attention.value_length"
    )

    quant_params = {
        name: count
        for name, count in model["parameters_by_type"].items()
        if name.startswith("Q")
    }

    dominant_quant = (
        max(quant_params, key=quant_params.get)
        if quant_params else None
    )

    hidden = model["embedding_size"]
    layers = model["layers"]
    context = model["context_length"]

    # Derive standard attention head dimensions if missing.
    dimensions_inferred = False

    if heads and hidden and hidden % heads == 0:
        if key_dim is None:
            key_dim = hidden // heads
            dimensions_inferred = True
        if value_dim is None:
            value_dim = hidden // heads
            dimensions_inferred = True

    kv_mib = None

    if all(
        isinstance(v, int) and v > 0
        for v in [layers, context, kv_heads, key_dim, value_dim]
    ):
        kv_bytes = (
            layers * context * kv_heads
            * (key_dim + value_dim) * 2
        )
        kv_mib = round(kv_bytes / MIB, 2)

    return {
        "model_name": model["model_name"],
        "architecture": arch,
        "parameters": params,
        "layers": layers,
        "embedding_size": hidden,
        "context_length": context,
        "attention_heads": heads,
        "kv_heads": kv_heads,
        "dominant_quantization": dominant_quant,
        "quantized_parameter_fraction": (
            round(sum(quant_params.values()) / params, 4)
            if params else None
        ),
        "average_stored_bits_per_parameter": (
            round(8 * storage / params, 3)
            if params else None
        ),
        "weights_mib": round(storage / MIB, 2),
        "illustrative_f16_kv_cache_mib": kv_mib,
        "kv_head_dimensions_inferred": dimensions_inferred,
        "analysis_status": "metadata_based_estimation",
        "limitations": [
            "KV cache assumes FP16 storage and full context.",
            "Runtime buffers and temporary activations excluded.",
            "No measured inference speed or memory bandwidth.",
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()

    profile = build_workload_profile(args.model)
    text = json.dumps(profile, indent=2)
    print(text)

    if args.output:
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

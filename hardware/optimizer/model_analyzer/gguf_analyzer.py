import argparse
import json
from collections import Counter
from pathlib import Path

from gguf import GGUFReader


def get_metadata(reader, key):
    field = reader.get_field(key)
    if field is None:
        return None
    return field.contents()


def analyze_model(model_path):
    path = Path(model_path).expanduser().resolve()

    if not path.is_file():
        raise FileNotFoundError(f"Model not found: {path}")

    if path.suffix.lower() != ".gguf":
        raise ValueError("This analyzer currently supports GGUF models.")

    reader = GGUFReader(str(path))

    architecture = get_metadata(reader, "general.architecture")

    if not architecture:
        raise ValueError("GGUF architecture metadata is missing.")

    quantization = Counter()
    parameters_by_type = Counter()

    total_parameters = 0
    total_tensor_bytes = 0

    for tensor in reader.tensors:
        dtype = tensor.tensor_type.name

        quantization[dtype] += 1
        parameters_by_type[dtype] += int(tensor.n_elements)

        total_parameters += int(tensor.n_elements)
        total_tensor_bytes += int(tensor.n_bytes)

    profile = {
        "model_name": get_metadata(reader, "general.name"),
        "model_path": str(path),
        "format": "GGUF",
        "architecture": architecture,
        "file_size_bytes": path.stat().st_size,
        "tensor_count": len(reader.tensors),
        "parameter_count": total_parameters,
        "tensor_storage_bytes": total_tensor_bytes,
        "quantization_types": dict(quantization),
        "parameters_by_type": dict(parameters_by_type),
        "layers": get_metadata(
            reader, f"{architecture}.block_count"
        ),
        "embedding_size": get_metadata(
            reader, f"{architecture}.embedding_length"
        ),
        "context_length": get_metadata(
            reader, f"{architecture}.context_length"
        ),
        "split_count": get_metadata(
            reader, "split.count"
        ),
    }

    return profile


def main():
    parser = argparse.ArgumentParser(
        description="Analyze a GGUF model for FPGA optimization"
    )

    parser.add_argument("--model", required=True)
    parser.add_argument("--output", default=None)

    args = parser.parse_args()

    profile = analyze_model(args.model)

    result = json.dumps(profile, indent=2)

    print(result)

    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(
            parents=True, exist_ok=True
        )
        output_path.write_text(
            result + "\n", encoding="utf-8"
        )


if __name__ == "__main__":
    main()

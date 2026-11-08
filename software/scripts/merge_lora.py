#!/usr/bin/env python
"""Merge a LoRA/QLoRA adapter into the base model before pruning or export.

Why this exists:
- QLoRA/LoRA training saves small adapter weights.
- Pruning/quantization/deployment should be run on a normal merged Hugging Face model.

Example:
python scripts/merge_lora.py \
  --base-model TinyLlama/TinyLlama-1.1B-Chat-v1.0 \
  --adapter experiments/tinyllama_lora_health_baseline/checkpoints \
  --output experiments/tinyllama_lora_health_baseline/merged_model
"""

from __future__ import annotations

import argparse
from pathlib import Path

from heart_paths import require_model, reserve_new_output

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

DTYPES = {
    "float16": torch.float16,
    "bfloat16": torch.bfloat16,
    "float32": torch.float32,
}


def resolve_adapter_path(adapter: str) -> Path:
    """Accept either the adapter folder or a parent folder containing checkpoints."""
    path = Path(adapter)
    if (path / "adapter_config.json").exists():
        return path
    checkpoints = sorted(
        [p for p in path.glob("checkpoint-*") if (p / "adapter_config.json").exists()],
        key=lambda p: int(p.name.split("-")[-1]) if p.name.split("-")[-1].isdigit() else -1,
    )
    if checkpoints:
        return checkpoints[-1]
    raise FileNotFoundError(
        f"Could not find adapter_config.json in {path} or in checkpoint-* subfolders."
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-model", required=True, help="HF model id or local path, e.g. TinyLlama/TinyLlama-1.1B-Chat-v1.0")
    parser.add_argument("--adapter", required=True, help="LoRA adapter folder or parent checkpoint folder")
    parser.add_argument("--output", required=True, help="Output folder for merged HF model")
    parser.add_argument("--dtype", default="float16", choices=list(DTYPES))
    parser.add_argument("--device-map", default="auto", help="Use 'auto' on GPU server; use 'cpu' for debugging only")
    args = parser.parse_args()

    adapter_path = resolve_adapter_path(args.adapter)
    require_model(args.base_model)
    output = reserve_new_output(args.output)
    dtype = DTYPES[args.dtype]

    print(f"Loading tokenizer from: {args.base_model}")
    tokenizer = AutoTokenizer.from_pretrained(args.base_model, use_fast=True, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    print(f"Loading base model from: {args.base_model}")
    base = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        torch_dtype=dtype,
        device_map=args.device_map,
        trust_remote_code=True,
    )

    print(f"Loading adapter from: {adapter_path}")
    model = PeftModel.from_pretrained(base, str(adapter_path))

    print("Merging adapter into base weights...")
    model = model.merge_and_unload()
    model.config.use_cache = True

    print(f"Saving merged model to: {output}")
    model.save_pretrained(output, safe_serialization=True)
    tokenizer.save_pretrained(output)
    print("Done.")


if __name__ == "__main__":
    main()
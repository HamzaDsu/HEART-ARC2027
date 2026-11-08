#!/usr/bin/env python
"""Memory-safe Mix-and-Match style pruning for TinyLlama/LLaMA-style SLMs.

This version avoids storing full per-weight gradient tensors for all layers.
Instead, it computes a scalar sensitivity score per prunable matrix using:

    sensitivity(layer) = average absolute gradient

Then it applies architecture-aware magnitude pruning with sensitivity-adjusted
layer ranges.

Why this is better on Willi:
- TinyLlama has almost 1B prunable matrix weights.
- Storing full float32 gradient-score tensors for every matrix can silently fail.
- This version is stable and still follows the thesis idea:
  sensitivity analysis + multi-strategy pruning + trade-off variants.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

from heart_paths import require_file, require_model, reserve_new_output
from statistics import median
from typing import Dict, Iterable, Tuple

import torch
from datasets import load_dataset
from tqdm import tqdm
from transformers import AutoModelForCausalLM, AutoTokenizer

TARGET_SUFFIXES = (
    "q_proj.weight",
    "k_proj.weight",
    "v_proj.weight",
    "o_proj.weight",
    "gate_proj.weight",
    "up_proj.weight",
    "down_proj.weight",
)

PROTECTED_PATTERNS = (
    "embed_tokens",
    "lm_head",
    "norm",
    "rotary_emb",
)

STRATEGIES = [
    "min_conservative",
    "lower_30th_percentile",
    "balanced",
    "upper_70th_percentile",
    "upper_90th_percentile",
    "max_aggressive",
]

DTYPES = {
    "float16": torch.float16,
    "bfloat16": torch.bfloat16,
    "float32": torch.float32,
}


def log(msg: str) -> None:
    print(msg, flush=True)


def parse_layer_index(name: str) -> int:
    parts = name.split(".")
    for i, part in enumerate(parts):
        if part == "layers" and i + 1 < len(parts):
            try:
                return int(parts[i + 1])
            except ValueError:
                return -1
    return -1


def get_projection_type(name: str) -> str:
    for suffix in TARGET_SUFFIXES:
        if name.endswith(suffix):
            return suffix.replace(".weight", "")
    return "other"


def is_prunable(name: str, param: torch.nn.Parameter) -> bool:
    if param.ndim != 2:
        return False
    if any(pattern in name for pattern in PROTECTED_PATTERNS):
        return False
    return name.endswith(TARGET_SUFFIXES)


def collect_prunable_parameters(model) -> dict[str, torch.nn.Parameter]:
    return {
        name: param
        for name, param in model.named_parameters()
        if is_prunable(name, param)
    }


def base_pruning_range_for_param(name: str, num_layers: int) -> Tuple[float, float]:
    """Architecture-aware base pruning range before sensitivity adjustment."""
    layer_idx = parse_layer_index(name)
    projection = get_projection_type(name)

    if layer_idx < 0 or num_layers <= 1:
        position_factor = 0.80
    else:
        rel_pos = layer_idx / float(num_layers - 1)
        if rel_pos < 0.20 or rel_pos > 0.85:
            position_factor = 0.65
        elif rel_pos < 0.50:
            position_factor = 0.90
        else:
            position_factor = 1.00

    if projection in {"q_proj", "o_proj"}:
        low, high = 5.0, 30.0
    elif projection in {"k_proj", "v_proj"}:
        low, high = 10.0, 40.0
    elif projection in {"gate_proj", "up_proj"}:
        low, high = 20.0, 65.0
    elif projection == "down_proj":
        low, high = 15.0, 55.0
    else:
        low, high = 0.0, 10.0

    return low * position_factor, high * position_factor


def sensitivity_adjusted_range(
    name: str,
    num_layers: int,
    sensitivity: float,
    median_sensitivity: float,
) -> Tuple[float, float]:
    """Reduce pruning for high-sensitivity layers and allow more for low-sensitivity layers."""
    low, high = base_pruning_range_for_param(name, num_layers)

    if median_sensitivity <= 0:
        return low, high

    ratio = sensitivity / median_sensitivity

    if ratio >= 1.50:
        factor = 0.65
    elif ratio >= 1.15:
        factor = 0.80
    elif ratio <= 0.65:
        factor = 1.15
    elif ratio <= 0.85:
        factor = 1.05
    else:
        factor = 1.00

    adjusted_low = low * factor
    adjusted_high = high * factor

    # Keep pruning ranges safe for health-recommendation SLM experiments.
    adjusted_low = max(0.0, min(adjusted_low, 70.0))
    adjusted_high = max(adjusted_low, min(adjusted_high, 75.0))

    return adjusted_low, adjusted_high


def strategy_percentage(strategy: str, low: float, high: float) -> float:
    mapping = {
        "min_conservative": 0.00,
        "lower_30th_percentile": 0.30,
        "balanced": 0.50,
        "upper_70th_percentile": 0.70,
        "upper_90th_percentile": 0.90,
        "max_aggressive": 1.00,
    }

    if strategy not in mapping:
        raise ValueError(f"Unknown strategy '{strategy}'. Valid values: {sorted(mapping)}")

    return low + (high - low) * mapping[strategy]


def iter_token_batches(
    tokenizer,
    dataset,
    text_field: str,
    max_length: int,
    batch_size: int,
    assistant_marker: str,
) -> Iterable[dict]:
    """
    Build calibration batches where loss is applied only to the
    assistant response.

    Prompt/user/context tokens receive label -100.
    Assistant response tokens, including EOS, are supervised.
    """

    samples = []

    def make_sample(text: str) -> dict:
        if assistant_marker not in text:
            raise ValueError(
                f"Assistant marker {assistant_marker!r} "
                "not found in calibration example."
            )

        prompt_text, response_text = text.split(
            assistant_marker,
            1,
        )

        prompt_text = prompt_text + assistant_marker
        response_text = response_text.rstrip()

        if tokenizer.eos_token:
            if not response_text.endswith(tokenizer.eos_token):
                response_text += tokenizer.eos_token

        prompt_ids = tokenizer(
            prompt_text,
            add_special_tokens=False,
        )["input_ids"]

        response_ids = tokenizer(
            response_text,
            add_special_tokens=False,
        )["input_ids"]

        # Preserve the entire assistant response whenever possible.
        if len(response_ids) >= max_length:
            response_ids = response_ids[:max_length]
            prompt_ids = []
        else:
            available_prompt = max_length - len(response_ids)
            prompt_ids = prompt_ids[-available_prompt:]

        input_ids = prompt_ids + response_ids

        labels = (
            [-100] * len(prompt_ids)
            + response_ids.copy()
        )

        return {
            "input_ids": input_ids,
            "labels": labels,
        }

    def collate(items: list[dict]) -> dict:
        max_len = max(len(x["input_ids"]) for x in items)
        pad_id = tokenizer.pad_token_id

        input_ids = []
        attention_mask = []
        labels = []

        for item in items:
            length = len(item["input_ids"])
            pad_count = max_len - length

            input_ids.append(
                item["input_ids"]
                + [pad_id] * pad_count
            )

            attention_mask.append(
                [1] * length
                + [0] * pad_count
            )

            labels.append(
                item["labels"]
                + [-100] * pad_count
            )

        return {
            "input_ids": torch.tensor(
                input_ids,
                dtype=torch.long,
            ),
            "attention_mask": torch.tensor(
                attention_mask,
                dtype=torch.long,
            ),
            "labels": torch.tensor(
                labels,
                dtype=torch.long,
            ),
        }

    for row in dataset:
        text = row.get(text_field) or row.get("text") or row.get("prompt") or ""
        text = str(text).strip()

        if not text:
            continue

        samples.append(make_sample(text))

        if len(samples) >= batch_size:
            yield collate(samples)
            samples = []

    if samples:
        yield collate(samples)


def compute_layer_sensitivity(
    model,
    tokenizer,
    data_file: str,
    text_field: str,
    max_length: int,
    batch_size: int,
    num_batches: int,
    device: str,
    assistant_marker: str,
) -> Dict[str, float]:
    """Compute scalar gradient sensitivity per prunable matrix."""
    model.train()
    model.config.use_cache = False

    for param in model.parameters():
        param.requires_grad_(False)

    prunable = collect_prunable_parameters(model)

    if not prunable:
        raise RuntimeError(
            "No prunable LLaMA-style projection matrices were found. "
            "Expected q_proj/k_proj/v_proj/o_proj/gate_proj/up_proj/down_proj weights."
        )

    log(f"Prunable tensors: {len(prunable)}")
    log(f"Total prunable params: {sum(p.numel() for p in prunable.values()):,}")

    for param in prunable.values():
        param.requires_grad_(True)

    dataset = load_dataset("json", data_files=data_file, split="train")

    scores = {name: 0.0 for name in prunable}
    seen = 0

    for batch in tqdm(
        iter_token_batches(
            tokenizer,
            dataset,
            text_field,
            max_length,
            batch_size,
            assistant_marker,
        ),
        total=num_batches,
        desc="Sensitivity batches",
    ):
        if seen >= num_batches:
            break

        batch = {
            key: value.to(device)
            for key, value in batch.items()
        }

        if seen == 0:
            supervised = int(
                (batch["labels"] != -100).sum().item()
            )
            total = int(
                batch["attention_mask"].sum().item()
            )

            log(
                "Assistant-only calibration sanity: "
                f"supervised_tokens={supervised}, "
                f"total_tokens={total}"
            )

        model.zero_grad(set_to_none=True)

        loss = model(**batch).loss
        loss.backward()

        for name, param in prunable.items():
            if param.grad is not None:
                scores[name] += float(param.grad.detach().abs().mean().cpu())

        seen += 1

    if seen == 0:
        raise RuntimeError("No calibration batches were read. Check --data-file and --text-field.")

    for name in scores:
        scores[name] /= float(seen)

    log(f"Calibration batches used: {seen}")

    return scores


def apply_unstructured_pruning(
    model,
    sensitivity_scores: Dict[str, float],
    strategy: str,
    num_layers: int,
) -> dict:
    """Apply one pruning strategy and return metrics."""
    prunable = collect_prunable_parameters(model)
    sensitivities = list(sensitivity_scores.values())
    median_sensitivity = median(sensitivities) if sensitivities else 0.0

    metrics = {
        "strategy": strategy,
        "total_prunable_params": 0,
        "zero_prunable_params": 0,
        "overall_prunable_sparsity": 0.0,
        "median_sensitivity": median_sensitivity,
        "layers": {},
    }

    for name, param in tqdm(prunable.items(), desc=f"Apply {strategy}"):
        sensitivity = float(sensitivity_scores.get(name, median_sensitivity))
        low, high = sensitivity_adjusted_range(name, num_layers, sensitivity, median_sensitivity)
        prune_pct = strategy_percentage(strategy, low, high)

        total = param.numel()
        to_prune = int(total * prune_pct / 100.0)

        if to_prune > 0:
            with torch.no_grad():
                flat_abs = param.detach().abs().flatten()

                # kthvalue uses 1-based k. Clamp safely.
                k = max(1, min(to_prune, flat_abs.numel()))
                threshold = torch.kthvalue(flat_abs, k).values

                keep_mask = param.detach().abs() > threshold
                param.mul_(keep_mask.to(dtype=param.dtype))

        zeros = int((param.detach() == 0).sum().item())
        sparsity_after = 100.0 * zeros / max(1, total)

        metrics["layers"][name] = {
            "projection_type": get_projection_type(name),
            "layer_index": parse_layer_index(name),
            "sensitivity": sensitivity,
            "range_min_pct": round(low, 4),
            "range_max_pct": round(high, 4),
            "requested_prune_pct": round(prune_pct, 4),
            "params": total,
            "zeros_after": zeros,
            "sparsity_after_pct": round(sparsity_after, 4),
        }

        metrics["total_prunable_params"] += total
        metrics["zero_prunable_params"] += zeros

    metrics["overall_prunable_sparsity"] = round(
        100.0 * metrics["zero_prunable_params"] / max(1, metrics["total_prunable_params"]),
        4,
    )

    return metrics


def write_summary_csv(path: Path, all_metrics: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=[
                "strategy",
                "total_prunable_params",
                "zero_prunable_params",
                "overall_prunable_sparsity",
            ],
        )
        writer.writeheader()

        for row in all_metrics:
            writer.writerow({key: row.get(key) for key in writer.fieldnames})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="Merged Hugging Face model path or model id")
    parser.add_argument("--data-file", required=True, help="Calibration JSONL")
    parser.add_argument("--output-dir", default="experiments/mix_match_pruned")
    parser.add_argument("--text-field", default="text")
    parser.add_argument("--strategies", nargs="*", default=STRATEGIES)
    parser.add_argument("--num-batches", type=int, default=16)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--dtype", default="float16", choices=list(DTYPES))
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument(
        "--assistant-marker",
        default="<|assistant|>",
        help="Marker separating prompt from assistant response",
    )

    args = parser.parse_args()

    for strategy in args.strategies:
        if strategy not in STRATEGIES:
            parser.error(f"Invalid strategy {strategy!r}; choose from {STRATEGIES}")
    require_model(args.model)
    require_file(args.data_file, "Pruning calibration dataset")
    dtype = DTYPES[args.dtype]
    out_root = reserve_new_output(args.output_dir)

    log(f"Loading tokenizer: {args.model}")
    tokenizer = AutoTokenizer.from_pretrained(
        args.model,
        use_fast=True,
        trust_remote_code=True,
    )

    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    tokenizer.padding_side = "right"

    log(f"Loading model for sensitivity scoring: {args.model}")
    score_model = AutoModelForCausalLM.from_pretrained(
        args.model,
        torch_dtype=dtype,
        trust_remote_code=True,
    ).to(args.device)

    num_layers = int(
        getattr(score_model.config, "num_hidden_layers", 0)
        or getattr(score_model.config, "n_layer", 0)
        or 0
    )

    log(f"Detected hidden layers: {num_layers}")

    sensitivity_scores = compute_layer_sensitivity(
        score_model,
        tokenizer,
        args.data_file,
        args.text_field,
        args.max_length,
        args.batch_size,
        args.num_batches,
        args.device,
        args.assistant_marker,
    )

    sensitivity_summary = {
        "model": args.model,
        "data_file": args.data_file,
        "num_layers": num_layers,
        "num_prunable_tensors": len(sensitivity_scores),
        "score_method": "assistant_only_average_abs_gradient_per_matrix_with_magnitude_pruning",
        "loss_scope": "assistant_only",
        "assistant_marker": args.assistant_marker,
        "protected_patterns": list(PROTECTED_PATTERNS),
        "target_suffixes": list(TARGET_SUFFIXES),
        "strategies": args.strategies,
        "sensitivity_scores": sensitivity_scores,
    }

    (out_root / "sensitivity_summary.json").write_text(
        json.dumps(sensitivity_summary, indent=2),
        encoding="utf-8",
    )

    del score_model

    if args.device == "cuda":
        torch.cuda.empty_cache()

    all_metrics: list[dict] = []

    for strategy in args.strategies:
        log(f"\n=== Pruning strategy: {strategy} ===")

        model = AutoModelForCausalLM.from_pretrained(
            args.model,
            torch_dtype=dtype,
            trust_remote_code=True,
        ).to(args.device)

        model.eval()

        metrics = apply_unstructured_pruning(
            model,
            sensitivity_scores,
            strategy,
            num_layers,
        )

        strategy_dir = out_root / strategy
        strategy_dir.mkdir(parents=True, exist_ok=True)

        model.config.use_cache = True

        log(f"Saving {strategy} model to: {strategy_dir}")
        model.save_pretrained(strategy_dir, safe_serialization=True)
        tokenizer.save_pretrained(strategy_dir)

        (strategy_dir / "pruning_metrics.json").write_text(
            json.dumps(metrics, indent=2),
            encoding="utf-8",
        )

        all_metrics.append(metrics)

        log(
            f"Saved {strategy} | prunable sparsity = "
            f"{metrics['overall_prunable_sparsity']}%"
        )

        del model

        if args.device == "cuda":
            torch.cuda.empty_cache()

    (out_root / "all_pruning_metrics.json").write_text(
        json.dumps(all_metrics, indent=2),
        encoding="utf-8",
    )

    write_summary_csv(out_root / "all_pruning_metrics.csv", all_metrics)

    log(f"\nDone. Pruned variants saved under: {out_root}")


if __name__ == "__main__":
    main()

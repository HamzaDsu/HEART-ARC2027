#!/usr/bin/env python
"""Convert a raw PubMed `text` JSONL sample into domain-adaptation train/eval.

The original HEART cleaning/shuffle/split logic is preserved. This wrapper
refuses to overwrite existing dataset files.
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path


def clean_text(text: str) -> str:
    text = " ".join(str(text).replace("\n", " ").split())
    return text.strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-prefix", type=Path, default=Path("data/processed/pubmed_domain"))
    parser.add_argument("--eval-size", type=float, default=0.02)
    parser.add_argument("--min-chars", type=int, default=200)
    parser.add_argument("--max-records", type=int, default=0)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    if not (0 < args.eval_size < 1):
        parser.error("--eval-size must be between 0 and 1")
    if args.max_records < 0:
        parser.error("--max-records must not be negative")

    total_path = args.output_prefix.with_suffix(".jsonl")
    train_path = args.output_prefix.with_name(args.output_prefix.name + "_train.jsonl")
    eval_path = args.output_prefix.with_name(args.output_prefix.name + "_eval.jsonl")
    for path in (total_path, train_path, eval_path):
        if path.exists() or path.is_symlink():
            raise FileExistsError(f"Refusing to overwrite PubMed dataset: {path}")

    records: list[dict] = []
    with args.input.open("r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            row = json.loads(line)
            text = clean_text(row.get("text", ""))
            if len(text) < args.min_chars:
                continue
            records.append({"text": text})
            if args.max_records and len(records) >= args.max_records:
                break

    if not records:
        raise RuntimeError("No PubMed text records found. Check --input and --min-chars.")
    random.seed(args.seed)
    random.shuffle(records)
    eval_count = max(1, int(len(records) * args.eval_size))
    eval_records = records[:eval_count]
    train_records = records[eval_count:]

    args.output_prefix.parent.mkdir(parents=True, exist_ok=True)
    for path, rows in [(total_path, records), (train_path, train_records), (eval_path, eval_records)]:
        with path.open("x", encoding="utf-8") as f:
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")

    print(f"Wrote total={len(records)}, train={len(train_records)}, eval={len(eval_records)}")
    print(f"Train: {train_path}")
    print(f"Eval : {eval_path}")


if __name__ == "__main__":
    main()

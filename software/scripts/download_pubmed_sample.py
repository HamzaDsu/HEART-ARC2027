#!/usr/bin/env python
"""Download a PubMed abstracts sample in JSONL format with a `text` field.

For a reproducible source fetch, pass --revision with a Hugging Face commit SHA.
The historical HEART experiment did not record this revision.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def extract_text(row: dict) -> str:
    if row.get("text"):
        return str(row["text"]).strip()
    title = row.get("title") or row.get("Title") or row.get("article_title") or row.get("ArticleTitle") or ""
    abstract = row.get("abstract") or row.get("Abstract") or row.get("abstract_text") or row.get("AbstractText") or ""
    if isinstance(abstract, list):
        abstract = " ".join(str(x) for x in abstract)
    return f"{title}\n\n{abstract}".strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample-size", type=int, default=10000)
    parser.add_argument("--out", required=True)
    parser.add_argument("--dataset", default="casinca/PUBMED_title_abstracts_2019_baseline")
    parser.add_argument("--split", default="train")
    parser.add_argument("--revision", default=None, help="Optional pinned Hugging Face dataset commit")
    parser.add_argument("--contains", default="")
    parser.add_argument("--min-chars", type=int, default=200)
    args = parser.parse_args()

    if args.sample_size < 1:
        parser.error("--sample-size must be positive")
    out_path = Path(args.out)
    if out_path.exists() or out_path.is_symlink():
        raise FileExistsError(f"Refusing to overwrite PubMed source sample: {out_path}")
    out_path.parent.mkdir(parents=True, exist_ok=True)

    from datasets import load_dataset
    from tqdm import tqdm
    dataset_kwargs = {"split": args.split, "streaming": True}
    if args.revision:
        dataset_kwargs["revision"] = args.revision
    print(f"Loading dataset: {args.dataset}", flush=True)
    print(f"Split: {args.split}", flush=True)
    print(f"Revision: {args.revision or 'UNPINNED'}", flush=True)
    print("Streaming: True", flush=True)
    dataset = load_dataset(args.dataset, **dataset_kwargs)
    count = 0
    scanned = 0
    keyword = args.contains.lower().strip()
    with out_path.open("x", encoding="utf-8") as f:
        for row in tqdm(dataset, total=args.sample_size, desc="Writing PubMed sample"):
            scanned += 1
            text = extract_text(row)
            if len(text) < args.min_chars:
                continue
            if keyword and keyword not in text.lower():
                continue
            f.write(json.dumps({"text": text}, ensure_ascii=False) + "\n")
            count += 1
            if count >= args.sample_size:
                break
    print(f"Scanned rows: {scanned}", flush=True)
    print(f"Wrote rows: {count}", flush=True)
    print(f"Output: {out_path}", flush=True)
    if count == 0:
        raise RuntimeError("No records written. Try lowering --min-chars or removing --contains.")


if __name__ == "__main__":
    main()

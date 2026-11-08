#!/usr/bin/env python3
"""Verify HEART input/evidence hashes; optionally rebuild datasets in isolated temp dir.

No model inference, Slurm submission, downloading, or original file overwrites.
"""
from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT_DIR = ROOT / "software" / "scripts"
MARKER = ".heart-arc2027-workspace"
MARKER_VALUE = "HEART_ARC2027_WORKSPACE_V1"
DATA_HASHES = {
    "data/raw/pubmed_sample_10k.jsonl": "b28714eb7d5b854dafc192de7a01392976fd12bc8432939fb58f433c8b881e94",
    "data/processed/pubmed_domain_train.jsonl": "052b428468c95d23143653cdfe99c38b4be1ce11736fa13a3d11fdc6098bb3c9",
    "data/processed/pubmed_domain_eval.jsonl": "1f834eec156ae235b5b2cd25980a37b720b60211e8b2cea2819695a28fec4a0f",
    "data/final_health_coaching_v4/processed/health_coaching_train.jsonl": "0cddf85b19ffc5f3b95f1acd71d5db93bdf3e07a689933a2adabacaa3c7d0d89",
    "data/final_health_coaching_v4/processed/health_coaching_eval.jsonl": "0d0b570eddca4f90bf319698c4d0c729980e148d2e42a56b1dc91182197e5962",
}
RESULT_HASHES = {
    "software/results/e1_e2_e3_master_results.csv": "89ab072240dd1120859ecd4ce8384f45f20bfe99e180645d7eef8200854c14df",
}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def verify(path: Path, expected: str) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"Missing required input: {path}")
    actual = digest(path)
    if actual != expected:
        raise ValueError(f"SHA-256 MISMATCH: {path}\n  actual:   {actual}\n  expected: {expected}")
    print(f"[MATCH] {path.name}: {actual}", flush=True)


def workspace(path: Path) -> Path:
    if path.is_symlink():
        raise ValueError("Workspace root cannot be a symlink")
    p = path.resolve(strict=True)
    if not p.is_dir() or not (p / MARKER).is_file():
        raise ValueError(f"Missing HEART workspace marker: {p / MARKER}")
    if (p / MARKER).read_text(encoding="utf-8").strip() != MARKER_VALUE:
        raise ValueError(f"Invalid HEART workspace marker: {p / MARKER}")
    return p


def run(args: list[str]) -> None:
    print("RUN:", " ".join(args), flush=True)
    subprocess.run(args, check=True, cwd=ROOT)


def rebuild(p: Path) -> None:
    # Unique temporary directory in the marked workspace: no existing checkpoint or
    # previously rebuilt dataset is overwritten. Removed only by Python's tempdir owner.
    for rel, h in DATA_HASHES.items():
        verify(p / rel, h)
    with tempfile.TemporaryDirectory(prefix="heart-dataset-check-", dir=p) as temp:
        out = Path(temp)
        v4 = out / "v4"
        run([sys.executable, str(SCRIPT_DIR / "build_health_coaching_dataset.py"),
             "--out-dir", str(v4), "--train-size", "8000", "--eval-size", "1000", "--seed", "42"])
        verify(v4 / "health_coaching_train.jsonl", DATA_HASHES["data/final_health_coaching_v4/processed/health_coaching_train.jsonl"])
        verify(v4 / "health_coaching_eval.jsonl", DATA_HASHES["data/final_health_coaching_v4/processed/health_coaching_eval.jsonl"])
        prefix = out / "pubmed" / "pubmed_domain"
        run([sys.executable, str(SCRIPT_DIR / "build_pubmed_corpus.py"),
             "--input", str(p / "data/raw/pubmed_sample_10k.jsonl"),
             "--output-prefix", str(prefix), "--eval-size", "0.02",
             "--min-chars", "200", "--max-records", "10000", "--seed", "42"])
        verify(prefix.with_name("pubmed_domain_train.jsonl"), DATA_HASHES["data/processed/pubmed_domain_train.jsonl"])
        verify(prefix.with_name("pubmed_domain_eval.jsonl"), DATA_HASHES["data/processed/pubmed_domain_eval.jsonl"])
    print("DATASET REPRODUCTION VERIFIED (datasets only; no model training or inference)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("results", "data", "rebuild"), required=True)
    parser.add_argument("--workspace", type=Path)
    args = parser.parse_args()
    try:
        if args.mode == "results":
            for rel, sha in RESULT_HASHES.items():
                verify(ROOT / rel, sha)
            print("RECORDED RESULT FILE VERIFIED (no evaluation rerun)")
        else:
            if args.workspace is None:
                parser.error("--workspace is required for data and rebuild modes")
            p = workspace(args.workspace)
            if args.mode == "data":
                for rel, sha in DATA_HASHES.items():
                    verify(p / rel, sha)
                print("SOURCE DATA VERIFIED")
            else:
                rebuild(p)
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

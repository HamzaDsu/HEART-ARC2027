#!/usr/bin/env python3
"""Selectively copy paper-relevant files from Willi into the HEART repository.

No recursive copying. Existing differing files are never overwritten.
The program defaults to a safe dry run. Tested syntax targets Python >= 3.9.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


# Relative (source, destination) file paths. All entries have been named in
# documented HEART experiments; optional entries are skipped if absent.
REPO_FILES = [
    ("build_e3_challenge.py", "scripts/build_e3_challenge.py"),
    ("scripts/evaluate_precompression_baseline.py", "scripts/evaluate_precompression_baseline.py"),
    ("scripts/evaluate_gguf_safety.py", "scripts/evaluate_gguf_safety.py"),
    ("build_safety_imatrix_v4.slurm", "slurm/build_safety_imatrix_v4.slurm"),
    ("evaluate_gguf_v4.slurm", "slurm/evaluate_gguf_v4.slurm"),
    ("evaluate_gguf_e3.slurm", "slurm/evaluate_gguf_e3.slurm"),
]

EXPERIMENT_FILES = [
    ("evaluations/e1_e2_e3_master_results.csv", "results/e1_e2_e3_master_results.csv"),
    ("data/e3_challenge_v1/health_coaching_e3.jsonl", "data/health_coaching_e3.jsonl"),
    ("data/e3_challenge_v1/contamination_audit.json", "results/e3_contamination_audit.json"),
]

# These contain calibration examples derived from model-training data. Include
# only after reviewing their origins and publication rights.
CALIBRATION_FILES = [
    ("deployment_v4_unpruned/safety_calibration.txt", "data/safety_calibration.txt"),
    ("e2_ablation/uniform_calibration_96.txt", "data/uniform_calibration_96.txt"),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Existing HealthSLM thesis source repository")
    parser.add_argument("--experiments", type=Path, required=True, help="Existing /scratch/.../health-slm-experiments")
    parser.add_argument("--apply", action="store_true", help="Perform safe copies (default: preview only)")
    parser.add_argument("--include-calibration", action="store_true", help="Also copy calibration text after review of publishing rights")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    tasks = [(args.source, src, dst) for src, dst in REPO_FILES]
    tasks += [(args.experiments, src, dst) for src, dst in EXPERIMENT_FILES]
    if args.include_calibration:
        tasks += [(args.experiments, src, dst) for src, dst in CALIBRATION_FILES]

    manifest = []
    errors = []
    for base, src_name, dst_name in tasks:
        src = base / src_name
        dst = root / dst_name
        if not src.is_file():
            print("MISSING (not copied):", src)
            errors.append(src_name)
            continue
        if src.stat().st_size > 10 * 1024 * 1024:
            raise SystemExit(f"Refusing unexpectedly large file (>10 MiB): {src}")
        digest = sha256(src)
        state = "COPY"
        if dst.exists():
            if not dst.is_file() or sha256(dst) != digest:
                raise SystemExit(f"Destination exists with different content; refusing overwrite: {dst}")
            state = "UNCHANGED"
        print(f"{state:10s} {src}  ->  {dst.relative_to(root)}")
        manifest.append({"path": str(dst.relative_to(root)), "sha256": digest, "size_bytes": src.stat().st_size})
        if args.apply and state == "COPY":
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)

    print(f"\n{len(manifest)} available files, {len(errors)} missing; mode={'APPLY' if args.apply else 'DRY RUN'}")
    if args.apply:
        manifest_path = root / "FILE_MANIFEST.json"
        manifest_path.write_text(json.dumps({"files": manifest, "missing_source_paths": errors}, indent=2) + "\n", encoding="utf-8")
        print("Manifest created:", manifest_path)
    if errors:
        print("Review missing files; optional files may not exist in older working copies.")


if __name__ == "__main__":
    main()

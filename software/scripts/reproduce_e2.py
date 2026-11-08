#!/usr/bin/env python3
"""Reproduce E2 Q3_K_S models and verify exact or metadata-only GGUF equivalence.

Preview by default; --execute runs immutable, out-of-tree Slurm-compatible jobs.
A different SHA-256 is accepted ONLY when an explicitly provided original GGUF
matches in all meaningful metadata and all tensor/importance-matrix payloads.
Requires compare_gguf.py adjacent to this script. Standard-library Python 3.9+.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path

from compare_gguf import parse as parse_gguf, sha_part, number as gguf_number, string as gguf_string, value as gguf_value

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_FILE = ROOT / "results" / "e2_reference_hashes.json"
CALIB_DIR = ROOT / "data" / "calibration"
MATRIX_CALIB = {
    "uniform96": ("uniform_calibration_96.txt", "uniform_imatrix_96.gguf"),
    "safety": ("safety_calibration.txt", "safety_imatrix.gguf"),
}
MODELS = {
    "no-imatrix": "v4_q3ks_no_imatrix.gguf",
    "uniform96": "v4_q3ks_uniform96.gguf",
    "safety": "v4_q3ks_safety_rebuilt.gguf",
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def reference_map(entries: list[str], valid: set[str]) -> dict[str, Path]:
    refs = {}
    for entry in entries:
        name, sep, location = entry.partition("=")
        if not sep or name not in valid or not location:
            raise ValueError(f"Invalid --reference '{entry}'; expected ARTIFACT_NAME=/absolute/path")
        if name in refs:
            raise ValueError(f"Duplicate --reference for {name}")
        refs[name] = Path(location).expanduser().resolve()
    return refs


def permitted_metadata_difference(name: str, key: str, a_val, b_val) -> bool:
    """Allow only provenance paths to change; never ignore numerical fields."""
    if name.endswith("imatrix_96.gguf") or name == "safety_imatrix.gguf":
        # GGUF imatrix datasets are string arrays; the parser hashes their bytes.
        return key == "imatrix.datasets"
    if name == "v4_q3ks_uniform96.gguf":
        if key == "quantize.imatrix.file":
            return all(isinstance(v, tuple) and v[0] == "string" and
                       v[1].endswith("uniform_imatrix_96.gguf") for v in (a_val, b_val))
        if key == "quantize.imatrix.dataset":
            return all(isinstance(v, tuple) and v[0] == "string" and
                       v[1].endswith("uniform_calibration_96.txt") for v in (a_val, b_val))
    if name == "v4_q3ks_safety_rebuilt.gguf":
        if key == "quantize.imatrix.file":
            return all(isinstance(v, tuple) and v[0] == "string" and
                       v[1].endswith("safety_imatrix.gguf") for v in (a_val, b_val))
        if key == "quantize.imatrix.dataset":
            return all(isinstance(v, tuple) and v[0] == "string" and
                       v[1].endswith("safety_calibration.txt") for v in (a_val, b_val))
    return False


def imatrix_dataset_paths(path: Path) -> tuple[str, ...]:
    """Read dataset provenance strings from GGUF metadata (not tensor entries)."""
    with path.open("rb") as f:
        if f.read(4) != b"GGUF":
            raise ValueError(f"Not GGUF: {path}")
        if gguf_number(f, "<I") not in (2, 3):
            raise ValueError(f"Unsupported GGUF version: {path}")
        gguf_number(f, "<Q")  # number of tensors
        nkeys = gguf_number(f, "<Q")
        if nkeys > 2_000_000:
            raise ValueError(f"Unreasonable GGUF metadata entry count: {path}")
        for _ in range(nkeys):
            key = gguf_string(f)
            kind = gguf_number(f, "<I")
            if key != "imatrix.datasets":
                gguf_value(f, kind)
                continue
            if kind != 9 or gguf_number(f, "<I") != 8:
                raise RuntimeError(f"imatrix.datasets is not a string array: {path}")
            count = gguf_number(f, "<Q")
            if not 1 <= count <= 32:
                raise RuntimeError(f"Unexpected imatrix dataset count {count}: {path}")
            return tuple(gguf_string(f) for _ in range(count))
    raise RuntimeError(f"Missing imatrix.datasets metadata: {path}")


def verify_semantic_gguf(name: str, original: Path, regenerated: Path) -> tuple[int, int]:
    """Verify all imatrix/model tensor payloads, layouts and non-path metadata.

    In llama.cpp GGUF imatrix output, .in_sum2 and .counts are *tensors*.
    They are not metadata keys; the old v2 check incorrectly inspected keys.
    """
    a, b = parse_gguf(original), parse_gguf(regenerated)
    if a["version"] != b["version"]:
        raise RuntimeError(f"GGUF version mismatch: {name}")
    keys_a, keys_b = set(a["metadata"]), set(b["metadata"])
    if keys_a != keys_b:
        raise RuntimeError(f"GGUF metadata key set differs: {name}")
    allowed_diffs = []
    for key in sorted(keys_a):
        left, right = a["metadata"][key], b["metadata"][key]
        if left != right:
            if not permitted_metadata_difference(name, key, left, right):
                raise RuntimeError(f"Non-path metadata changed in {name}: {key}: {left!r} -> {right!r}")
            allowed_diffs.append(key)

    if set(a["tensors"]) != set(b["tensors"]):
        raise RuntimeError(f"Tensor name set differs: {name}")
    count = len(a["tensors"])
    if name in ("uniform_imatrix_96.gguf", "safety_imatrix.gguf"):
        # Importance scores and counts are GGUF tensors, not metadata keys.
        numeric_tensors = [k for k in a["tensors"]
                           if k.endswith(".in_sum2") or k.endswith(".counts")]
        if not numeric_tensors or "imatrix.chunk_count" not in keys_a:
            raise RuntimeError(f"Expected importance-matrix numerical tensors missing: {name}")
        if "imatrix.datasets" in allowed_diffs:
            expected_file = ("uniform_calibration_96.txt" if name == "uniform_imatrix_96.gguf"
                             else "safety_calibration.txt")
            original_paths = imatrix_dataset_paths(original)
            regenerated_paths = imatrix_dataset_paths(regenerated)
            if len(original_paths) != len(regenerated_paths) or any(
                Path(path).name != expected_file
                for path in (*original_paths, *regenerated_paths)
            ):
                raise RuntimeError(f"Non-provenance dataset change in {name}: "
                                   f"{original_paths} -> {regenerated_paths}")
    elif count == 0:
        raise RuntimeError(f"No model tensors found: {name}")

    with original.open("rb") as fa, regenerated.open("rb") as fb:
        for tensor in sorted(a["tensors"]):
            ta, tb = a["tensors"][tensor], b["tensors"][tensor]
            if (ta["dims"], ta["type"], ta["length"]) != (tb["dims"], tb["type"], tb["length"]):
                raise RuntimeError(f"Tensor layout mismatch in {name}: {tensor}")
            if sha_part(fa, ta["begin"], ta["length"]) != sha_part(fb, tb["begin"], tb["length"]):
                raise RuntimeError(f"Tensor payload mismatch in {name}: {tensor}")

    print(f"[PAYLOAD MATCH] {name}: {count} GGUF tensors; "
          f"{len(allowed_diffs)} allowed provenance metadata differences: {allowed_diffs}", flush=True)
    return count, len(allowed_diffs)


def verify(path: Path, name: str, expected_sha: str, refs: dict[str, Path], ref: dict) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"Missing artifact: {path}")
    actual_sha = sha256_file(path)
    if actual_sha == expected_sha:
        print(f"[SHA256 MATCH] {name}: {actual_sha}", flush=True)
        return {"status": "sha256_exact", "sha256": actual_sha, "bytes": path.stat().st_size}
    print(f"[SHA256 DIFF] {name}\n  actual:   {actual_sha}\n  expected: {expected_sha}", flush=True)
    original = refs.get(name)
    if original is None:
        raise RuntimeError(f"SHA mismatch for {name}. Supply --reference {name}=/path/to/original to check payload equivalence")
    if not original.is_file():
        raise FileNotFoundError(f"Original/reference GGUF missing: {original}")
    original_sha = sha256_file(original)
    if original_sha != expected_sha:
        raise RuntimeError(f"Supplied reference file is not the recorded original for {name}: {original_sha}")
    # Ensure the checked-in calibration is the original calibration for
    # every imatrix-backed variant, even when only verifying model payload.
    for mode, (cal_name, matrix_name) in MATRIX_CALIB.items():
        if name in (matrix_name, MODELS[mode]):
            calibration = CALIB_DIR / cal_name
            checksum = sha256_file(calibration)
            if checksum != ref["calibration_inputs"][cal_name]:
                raise RuntimeError(f"Calibration input mismatch: {calibration}")
    ntensors, n_prov = verify_semantic_gguf(name, original, path)
    return {"status": "payload_exact_metadata_path_diff", "sha256": actual_sha,
            "reference_sha256": original_sha, "bytes": path.stat().st_size,
            "tensor_count": ntensors, "path_metadata_differences": n_prov}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--llama-dir", type=Path, required=True)
    p.add_argument("--fp16", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    p.add_argument("--calibration-dir", type=Path, default=CALIB_DIR)
    p.add_argument("--mode", choices=["all", *MODELS], default="all")
    p.add_argument("--phase", choices=["all", "imatrix", "quantize", "verify"], default="all")
    p.add_argument("--threads", type=int, default=8)
    p.add_argument("--execute", action="store_true", help="Run the planned operations; default is preview")
    p.add_argument("--reference", action="append", default=[], metavar="ARTIFACT=PATH",
                   help="Original GGUF for metadata-only mismatch comparison (repeatable)")
    args = p.parse_args()
    if args.threads < 1:
        raise ValueError("--threads must be positive")
    ref = json.loads(EXPECTED_FILE.read_text(encoding="utf-8"))
    refs = reference_map(args.reference, set(ref["expected_outputs"]))
    fp16 = args.fp16.expanduser().resolve()
    out = args.output_dir.expanduser().resolve()
    calib_dir = args.calibration_dir.expanduser().resolve()
    llama = args.llama_dir.expanduser().resolve()
    modes = list(MODELS) if args.mode == "all" else [args.mode]
    if not fp16.is_file() or fp16.stat().st_size != ref["fp16_gguf_size_bytes"]:
        raise RuntimeError("FP16 GGUF missing or unexpected size; verify unpruned source model")
    if out in (ROOT.resolve(), calib_dir, fp16.parent) or ROOT.resolve() in out.parents or calib_dir in out.parents or fp16.parent in out.parents:
        raise ValueError("Output path must be outside the repository and separate from calibration and FP16 source")

    imatrix_bin, quant_bin = llama / "build/bin/llama-imatrix", llama / "build/bin/llama-quantize"
    if args.phase != "verify":
        for exe in (imatrix_bin, quant_bin):
            if not exe.is_file() or not os.access(exe, os.X_OK):
                raise FileNotFoundError(f"Missing executable: {exe}")
        revision = subprocess.run(["git", "-C", str(llama), "rev-parse", "HEAD"],
                                  capture_output=True, text=True, check=True).stdout.strip()
        if revision != ref["llama_cpp_revision"]:
            raise RuntimeError(f"llama.cpp version mismatch: {revision} != {ref['llama_cpp_revision']}")
    for mode in modes:
        if mode in MATRIX_CALIB:
            cal_name = MATRIX_CALIB[mode][0]
            f = calib_dir / cal_name
            actual = sha256_file(f)
            expected = ref["calibration_inputs"][cal_name]
            if actual != expected:
                raise RuntimeError(f"Calibration SHA mismatch: {f}: {actual}")
            print(f"[CALIBRATION MATCH] {cal_name}: {actual}", flush=True)

    tasks = []
    for mode in modes:
        if mode in MATRIX_CALIB and args.phase in ("imatrix", "all"):
            calib_name, matrix_name = MATRIX_CALIB[mode]
            tasks.append((matrix_name, [str(imatrix_bin), "-m", str(fp16), "-f", str(calib_dir / calib_name),
                                        "-o", str(out / matrix_name), "--output-format", "gguf",
                                        "--parse-special", "--no-ppl", "-t", str(args.threads)]))
    for mode in modes:
        if args.phase in ("quantize", "all"):
            matrix_flags = ["--imatrix", str(out / MATRIX_CALIB[mode][1])] if mode in MATRIX_CALIB else []
            name = MODELS[mode]
            tasks.append((name, [str(quant_bin), *matrix_flags, str(fp16), str(out / name),
                                 "Q3_K_S", str(args.threads)]))

    print(f"Mode={args.mode}; phase={args.phase}; execute={args.execute}", flush=True)
    print(f"FP16={fp16}\nOutput={out}", flush=True)
    if args.phase == "verify":
        results = {}
        for mode in modes:
            targets = ([MATRIX_CALIB[mode][1]] if mode in MATRIX_CALIB else []) + [MODELS[mode]]
            for name in targets:
                results[name] = verify(out / name, name, ref["expected_outputs"][name], refs, ref)
        print("VERIFIED all selected artifacts (exact SHA or checked payload equivalence).", flush=True)
        return 0

    print("Commands:", flush=True)
    for name, cmd in tasks:
        print(f"  {name}: {shlex.join(cmd)}", flush=True)
    if not args.execute:
        print("PREVIEW ONLY. No outputs written.", flush=True)
        return 0

    out.mkdir(parents=True, exist_ok=True)
    results = {}
    for name, cmd in tasks:
        path = out / name
        if path.exists():
            print(f"[EXISTS; DO NOT OVERWRITE] {path}", flush=True)
        else:
            # A quantize-only job must validate the upstream imatrix first.
            for mode, (_, matrix_name) in MATRIX_CALIB.items():
                if name == MODELS[mode]:
                    verify(out / matrix_name, matrix_name, ref["expected_outputs"][matrix_name], refs, ref)
            logfile = out / f"{name}.log"
            if logfile.exists():
                raise FileExistsError(f"Prior log exists but output missing; inspect before retry: {logfile}")
            print(f"[RUN] {name}: log={logfile}", flush=True)
            with logfile.open("x", encoding="utf-8") as log:
                log.write(f"COMMAND: {shlex.join(cmd)}\n")
                log.flush()
                subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, check=True)
        results[name] = verify(path, name, ref["expected_outputs"][name], refs, ref)

    manifest = out / f"reproduce_e2_run_manifest_{args.mode}_{args.phase}.json"
    report = {
        "generated_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "llama_cpp_revision": ref["llama_cpp_revision"],
        "source_fp16": str(fp16), "source_fp16_sha256": sha256_file(fp16),
        "threads": args.threads, "mode": args.mode, "phase": args.phase,
        "verification": results,
    }
    manifest.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Reproduction verified. Manifest: {manifest}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)

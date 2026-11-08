#!/usr/bin/env python3
"""Generate, compile, execute and benchmark multiple GGUF models."""

import argparse
import csv
import re
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
RUNNER = SCRIPT_DIR / "build_and_run.sh"
RESULTS_DIR = Path.home() / "health-slm-inference-results"


def parse_metric(pattern, text):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else None


def elapsed_seconds(value):
    if not value:
        return None

    parts = [float(part) for part in value.split(":")]
    seconds = 0.0

    for part in parts:
        seconds = seconds * 60 + part

    return round(seconds, 3)


def validate_model(model):
    if not model.is_file():
        raise ValueError(f"Model not found: {model}")

    if model.suffix.lower() != ".gguf":
        raise ValueError(f"Not a GGUF model: {model}")

    with model.open("rb") as file:
        if file.read(4) != b"GGUF":
            raise ValueError(f"Invalid GGUF header: {model}")


def run_model(model, prompt, timeout):
    model = Path(model).expanduser().resolve()
    validate_model(model)

    print(f"\n===== PROCESSING: {model.name} =====", flush=True)

    completed = subprocess.run(
        [str(RUNNER), str(model), prompt],
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )

    print(completed.stdout)

    if completed.stderr:
        print(completed.stderr, file=sys.stderr)

    name = re.sub(r"[^A-Za-z0-9_-]", "_", model.stem)
    log = RESULTS_DIR / f"{name}_inference.log"
    source = SCRIPT_DIR / "generated" / f"{name}.c"

    log_text = log.read_text(errors="replace") if log.is_file() else ""

    elapsed = parse_metric(
        r"^\s*Elapsed \(wall clock\) time \([^)]*\):\s*(\S+)\s*$",
        log_text,
    )

    memory = parse_metric(
        r"Maximum resident set size \(kbytes\):\s*(\d+)",
        log_text,
    )

    exit_status = parse_metric(
        r"^\s*Exit status:\s*(\d+)",
        log_text,
    )

    passed = (
        completed.returncode == 0
        and source.is_file()
        and "===== GENERATED RESPONSE =====" in log_text
        and exit_status == "0"
    )

    result = {
        "model": model.name,
        "model_size_mib": round(model.stat().st_size / 1048576, 2),
        "c_generated": source.is_file(),
        "elapsed_seconds": elapsed_seconds(elapsed),
        "peak_ram_mib": round(int(memory) / 1024, 2) if memory else None,
        "status": "PASS" if passed else "FAIL",
        "log": str(log),
    }

    print("===== RESULT =====")
    for key, value in result.items():
        print(f"{key}: {value}")

    return result


def main():
    parser = argparse.ArgumentParser(
        description="Automated GGUF-to-C integration and inference validation"
    )

    parser.add_argument(
        "models",
        nargs="+",
        help="Paths to compatible GGUF models",
    )

    parser.add_argument(
        "--prompt",
        default="Give three general tips for maintaining a healthy lifestyle.",
    )

    parser.add_argument(
        "--timeout",
        type=int,
        default=180,
    )

    args = parser.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    results = []

    for model in args.models:
        try:
            result = run_model(model, args.prompt, args.timeout)
        except (ValueError, OSError, subprocess.TimeoutExpired) as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            result = {
                "model": Path(model).name,
                "model_size_mib": None,
                "c_generated": False,
                "elapsed_seconds": None,
                "peak_ram_mib": None,
                "status": "FAIL",
                "log": "",
            }

        results.append(result)

    report = RESULTS_DIR / "c_automation_results.csv"

    with report.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=results[0].keys())
        writer.writeheader()
        writer.writerows(results)

    print(f"\nResults saved: {report}")

    passed = sum(result["status"] == "PASS" for result in results)
    print(f"Models passed: {passed}/{len(results)}")

    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())

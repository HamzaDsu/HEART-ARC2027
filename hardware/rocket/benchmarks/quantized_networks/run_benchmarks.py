#!/usr/bin/env python3
"""Automated CPU reference benchmarks for quantized CNN/ViT networks."""

import argparse
import csv
import os
import re
import subprocess
import sys
import time
from pathlib import Path

MODELS = ["resnet18", "vgg11", "swint", "levit384"]

parser = argparse.ArgumentParser()
parser.add_argument(
    "--workspace",
    type=Path,
    default=Path.home() / "quantized-network-benchmarks",
)
parser.add_argument("--timeout", type=int, default=600)
args = parser.parse_args()

workspace = args.workspace.expanduser().resolve()
results_dir = workspace / "results"
logs_dir = results_dir / "logs"

logs_dir.mkdir(parents=True, exist_ok=True)

rows = []

for model in MODELS:
    folder = workspace / model
    script = folder / "npu_inference_integer.py"
    log_file = logs_dir / f"{model}_mqf.log"

    print(f"\n===== Running {model.upper()} =====", flush=True)

    if not script.is_file():
        print(f"Missing inference script: {script}")
        rows.append({
            "network": model,
            "status": "MISSING",
            "exit_code": "",
            "runtime_seconds": "",
            "peak_memory_mib": "",
            "predicted_class": "",
        })
        continue

    # Prevent an unexpected CIFAR-10 download in VGG11.
    if model == "vgg11" and 'download=True' in script.read_text():
        print("VGG11 still enables dataset download. Skipping.")
        rows.append({
            "network": model,
            "status": "CONFIGURATION_ERROR",
            "exit_code": "",
            "runtime_seconds": "",
            "peak_memory_mib": "",
            "predicted_class": "",
        })
        continue

    env = os.environ.copy()
    env["OMP_NUM_THREADS"] = "2"
    env["MKL_NUM_THREADS"] = "2"

    command = [
        "/usr/bin/time",
        "-v",
        sys.executable,
        "-u",
        str(script),
        "--mode",
        "mqf",
    ]

    start = time.monotonic()
    exit_code = 124

    with log_file.open("w") as log:
        try:
            result = subprocess.run(
                command,
                cwd=folder,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                timeout=args.timeout,
                check=False,
            )
            exit_code = result.returncode
        except subprocess.TimeoutExpired:
            log.write("\nBenchmark timed out.\n")

    elapsed = time.monotonic() - start
    output = log_file.read_text(errors="replace")

    memory = re.search(
        r"Maximum resident set size \(kbytes\):\s*(\d+)",
        output,
    )
    prediction = re.search(
        r"Predicted(?: Class)?\s*:\s*(\d+)",
        output,
    )

    status = "PASS" if exit_code == 0 else "FAIL"

    row = {
        "network": model,
        "status": status,
        "exit_code": exit_code,
        "runtime_seconds": round(elapsed, 3),
        "peak_memory_mib": (
            round(int(memory.group(1)) / 1024, 2)
            if memory else ""
        ),
        "predicted_class": (
            prediction.group(1) if prediction else ""
        ),
    }

    rows.append(row)

    print(f"Status: {status}")
    print(f"Runtime: {row['runtime_seconds']} seconds")
    print(f"Peak memory: {row['peak_memory_mib']} MiB")
    print(f"Log: {log_file}")

csv_file = results_dir / "benchmark_results.csv"

with csv_file.open("w", newline="") as file:
    writer = csv.DictWriter(file, fieldnames=[
        "network",
        "status",
        "exit_code",
        "runtime_seconds",
        "peak_memory_mib",
        "predicted_class",
    ])
    writer.writeheader()
    writer.writerows(rows)

passed = sum(row["status"] == "PASS" for row in rows)

print(f"\n===== FINAL SUMMARY: {passed}/{len(MODELS)} PASSED =====")
print(f"Results saved: {csv_file}")

sys.exit(0 if passed == len(MODELS) else 1)

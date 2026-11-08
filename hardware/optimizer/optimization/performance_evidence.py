"""Validation of measured, workload-specific performance evidence."""

import json
from pathlib import Path


def summarize_q3k_evidence(evidence_path):
    data = json.loads(Path(evidence_path).read_text())

    if data.get("measurement_type") != "simulated_riscv_cycles":
        raise ValueError("Unsupported performance measurement type")

    measurements = data["q3k"]
    baseline = measurements["normal_0k"]
    baseline_cycles = baseline["cycles"]

    if baseline_cycles <= 0:
        raise ValueError("Invalid baseline cycle count")

    checksum = baseline["checksum"]
    comparisons = {}

    for name, item in measurements.items():
        cycles = item["cycles"]

        if cycles <= 0:
            raise ValueError(f"Invalid cycles: {name}")

        if item["checksum"] != checksum:
            raise ValueError(f"Checksum mismatch: {name}")

        comparisons[name] = {
            "cycles": cycles,
            "speedup_vs_normal": round(
                baseline_cycles / cycles, 4
            ),
            "cycle_change_percent": round(
                (cycles / baseline_cycles - 1) * 100, 2
            ),
        }

    return {
        "status": "benchmark_evidence_validated",
        "scope": "exact_measured_kernel_only",
        "hardware_execution": data["hardware_execution"],
        "workload": data["workload"],
        "checksum": checksum,
        "comparisons": comparisons,
        "limitation": (
            "These measurements do not predict arbitrary "
            "model inference performance or scratchpad placement."
        ),
    }


def summarize_q3k_l1_evidence(manifest_path):
    """Validate controlled L1 benchmark results from Verilator."""
    import hashlib
    import re

    manifest_path = Path(manifest_path)
    data = json.loads(manifest_path.read_text())
    folder = manifest_path.parent

    if data.get("workload") != "Q3_K x Q8_K":
        raise ValueError("Unsupported workload")

    if data.get("blocks", 0) <= 0 or data.get("repeats", 0) <= 0:
        raise ValueError("Invalid workload size")

    binary = folder / "q3k-16-isolated.riscv"

    digest = hashlib.sha256(binary.read_bytes()).hexdigest()
    if digest != data["benchmark_sha256"]:
        raise ValueError("Benchmark SHA256 mismatch")

    configurations = data["configurations"]
    if set(configurations) != {"8k", "16k", "32k"}:
        raise ValueError("Unexpected cache configurations")

    measurements = {}
    checksums = set()

    for label, item in configurations.items():
        filename = item["log_file"]

        if Path(filename).name != filename:
            raise ValueError("Invalid log path")

        expected_kib = int(label[:-1])
        actual_bytes = (
            item["cache_sets"]
            * item["cache_ways"]
            * data["cache_line_bytes"]
        )

        if actual_bytes != expected_kib * 1024:
            raise ValueError(f"Cache metadata mismatch: {label}")

        log = (folder / filename).read_text()

        matches = re.findall(
            r"RESULT cycles=(\d+) checksum=(0x[0-9a-fA-F]+)",
            log,
        )

        if len(matches) != 1 or "Verilog $finish" not in log:
            raise ValueError(f"Incomplete benchmark: {label}")

        if "Assertion" in log or "out of bounds" in log:
            raise ValueError(f"Simulation error: {label}")

        cycles = int(matches[0][0])
        checksum = matches[0][1].lower()

        if cycles <= 0 or cycles != item["cycles"]:
            raise ValueError(f"Cycle mismatch: {label}")

        if checksum != data["expected_checksum"].lower():
            raise ValueError(f"Checksum mismatch: {label}")

        checksums.add(checksum)

        measurements[label] = {
            "cycles": cycles,
            "cache_sets": item["cache_sets"],
            "cache_ways": item["cache_ways"],
        }

    if len(checksums) != 1:
        raise ValueError("Inconsistent checksums")

    baseline = measurements["16k"]["cycles"]

    for item in measurements.values():
        item["cycle_change_percent"] = round(
            (item["cycles"] / baseline - 1) * 100, 4
        )
        item["speedup_vs_16k"] = round(
            baseline / item["cycles"], 6
        )

    return {
        "status": "benchmark_evidence_validated",
        "measurement_type": "simulated_riscv_cycles",
        "scope": "exact_measured_kernel_only",
        "hardware_execution": False,
        "automatic_ranking_eligible": False,
        "workload": data["workload"],
        "blocks": data["blocks"],
        "checksum": data["expected_checksum"],
        "baseline": "16k",
        "comparisons": measurements,
        "limitation": (
            "Kernel simulation cycles do not establish full-model "
            "inference performance. The 32 KiB cache also uses "
            "different associativity."
        ),
    }

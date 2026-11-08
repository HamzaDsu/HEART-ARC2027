"""Match benchmark evidence to explicitly identified artifacts."""

import json
from pathlib import Path

from optimizer.optimization.performance_evidence import (
    summarize_q3k_evidence,
)


def match_q3k_artifacts(
    evidence_path,
    manifest_path,
    *,
    key,
    simulator_config,
    simulator_sha256,
    binary_sha256,
    scratchpad_kib,
    blocks=64,
    repeats=1,
):
    manifest = json.loads(Path(manifest_path).read_text())
    evidence = summarize_q3k_evidence(evidence_path)

    if manifest.get("platform") != "Chipyard Rocket / Verilator":
        return None

    if (
        manifest.get("kernel") != "Q3_K x Q8_K"
        or manifest.get("blocks") != blocks
        or manifest.get("repeats") != repeats
        or evidence["workload"].get("blocks") != blocks
        or evidence["workload"].get("repeats") != repeats
    ):
        return None

    config = manifest.get("configurations", {}).get(key)

    if config is None:
        return None

    expected = {
        "simulator_config": simulator_config,
        "simulator_sha256": simulator_sha256,
        "binary_sha256": binary_sha256,
        "scratchpad_kib": scratchpad_kib,
    }

    if any(config.get(k) != v for k, v in expected.items()):
        return None

    measurement = evidence["comparisons"].get(key)

    if measurement is None:
        return None

    return {
        "status": "benchmark_artifacts_matched",
        "configuration": key,
        "cycles": measurement["cycles"],
        "speedup_vs_normal": measurement["speedup_vs_normal"],
        "hardware_execution": False,
        "eligible_for_automatic_ranking": False,
        "provenance": "artifact_hashes_collected_after_runs",
    }

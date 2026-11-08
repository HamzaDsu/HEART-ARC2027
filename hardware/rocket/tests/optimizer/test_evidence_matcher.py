import json
from pathlib import Path

import pytest

from optimizer.optimization.evidence_matcher import (
    match_q3k_artifacts,
)


ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "results/optimizer/measurements"
MANIFEST = BASE / "verilator_q3k_artifact_manifest.json"
EVIDENCE = BASE / "verilator_q3k_memory_evidence.json"


def match(key="sbus_64k", **changes):
    manifest = json.loads(MANIFEST.read_text())
    config = manifest["configurations"].get(
        key, manifest["configurations"]["sbus_64k"]
    )

    arguments = {
        "key": key,
        "simulator_config": config["simulator_config"],
        "simulator_sha256": config["simulator_sha256"],
        "binary_sha256": config["binary_sha256"],
        "scratchpad_kib": config["scratchpad_kib"],
    }

    arguments.update(changes)

    return match_q3k_artifacts(
        EVIDENCE, MANIFEST, **arguments
    )


@pytest.mark.parametrize(
    "key,cycles",
    [
        ("normal_0k", 713639),
        ("sbus_64k", 1072451),
        ("sbus_128k", 1072451),
    ],
)
def test_valid_artifact_match(key, cycles):
    result = match(key)

    assert result is not None
    assert result["cycles"] == cycles
    assert result["eligible_for_automatic_ranking"] is False


@pytest.mark.parametrize(
    "changes",
    [
        {"simulator_sha256": "0" * 64},
        {"binary_sha256": "0" * 64},
        {"scratchpad_kib": 256},
        {"simulator_config": "UnknownConfig"},
        {"blocks": 32},
        {"repeats": 2},
        {"key": "unknown"},
    ],
)
def test_incorrect_artifacts_rejected(changes):
    assert match(**changes) is None

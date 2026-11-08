import json
import shutil
from pathlib import Path

import pytest

from optimizer.optimization.performance_evidence import (
    summarize_q3k_l1_evidence,
)

SOURCE = (
    Path(__file__).resolve().parents[2]
    / "results/optimizer/measurements/controlled_q3k_l1_16blocks"
)


def test_real_l1_measurements():
    result = summarize_q3k_l1_evidence(
        SOURCE / "manifest.json"
    )

    assert result["status"] == "benchmark_evidence_validated"
    assert result["hardware_execution"] is False
    assert result["automatic_ranking_eligible"] is False

    comparisons = result["comparisons"]

    assert comparisons["8k"]["cycles"] == 178376
    assert comparisons["16k"]["cycles"] == 177519
    assert comparisons["32k"]["cycles"] == 177563

    assert comparisons["16k"]["speedup_vs_16k"] == 1.0


def test_rejects_wrong_checksum(tmp_path):
    folder = tmp_path / "evidence"
    shutil.copytree(SOURCE, folder)

    log = folder / "8k.log"
    log.write_text(
        log.read_text().replace(
            "checksum=0xc5911000",
            "checksum=0xdeadbeef",
        )
    )

    with pytest.raises(ValueError, match="Checksum mismatch"):
        summarize_q3k_l1_evidence(folder / "manifest.json")


def test_rejects_wrong_cycles(tmp_path):
    folder = tmp_path / "evidence"
    shutil.copytree(SOURCE, folder)

    log = folder / "32k.log"
    log.write_text(
        log.read_text().replace(
            "cycles=177563",
            "cycles=999999",
        )
    )

    with pytest.raises(ValueError, match="Cycle mismatch"):
        summarize_q3k_l1_evidence(folder / "manifest.json")


def test_rejects_changed_binary(tmp_path):
    folder = tmp_path / "evidence"
    shutil.copytree(SOURCE, folder)

    binary = folder / "q3k-16-isolated.riscv"
    binary.write_bytes(binary.read_bytes() + b"modified")

    with pytest.raises(ValueError, match="SHA256 mismatch"):
        summarize_q3k_l1_evidence(folder / "manifest.json")

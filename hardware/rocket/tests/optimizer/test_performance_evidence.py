
import json
from pathlib import Path

import pytest

from optimizer.optimization.performance_evidence import (
    summarize_q3k_evidence,
)


EVIDENCE = (
    Path(__file__).resolve().parents[2]
    / "results/optimizer/measurements"
    / "verilator_q3k_memory_evidence.json"
)


def modified_evidence(tmp_path, modify):
    data = json.loads(EVIDENCE.read_text())
    modify(data)

    path = tmp_path / "evidence.json"
    path.write_text(json.dumps(data))
    return path


def test_real_q3k_measurements():
    result = summarize_q3k_evidence(EVIDENCE)

    assert result["status"] == "benchmark_evidence_validated"
    assert result["scope"] == "exact_measured_kernel_only"
    assert result["hardware_execution"] is False

    comparisons = result["comparisons"]

    assert comparisons["normal_0k"]["cycles"] == 713639
    assert comparisons["sbus_64k"]["cycles"] == 1072451
    assert comparisons["sbus_128k"]["cycles"] == 1072451

    assert comparisons["normal_0k"]["speedup_vs_normal"] == 1.0
    assert comparisons["sbus_64k"]["speedup_vs_normal"] == 0.6654


def test_rejects_checksum_mismatch(tmp_path):
    def modify(data):
        data["q3k"]["sbus_64k"]["checksum"] = "0xdeadbeef"

    path = modified_evidence(tmp_path, modify)

    with pytest.raises(ValueError, match="Checksum mismatch"):
        summarize_q3k_evidence(path)


def test_rejects_invalid_cycles(tmp_path):
    def modify(data):
        data["q3k"]["sbus_64k"]["cycles"] = 0

    path = modified_evidence(tmp_path, modify)

    with pytest.raises(ValueError, match="Invalid cycles"):
        summarize_q3k_evidence(path)


def test_rejects_unsupported_measurement_type(tmp_path):
    def modify(data):
        data["measurement_type"] = "unknown"

    path = modified_evidence(tmp_path, modify)

    with pytest.raises(ValueError, match="Unsupported"):
        summarize_q3k_evidence(path)

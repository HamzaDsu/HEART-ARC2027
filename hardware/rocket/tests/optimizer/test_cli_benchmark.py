import hashlib
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from optimizer import cli
from optimizer.optimization import evidence_matcher


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = (
    ROOT / "results/optimizer/measurements"
    / "verilator_q3k_artifact_manifest.json"
)


def run_cli(monkeypatch, tmp_path, extra_args):
    model = tmp_path / "model.gguf"
    model.write_bytes(b"GGUF")

    report = tmp_path / "report.json"

    payload = {
        "status": "heuristic_ranking_complete",
        "model": "test-model",
        "board": "arty-a7-100",
        "recommended_exploration_candidate": {
            "configuration": {
                "cores": 1,
                "icache_kib": 16,
                "dcache_kib": 16,
                "scratchpad_kib": 0,
                "l2_enabled": False,
                "target_clock_mhz": 50,
            },
            "resource_estimates": {},
            "model_adjusted_throughput_proxy": 1.0,
        },
    }

    monkeypatch.setattr(
        cli.subprocess,
        "run",
        lambda *args, **kwargs: SimpleNamespace(
            returncode=0,
            stdout=json.dumps(payload),
            stderr="",
        ),
    )

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "optimizer.cli",
            "--model", str(model),
            "--board", "arty-a7-100",
            "--output", str(report),
            *extra_args,
        ],
    )

    code = cli.main()
    return code, json.loads(report.read_text())


def artifact_args(tmp_path, key="sbus_64k"):
    manifest = json.loads(MANIFEST.read_text())
    config = manifest["configurations"][key]

    simulator = tmp_path / (
        "simulator-chipyard.harness-"
        + config["simulator_config"]
    )
    binary = tmp_path / config["riscv_binary"]

    simulator.write_bytes(b"test simulator")
    binary.write_bytes(b"test binary")

    return [
        "--benchmark-key", key,
        "--benchmark-simulator", str(simulator),
        "--benchmark-binary", str(binary),
    ]


def test_default_is_heuristic(monkeypatch, tmp_path):
    code, data = run_cli(monkeypatch, tmp_path, [])

    assert code == 0

    best = data["recommended_exploration_candidate"]
    assert best["performance_assessment"]["status"] == "heuristic_estimate"
    assert "benchmark_reference" not in data


def test_matching_benchmark_attached(monkeypatch, tmp_path):
    args = artifact_args(tmp_path)

    def fake_match(*paths, **kwargs):
        assert kwargs["key"] == "sbus_64k"
        assert kwargs["simulator_sha256"] == hashlib.sha256(
            b"test simulator"
        ).hexdigest()
        assert kwargs["binary_sha256"] == hashlib.sha256(
            b"test binary"
        ).hexdigest()

        return {
            "status": "benchmark_artifacts_matched",
            "configuration": "sbus_64k",
            "cycles": 1072451,
            "speedup_vs_normal": 0.6654,
            "eligible_for_automatic_ranking": False,
        }

    monkeypatch.setattr(
        evidence_matcher, "match_q3k_artifacts", fake_match
    )

    code, data = run_cli(monkeypatch, tmp_path, args)

    assert code == 0
    ref = data["benchmark_reference"]
    assert ref["cycles"] == 1072451
    assert ref["eligible_for_automatic_ranking"] is False
    assert ref["full_model_inference_measured"] is False


def test_wrong_artifact_hash_rejected(monkeypatch, tmp_path):
    args = artifact_args(tmp_path)

    with pytest.raises(SystemExit) as exc:
        run_cli(monkeypatch, tmp_path, args)

    assert exc.value.code == 2


@pytest.mark.parametrize(
    "extra",
    [
        ["--benchmark-key", "sbus_64k"],
        ["--benchmark-binary", "missing.riscv"],
    ],
)
def test_incomplete_benchmark_flags_rejected(
    monkeypatch, tmp_path, extra
):
    with pytest.raises(SystemExit) as exc:
        run_cli(monkeypatch, tmp_path, extra)

    assert exc.value.code == 2

import json
import sys
from types import SimpleNamespace

import pytest

from optimizer import cli


@pytest.fixture
def sample_result():
    return {
        "status": "heuristic_ranking_complete",
        "model": "TestSLM",
        "board": "arty-a7-100",
        "confidence": "low",
        "recommendation_status": "estimated_not_synthesized",
        "pareto_candidate_count": 3,
        "recommended_exploration_candidate": {
            "configuration": {
                "cores": 1,
                "icache_kib": 16,
                "dcache_kib": 16,
                "scratchpad_kib": 64,
                "l2_enabled": False,
                "target_clock_mhz": 50,
            },
            "resource_estimates": {
                "luts": 42781,
                "flip_flops": 24550,
                "bram36_equivalent": 58,
                "dsp": 24,
            },
            "model_adjusted_throughput_proxy": 1.0,
            "hardware_cost": {
                "resident_model_memory": {
                    "required_mib": 519.51,
                    "available_mib": 256,
                    "fits": False,
                }
            },
        },
    }


def run_mock_cli(
    monkeypatch,
    tmp_path,
    payload,
    *,
    stdout=None,
    returncode=0,
    stderr="",
):
    model = tmp_path / "test.gguf"
    model.write_bytes(b"GGUF")

    report = tmp_path / "result.json"
    captured = {}

    def fake_run(command, **kwargs):
        captured["command"] = command
        return SimpleNamespace(
            returncode=returncode,
            stdout=json.dumps(payload) if stdout is None else stdout,
            stderr=stderr,
        )

    monkeypatch.setattr(cli.subprocess, "run", fake_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "optimizer.cli",
            "--model", str(model),
            "--board", "Arty A7-100T",
            "--objective", "balanced",
            "--external-ram-mib", "256",
            "--past-tokens", "1024",
            "--output", str(report),
        ],
    )

    return cli.main(), report, captured


def test_cli_success(monkeypatch, tmp_path, sample_result, capsys):
    code, report, captured = run_mock_cli(
        monkeypatch, tmp_path, sample_result
    )

    assert code == 0
    assert report.exists()

    saved = json.loads(report.read_text())
    assert saved["status"] == "heuristic_ranking_complete"
    assert saved["pareto_candidate_count"] == 3

    output = capsys.readouterr().out
    assert "RECOMMENDED ROCKET CONFIGURATION" in output
    assert "FULL-CONTEXT MEMORY REFERENCE (ESTIMATED)" in output
    assert "Fits: False" in output

    assert "--past-tokens" in captured["command"]
    assert "--external-ram-mib" in captured["command"]


def test_cli_device_not_installed(
    monkeypatch, tmp_path, capsys
):
    payload = {
        "status": "vivado_device_not_installed",
        "message": "FPGA device support is missing",
    }

    code, report, _ = run_mock_cli(
        monkeypatch, tmp_path, payload
    )

    assert code == 2
    assert report.exists()
    assert "No ranked recommendation" in capsys.readouterr().out


def test_cli_invalid_json(monkeypatch, tmp_path, capsys):
    code, _, _ = run_mock_cli(
        monkeypatch,
        tmp_path,
        {},
        stdout="INVALID JSON",
    )

    assert code == 1
    assert "Invalid optimizer JSON" in capsys.readouterr().err


def test_cli_subprocess_failure(monkeypatch, tmp_path, capsys):
    code, _, _ = run_mock_cli(
        monkeypatch,
        tmp_path,
        {},
        returncode=1,
        stderr="Simulated optimizer failure",
    )

    assert code == 1
    assert "Simulated optimizer failure" in capsys.readouterr().err


@pytest.mark.parametrize(
    "option",
    [
        ["--top", "0"],
        ["--objective", "unsupported"],
    ],
)
def test_cli_rejects_invalid_options(monkeypatch, tmp_path, option):
    model = tmp_path / "test.gguf"
    model.write_bytes(b"GGUF")

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "optimizer.cli",
            "--model", str(model),
            "--board", "Arty A7-100T",
            *option,
        ],
    )

    with pytest.raises(SystemExit) as exc:
        cli.main()

    assert exc.value.code == 2


def test_cli_missing_model(monkeypatch, tmp_path):
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "optimizer.cli",
            "--model", str(tmp_path / "missing.gguf"),
            "--board", "Arty A7-100T",
        ],
    )

    with pytest.raises(SystemExit) as exc:
        cli.main()

    assert exc.value.code == 2

import json
import sys
from types import SimpleNamespace

import pytest

from optimizer import cli


def run_cli(monkeypatch, tmp_path, *options, include_workload=True):
    model = tmp_path / "test.gguf"
    model.write_bytes(b"GGUF")
    report = tmp_path / "report.json"

    payload = {
        "status": "heuristic_ranking_complete",
        "model": "TestSLM",
        "board": "arty-a7-100",
        "confidence": "low",
        "recommendation_status": "estimated_not_synthesized",
        "pareto_candidate_count": 1,
        "recommended_exploration_candidate": {
            "configuration": {
                "cores": 1,
                "icache_kib": 16,
                "dcache_kib": 16,
                "scratchpad_kib": 0,
                "l2_enabled": False,
                "target_clock_mhz": 50,
            },
            "resource_estimates": {
                "luts": 42106,
                "flip_flops": 24392,
                "bram36_equivalent": 40,
                "dsp": 24,
            },
            "model_adjusted_throughput_proxy": 1.0,
        },
    }

    if include_workload:
        payload["workload_profile"] = {
            "parameters": 1_000_000_000,
            "context_length": 1024,
            "weights_mib": 100.0,
            "illustrative_f16_kv_cache_mib": 16.0,
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
            *options,
        ],
    )

    assert cli.main() == 0
    return json.loads(report.read_text())


def test_roofline_integration(monkeypatch, tmp_path):
    data = run_cli(
        monkeypatch,
        tmp_path,
        "--past-tokens", "128",
        "--assumed-bandwidth-mib-s", "100",
        "--assumed-compute-gops-s", "10",
    )

    perf = data["decode_performance_envelope"]

    assert perf["status"] == "conditional_roofline_estimate"
    assert perf["bottleneck"] == "memory"
    assert perf["idealized_tokens_per_second"] > 0
    assert perf["candidate_specific"] is False
    assert perf["eligible_for_automatic_ranking"] is False

    assert (
        data["recommended_exploration_candidate"]
        ["configuration"]["scratchpad_kib"]
    ) == 0


def test_partial_hardware_information(monkeypatch, tmp_path):
    data = run_cli(
        monkeypatch,
        tmp_path,
        "--assumed-bandwidth-mib-s", "100",
    )

    perf = data["decode_performance_envelope"]

    assert perf["status"] == "hardware_rates_incomplete"
    assert perf["idealized_tokens_per_second"] is None


def test_default_without_rates(monkeypatch, tmp_path):
    data = run_cli(monkeypatch, tmp_path)

    assert "decode_performance_envelope" not in data


def test_missing_workload(monkeypatch, tmp_path):
    data = run_cli(
        monkeypatch,
        tmp_path,
        "--assumed-compute-gops-s", "5",
        include_workload=False,
    )

    assert (
        data["decode_performance_envelope"]["status"]
        == "unavailable"
    )


@pytest.mark.parametrize(
    "option",
    [
        "--assumed-bandwidth-mib-s",
        "--assumed-compute-gops-s",
    ],
)
@pytest.mark.parametrize(
    "value",
    ["0", "-1", "nan", "inf"],
)
def test_invalid_hardware_rates(
    monkeypatch, tmp_path, option, value
):
    with pytest.raises(SystemExit) as exc:
        run_cli(monkeypatch, tmp_path, option, value)

    assert exc.value.code == 2

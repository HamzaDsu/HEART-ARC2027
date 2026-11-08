import json
import sys
from types import SimpleNamespace

import pytest

from optimizer import cli


def test_cli_runtime_memory_analysis(monkeypatch, tmp_path):
    model = tmp_path / "test.gguf"
    model.write_bytes(b"test")

    report = tmp_path / "runtime.json"

    fake_result = {
        "status": "heuristic_ranking_complete",
        "model": "SmolLM2",
        "board": "arty-a7-100",
        "confidence": "low",
        "recommendation_status": "estimated_not_synthesized",
        "pareto_candidate_count": 1,
        "workload_profile": {
            "context_length": 8192,
            "weights_mib": 87.48,
            "illustrative_f16_kv_cache_mib": 180.0,
        },
        "recommended_exploration_candidate": {
            "configuration": {
                "cores": 1,
                "icache_kib": 16,
                "dcache_kib": 16,
                "scratchpad_kib": 64,
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
            stdout=json.dumps(fake_result),
            stderr="",
        ),
    )

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "optimizer.cli",
            "--model", str(model),
            "--board", "Arty A7-100T",
            "--external-ram-mib", "256",
            "--runtime-context-length", "1024",
            "--runtime-overhead-mib", "64",
            "--output", str(report),
        ],
    )

    assert cli.main() == 0

    result = json.loads(report.read_text())
    memory = result["runtime_memory_analysis"]

    assert memory["estimated_required_mib"] == pytest.approx(173.98)
    assert memory["estimated_headroom_mib"] == pytest.approx(82.02)
    assert memory["fits_estimated_memory"] is True


@pytest.mark.parametrize(
    "arguments",
    [
        ["--runtime-context-length", "0", "--external-ram-mib", "256"],
        ["--runtime-context-length", "-1", "--external-ram-mib", "256"],
        ["--runtime-overhead-mib", "-5"],
        ["--runtime-overhead-mib", "64"],
        ["--runtime-context-length", "1024"],
    ],
)
def test_cli_rejects_invalid_runtime_options(
    monkeypatch, tmp_path, arguments
):
    model = tmp_path / "test.gguf"
    model.write_bytes(b"test")

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "optimizer.cli",
            "--model", str(model),
            "--board", "Arty A7-100T",
            *arguments,
        ],
    )

    with pytest.raises(SystemExit) as exc:
        cli.main()

    assert exc.value.code == 2

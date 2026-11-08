import json
import sys
from types import SimpleNamespace

import pytest

from optimizer import cli


FPGA = "xc7a100tcsg324-1"

CONFIG = {
    "cores": 1,
    "icache_kib": 16,
    "dcache_kib": 16,
    "scratchpad_kib": 0,
    "l2_enabled": False,
    "target_clock_mhz": 50,
}


def profile():
    return {
        "fpga_part": FPGA,
        "configuration": CONFIG.copy(),
        "provenance": "user_assumption",
        "bandwidth_mib_s": 512,
        "compute_gops_s": 5,
    }


def run_case(monkeypatch, tmp_path, profile_text=None):
    model = tmp_path / "model.gguf"
    model.write_bytes(b"GGUF")
    output = tmp_path / "report.json"

    # Include metadata present in real optimizer candidates.
    candidate = {
        **CONFIG,
        "validation": "not_synthesized",
    }

    payload = {
        "status": "heuristic_ranking_complete",
        "model": "TestSLM",
        "board": "arty-a7-100",
        "confidence": "low",
        "recommendation_status": "estimated_not_synthesized",
        "pareto_candidate_count": 1,
        "reference_selection": {"target_part": FPGA},
        "workload_profile": {
            "parameters": 1_000_000_000,
            "context_length": 1024,
            "weights_mib": 100.0,
            "illustrative_f16_kv_cache_mib": 16.0,
        },
        "recommended_exploration_candidate": {
            "configuration": candidate,
            "resource_estimates": {
                "luts": 42106,
                "flip_flops": 24392,
                "bram36_equivalent": 40,
                "dsp": 24,
            },
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

    args = [
        "optimizer.cli",
        "--model", str(model),
        "--board", "arty-a7-100",
        "--scope", "kernel",
        "--past-tokens", "128",
        "--output", str(output),
    ]

    if profile_text is not None:
        profile_path = tmp_path / "profile.json"
        profile_path.write_text(profile_text)
        args.extend([
            "--candidate-rate-profile", str(profile_path),
        ])

    monkeypatch.setattr(sys, "argv", args)

    code = cli.main()

    if code != 0:
        raise AssertionError(f"Unexpected CLI result: {code}")

    return json.loads(output.read_text())


def test_matching_candidate_profile(monkeypatch, tmp_path):
    data = run_case(
        monkeypatch, tmp_path, json.dumps(profile())
    )

    result = data["candidate_performance_assessment"]

    assert result["status"] == "conditional_roofline_estimate"
    assert result["candidate_specific"] is True
    assert result["idealized_tokens_per_second"] > 0
    assert result["eligible_for_automatic_ranking"] is False
    assert result["hardware_execution_measured"] is False

    assert (
        data["recommended_exploration_candidate"]["configuration"]
        ["scratchpad_kib"]
    ) == 0


@pytest.mark.parametrize("mismatch", ["scratchpad", "fpga"])
def test_mismatching_profile_rejected(
    monkeypatch, tmp_path, mismatch
):
    item = profile()

    if mismatch == "scratchpad":
        item["configuration"]["scratchpad_kib"] = 64
    else:
        item["fpga_part"] = "different-fpga"

    data = run_case(
        monkeypatch, tmp_path, json.dumps(item)
    )

    result = data["candidate_performance_assessment"]

    assert result["status"] == "rate_profile_mismatch"
    assert result["idealized_tokens_per_second"] is None
    assert result["eligible_for_automatic_ranking"] is False


def test_without_profile_preserves_existing_behavior(
    monkeypatch, tmp_path
):
    data = run_case(monkeypatch, tmp_path)

    assert "candidate_performance_assessment" not in data


def test_invalid_json_rejected(monkeypatch, tmp_path):
    with pytest.raises(SystemExit) as error:
        run_case(monkeypatch, tmp_path, "{invalid-json")

    assert error.value.code == 2


@pytest.mark.parametrize(
    "field,value",
    [
        ("bandwidth_mib_s", 0),
        ("compute_gops_s", -1),
    ],
)
def test_invalid_rates_rejected(
    monkeypatch, tmp_path, field, value
):
    item = profile()
    item[field] = value

    with pytest.raises(SystemExit) as error:
        run_case(
            monkeypatch, tmp_path, json.dumps(item)
        )

    assert error.value.code == 2

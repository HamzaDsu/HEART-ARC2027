import pytest

from optimizer.optimization import candidates


def setup_mocks(monkeypatch, memory_status, runtime_fits=True):
    analysis = {
        "model": {"name": "TestSLM"},
        "fpga": {
            "board": "arty-a7-100",
            "resources": {"bram36_sites": 270},
        },
        "memory_assessment": {"status": memory_status},
    }

    monkeypatch.setattr(
        candidates,
        "check_feasibility",
        lambda *args, **kwargs: analysis,
    )

    monkeypatch.setattr(
        candidates,
        "build_workload_profile",
        lambda *args, **kwargs: {"context_length": 2048},
    )

    monkeypatch.setattr(
        candidates,
        "estimate_runtime_memory",
        lambda *args, **kwargs: {
            "fits_estimated_memory": runtime_fits,
            "estimated_required_mib": 174 if runtime_fits else 500,
        },
    )


def run_generation(**kwargs):
    return candidates.generate_candidates(
        "test.gguf",
        "Arty A7-100T",
        ram_mib=256,
        scope="full-model",
        **kwargs,
    )


def test_runtime_fit_allows_candidates(monkeypatch):
    setup_mocks(
        monkeypatch,
        "fp16_full_context_kv_does_not_fit",
        runtime_fits=True,
    )

    result = run_generation(runtime_context_length=1024)

    assert result["status"] == "candidate_generation_complete"
    assert result["candidate_count"] > 0


def test_runtime_memory_overflow_blocks_candidates(monkeypatch):
    setup_mocks(
        monkeypatch,
        "fp16_full_context_kv_does_not_fit",
        runtime_fits=False,
    )

    result = run_generation(runtime_context_length=1024)

    assert result["status"] == "resident_model_memory_blocked"
    assert result["candidates"] == []


def test_runtime_context_requires_ram(monkeypatch):
    setup_mocks(
        monkeypatch,
        "weights_may_fit_runtime_unverified",
    )

    result = candidates.generate_candidates(
        "test.gguf",
        "Arty A7-100T",
        ram_mib=None,
        scope="full-model",
        runtime_context_length=1024,
    )

    assert result["status"] == "runtime_memory_requires_external_ram"


def test_legacy_behavior_preserved(monkeypatch):
    setup_mocks(
        monkeypatch,
        "weights_may_fit_runtime_unverified",
    )

    result = run_generation()

    assert result["status"] == "candidate_generation_complete"
    assert result["candidate_count"] > 0


def test_legacy_memory_failure_still_blocks(monkeypatch):
    setup_mocks(
        monkeypatch,
        "fp16_full_context_kv_does_not_fit",
    )

    result = run_generation()

    assert result["status"] == "resident_model_memory_blocked"

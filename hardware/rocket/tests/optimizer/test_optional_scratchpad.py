
from optimizer.optimization import candidates
from optimizer.optimization.model_aware import model_fit


def test_zero_scratchpad_is_a_candidate(monkeypatch):
    analysis = {
        "memory_assessment": {
            "status": "weights_may_fit_runtime_unverified"
        },
        "fpga": {
            "resources": {"bram36_sites": 64},
            "board": "mock-board"
        },
        "model": {"name": "mock-model"}
    }

    monkeypatch.setattr(
        candidates,
        "check_feasibility",
        lambda *args, **kwargs: analysis
    )

    result = candidates.generate_candidates(
        "unused.gguf", "mock-board", scope="kernel"
    )

    assert result["status"] == "candidate_generation_complete"

    sizes = {
        c["scratchpad_kib"] for c in result["candidates"]
    }

    assert {0, 64, 128, 256}.issubset(sizes)


def test_scratchpad_has_no_unverified_speed_bonus():
    workload = {
        "embedding_size": 2048,
        "average_stored_bits_per_parameter": 3.0
    }

    baseline = {
        "dcache_kib": 16,
        "scratchpad_kib": 0
    }

    scratchpad = {
        "dcache_kib": 16,
        "scratchpad_kib": 64
    }

    result_zero = model_fit(baseline, workload)
    result_64 = model_fit(scratchpad, workload)

    assert result_zero["factor"] == result_64["factor"]
    assert result_zero["scratchpad_coverage"] == 0
    assert result_64["scratchpad_coverage"] > 0

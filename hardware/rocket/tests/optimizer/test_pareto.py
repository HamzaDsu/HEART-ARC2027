import pytest

from optimizer.optimization.pareto import pareto_front
from optimizer.optimization.recommender import rank_pareto


def make_candidate(name, score, luts, bram):
    return {
        "name": name,
        "score": score,
        "resource_estimates": {
            "luts": luts,
            "bram36_equivalent": bram,
        },
    }


@pytest.fixture
def candidates():
    return [
        make_candidate("performance", 1.015, 46203, 65),
        make_candidate("balanced", 1.000, 42781, 58),
        make_candidate("resources", 0.9925, 41070, 54),
        make_candidate("dominated", 0.980, 47000, 75),
    ]


def test_pareto_removes_dominated_candidate(candidates):
    front = pareto_front(candidates)

    assert len(front) == 3
    assert {item["name"] for item in front} == {
        "performance",
        "balanced",
        "resources",
    }


@pytest.mark.parametrize(
    "objective,expected",
    [
        ("performance", "performance"),
        ("resources", "resources"),
        ("balanced", "balanced"),
    ],
)
def test_objective_selection(candidates, objective, expected):
    front = pareto_front(candidates)

    limits = {
        "luts": 63400,
        "bram36_sites": 135,
    }

    ranked = rank_pareto(front, objective, limits)

    assert ranked[0]["name"] == expected


def test_all_selected_candidates_are_pareto(candidates):
    front = pareto_front(candidates)
    names = {item["name"] for item in front}

    for objective in ["performance", "resources", "balanced"]:
        ranked = rank_pareto(
            [dict(item) for item in front],
            objective,
            {"luts": 63400, "bram36_sites": 135},
        )
        assert ranked[0]["name"] in names

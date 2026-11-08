"""Experimental RTL tile evidence for V5 optimizer evaluation.

Measurements are 128-output first-layer Rocket RTL tiles.
They are not whole-model latency measurements.
V5 FPGA configuration equivalence remains unverified.
"""

import csv
import json
from collections import defaultdict
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CSV = (
    ROOT / "results/optimizer/measurements/"
    "four_model_v5_huge_rtl_tile_cache_matrix_20261029.csv"
)

MODELS = ("resnet18", "vgg11", "swint", "levit384")
CACHES = (8, 16, 32)


@lru_cache(maxsize=1)
def load_measurements():
    with CSV.open(newline="") as stream:
        rows = list(csv.DictReader(stream))

    assert len(rows) == 12, "Expected 12 RTL measurements"

    measurements = {}
    hashes = {}
    executables = {}

    for row in rows:
        model = row["model"]
        cache = int(row["cache_kib"])
        cycles = int(row["kernel_cycles"])

        assert model in MODELS
        assert cache in CACHES
        assert cycles > 0
        assert int(row["correct_outputs"]) == 128

        key = (model, cache)
        assert key not in measurements

        measurements[key] = cycles

        output_hash = row["output_hash"]
        elf_hash = row["riscv_elf_sha256"]

        if model in hashes:
            assert hashes[model] == output_hash
            assert executables[model] == elf_hash
        else:
            hashes[model] = output_hash
            executables[model] = elf_hash

    assert set(measurements) == {
        (model, cache)
        for model in MODELS
        for cache in CACHES
    }

    return measurements


def measured_speedup(model, cache_kib):
    measurements = load_measurements()

    baseline = measurements[(model, 8)]
    candidate = measurements[(model, cache_kib)]

    return baseline / candidate


def eligible_configuration(cfg):
    return (
        cfg.get("cores") == 1
        and cfg.get("scratchpad_kib") == 0
        and cfg.get("l2_enabled") is False
        and cfg.get("icache_kib") == cfg.get("dcache_kib")
        and cfg.get("dcache_kib") in CACHES
    )


def attach_rtl_preview(model, candidates):
    """Attach experimental scores without changing selection scores."""

    if model not in MODELS:
        return candidates

    groups = defaultdict(dict)

    for item in candidates:
        cfg = item["configuration"]

        if not eligible_configuration(cfg):
            continue

        # Same candidate configuration apart from L1 cache sizes.
        key = json.dumps(
            {
                k: v
                for k, v in cfg.items()
                if k not in ("icache_kib", "dcache_kib")
            },
            sort_keys=True,
        )

        cache = cfg["dcache_kib"]
        groups[key][cache] = item

    for group in groups.values():
        anchor = group.get(8)

        for cache, item in group.items():
            observed_speedup = measured_speedup(model, cache)

            evidence = {
                "measurement": "128_output_first_layer_rtl_tile",
                "source": str(CSV.relative_to(ROOT)),
                "cache_kib": cache,
                "observed_speedup_vs_8k": round(
                    observed_speedup, 8
                ),
                "v5_fpga_equivalence_verified": False,
                "used_for_selection": False,
            }

            # Full-model heuristic scores and measured tile
            # speedups are not interchangeable quantities.
            evidence["observed_tile_cycles"] = (
                load_measurements()[(model, cache)]
            )
            evidence["preview_score"] = None
            evidence["score_mapping_validated"] = False

            if anchor is not None and anchor["score"] > 0:
                evidence["original_heuristic_ratio_vs_8k"] = round(
                    item["score"] / anchor["score"], 8
                )
            else:
                evidence["prediction_status"] = (
                    "no_comparable_8k_anchor"
                )

            item["rtl_tile_evidence"] = evidence

    return candidates

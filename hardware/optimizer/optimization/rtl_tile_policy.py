"""Experimental resource-aware RTL tile cache policy.

This is an offline configuration-selection experiment.
It is not a full-model performance predictor.
"""

from optimizer.optimization.rtl_tile_calibration import (
    MODELS,
    load_measurements,
)

RESOURCE_KEYS = (
    "luts",
    "flip_flops",
    "bram36_equivalent",
    "dsp",
)


def evaluate_policy(model, resources16, resources32, tolerance):
    if model not in MODELS:
        raise ValueError(f"Unknown model: {model}")

    if tolerance < 0:
        raise ValueError("Tolerance cannot be negative")

    measurements = load_measurements()

    cycles16 = measurements[(model, 16)]
    cycles32 = measurements[(model, 32)]

    slowdown = 100 * (cycles16 / cycles32 - 1)

    saves_resources = (
        all(
            resources16[k] <= resources32[k]
            for k in RESOURCE_KEYS
        )
        and any(
            resources16[k] < resources32[k]
            for k in RESOURCE_KEYS
        )
    )

    selected = (
        16 if saves_resources and slowdown <= tolerance
        else 32
    )

    return {
        "model": model,
        "selected_cache_kib": selected,
        "tolerance_percent": tolerance,
        "slowdown_16_vs_32_percent": round(slowdown, 6),
        "resources16": resources16,
        "resources32": resources32,
        "resources_saved_by_16": {
            k: resources32[k] - resources16[k]
            for k in RESOURCE_KEYS
        },
        "source": "checkpoint_derived_first_layer_Rocket_RTL",
        "selection_status": "experimental_not_enabled",
    }

"""Regression tests for experimental V5 RTL cache calibration."""

import unittest

from optimizer.optimization.rtl_tile_calibration import (
    MODELS,
    attach_rtl_preview,
    load_measurements,
    measured_speedup,
)
from optimizer.optimization.rtl_tile_policy import (
    evaluate_policy,
)

R16 = {
    "luts": 42273,
    "flip_flops": 23210,
    "bram36_equivalent": 40.0,
    "dsp": 25,
}

R32 = {
    "luts": 42431,
    "flip_flops": 23473,
    "bram36_equivalent": 48.0,
    "dsp": 25,
}


class TestV5RTLPolicy(unittest.TestCase):

    def test_twelve_measurements_loaded(self):
        measurements = load_measurements()
        self.assertEqual(len(measurements), 12)

        for model in MODELS:
            for cache in (8, 16, 32):
                self.assertGreater(
                    measurements[(model, cache)], 0
                )

    def test_model_specific_speedups(self):
        self.assertEqual(
            measured_speedup("vgg11", 16),
            measured_speedup("vgg11", 32),
        )

        self.assertGreater(
            measured_speedup("swint", 32),
            measured_speedup("swint", 16),
        )

    def test_strict_tolerance(self):
        expected = {
            "resnet18": 16,
            "vgg11": 16,
            "swint": 32,
            "levit384": 16,
        }

        for model, chosen in expected.items():
            with self.subTest(model=model):
                result = evaluate_policy(
                    model, R16, R32, 0.1
                )
                self.assertEqual(
                    result["selected_cache_kib"], chosen
                )
                self.assertEqual(
                    result["selection_status"],
                    "experimental_not_enabled",
                )

    def test_relaxed_tolerance(self):
        for model in MODELS:
            with self.subTest(model=model):
                result = evaluate_policy(
                    model, R16, R32, 1.0
                )
                self.assertEqual(
                    result["selected_cache_kib"], 16
                )

                self.assertEqual(
                    result["resources_saved_by_16"][
                        "bram36_equivalent"
                    ],
                    8.0,
                )

    def test_no_savings_keeps_32k(self):
        result = evaluate_policy(
            "resnet18", R32, R32, 1.0
        )
        self.assertEqual(
            result["selected_cache_kib"], 32
        )

    def test_preview_does_not_change_score(self):
        for model in MODELS:
            with self.subTest(model=model):
                candidates = []

                for cache in (8, 16, 32):
                    candidates.append({
                        "configuration": {
                            "cores": 1,
                            "icache_kib": cache,
                            "dcache_kib": cache,
                            "scratchpad_kib": 0,
                            "l2_enabled": False,
                            "target_clock_mhz": 50,
                        },
                        "score": float(cache),
                    })

                attach_rtl_preview(model, candidates)

                for candidate in candidates:
                    cache = candidate[
                        "configuration"
                    ]["dcache_kib"]

                    self.assertEqual(
                        candidate["score"], float(cache)
                    )

                    evidence = candidate[
                        "rtl_tile_evidence"
                    ]

                    self.assertFalse(
                        evidence["used_for_selection"]
                    )

                    self.assertFalse(
                        evidence["score_mapping_validated"]
                    )

                    self.assertIsNone(
                        evidence["preview_score"]
                    )

    def test_invalid_tolerance_rejected(self):
        with self.assertRaises(ValueError):
            evaluate_policy(
                "resnet18", R16, R32, -1
            )


if __name__ == "__main__":
    unittest.main()

import unittest

from optimizer.optimization.rtl_guided_selection import (
    select_rtl_guided,
)


def candidate(cache, cost, clock=50, cores=1):
    return {
        "configuration": {
            "cores": cores,
            "icache_kib": cache,
            "dcache_kib": cache,
            "scratchpad_kib": 0,
            "l2_enabled": False,
            "target_clock_mhz": clock,
        },
        "score": float(cache),
        "resource_cost": float(cost),
    }


class TestRTLGuidedSelection(unittest.TestCase):

    def setUp(self):
        self.c32 = candidate(32, 0.40)
        self.c16 = candidate(16, 0.30)

    def test_four_models(self):
        expected = {
            "resnet18": 16,
            "vgg11": 16,
            "swint": 32,
            "levit384": 16,
        }

        for model, cache in expected.items():
            with self.subTest(model=model):
                ranked, decision = select_rtl_guided(
                    model,
                    [self.c32],
                    [self.c32, self.c16],
                    0.1,
                )

                self.assertEqual(
                    ranked[0]["configuration"]["dcache_kib"],
                    cache,
                )

    def test_reject_clock_mismatch(self):
        c16_25 = candidate(16, 0.30, clock=25)

        ranked, decision = select_rtl_guided(
            "resnet18",
            [self.c32],
            [self.c32, c16_25],
        )

        self.assertEqual(ranked[0], self.c32)
        self.assertEqual(
            decision["reason"],
            "no_matching_16k_candidate",
        )

    def test_requires_resource_saving(self):
        expensive = candidate(16, 0.50)

        ranked, decision = select_rtl_guided(
            "resnet18",
            [self.c32],
            [self.c32, expensive],
        )

        self.assertEqual(ranked[0], self.c32)
        self.assertEqual(
            decision["reason"],
            "no_estimated_resource_saving",
        )

    def test_two_core_not_modified(self):
        two_core = candidate(32, 0.40, cores=2)

        ranked, decision = select_rtl_guided(
            "resnet18",
            [two_core],
            [two_core, self.c16],
        )

        self.assertEqual(ranked[0], two_core)
        self.assertEqual(decision["status"], "not_applied")

    def test_evidence_limitations(self):
        ranked, decision = select_rtl_guided(
            "resnet18",
            [self.c32],
            [self.c32, self.c16],
        )

        self.assertFalse(
            decision["full_model_latency_validated"]
        )
        self.assertFalse(
            decision["physical_fpga_validated"]
        )

    def test_invalid_tolerance(self):
        for value in (-1.0, float("nan"), float("inf")):
            with self.assertRaises(ValueError):
                select_rtl_guided(
                    "resnet18",
                    [self.c32],
                    [self.c32, self.c16],
                    value,
                )


if __name__ == "__main__":
    unittest.main()

import csv
import json
import unittest
from pathlib import Path

from optimizer.optimization.vision_workload_recommender import (
    evaluate, MODELS, PROFILES, RESULTS, CONFIGS
)
from optimizer.optimization.pareto import pareto_front, dominates


def load(path):
    return json.loads(path.read_text())


class ResearchEvidenceTests(unittest.TestCase):

    def test_fpga_resources(self):
        baseline = load(RESULTS / "measurements/nexysvideo_baseline.json")
        optimized = load(RESULTS / "measurements/nexysvideo_optimized.json")

        self.assertEqual(
            baseline["fpga_part"],
            "xc7a200tsbg484-1"
        )
        self.assertEqual(
            baseline["configuration"]["dcache_kib"], 32
        )
        self.assertEqual(
            optimized["configuration"]["dcache_kib"], 16
        )
        self.assertEqual(
            baseline["configuration"]["scratchpad_kib"], 64
        )
        self.assertEqual(
            optimized["configuration"]["scratchpad_kib"], 0
        )

        b = baseline["measured_resources"]
        o = optimized["measured_resources"]

        self.assertEqual(b["luts"], 42836)
        self.assertEqual(o["luts"], 42273)
        self.assertEqual(b["bram36_equivalent"], 64)
        self.assertEqual(o["bram36_equivalent"], 40)

        reduction = 100 * (
            1 - o["bram36_equivalent"] / b["bram36_equivalent"]
        )
        self.assertAlmostEqual(reduction, 37.5)

    def test_cache_sensitivity(self):
        data = load(
            RESULTS / "measurements/rocket_rtl_cache_sensitivity.json"
        )

        expected = {
            8:  (72426, 74976),
            16: (119356, 137936),
            24: (178426, 274766),
            32: (249676, 386396),
            48: (519746, 619516),
        }

        rows = data["results"]
        self.assertEqual(len(rows), 5)

        for row in rows:
            size = row["working_set_kib"]

            self.assertIn(size, expected)
            self.assertTrue(row["checksum_verified"])
            self.assertEqual(
                (row["cycles_32k"], row["cycles_16k"]),
                expected[size]
            )

            difference = row["cycles_16k"] - row["cycles_32k"]
            percent = 100 * difference / row["cycles_32k"]

            self.assertAlmostEqual(
                percent,
                row["cycle_change_percent"],
                places=3
            )

    def test_int8_kernel_measurements(self):
        data = load(
            RESULTS /
            "measurements/rocket_rtl_int8_small_kernel_results.json"
        )

        expected = {
            "gemm": (261016, 261736),
            "attention": (152906, 153326),
            "convolution": (179016, 178996),
        }

        self.assertEqual(len(data["results"]), 3)

        for row in data["results"]:
            self.assertIn(row["kernel"], expected)
            self.assertTrue(row["checksum_verified"])
            self.assertEqual(
                (row["cycles_32k"], row["cycles_16k"]),
                expected[row["kernel"]]
            )

    def test_model_profiles(self):
        csv_path = (
            RESULTS / "measurements/four_model_workload_summary.csv"
        )

        with csv_path.open(newline="") as f:
            rows = list(csv.DictReader(f))

        self.assertEqual(len(rows), 4)
        self.assertEqual(
            {r["model"] for r in rows},
            set(MODELS)
        )

        for row in rows:
            name = row["model"]
            profile = load(
                RESULTS / "combined_workload_profiles" / f"{name}.json"
            )

            self.assertEqual(profile["model_name"], name)
            self.assertAlmostEqual(
                float(row["accounted_gmacs"]),
                profile["accounted_gmacs"]
            )

    def test_pareto_consistency(self):
        saved = load(
            RESULTS / "four_model_pareto_validation.json"
        )

        references = {
            name: load(RESULTS / "measurements" / filename)
            for name, filename in CONFIGS.items()
        }

        for model in MODELS:
            profile = load(PROFILES / f"{model}.json")
            execution = load(
                RESULTS / "integer_execution_profiles" /
                f"{model}.json"
            )

            candidates = {
                name: evaluate(profile, execution, ref, name)
                for name, ref in references.items()
            }

            baseline = candidates["baseline"]
            optimized = candidates["optimized"]

            self.assertGreaterEqual(
                baseline["score"], optimized["score"]
            )

            self.assertFalse(dominates(baseline, optimized))
            self.assertFalse(dominates(optimized, baseline))

            front = pareto_front(list(candidates.values()))
            names = {x["name"] for x in front}

            self.assertEqual(names, {"baseline", "optimized"})

            recorded = saved["models"][model]

            self.assertEqual(
                set(recorded["pareto_front"]), names
            )

            for name in candidates:
                self.assertEqual(
                    recorded["candidates"][name]["score"],
                    candidates[name]["score"]
                )

    def test_research_reports(self):
        required = [
            "MODEL_WORKLOAD_AND_RTL_EVIDENCE.md",
            "FOUR_MODEL_PARETO_VALIDATION.md",
            "figures/rocket_cache_cycles.png",
            "figures/rocket_cache_cycle_overhead.png",
        ]

        for filename in required:
            path = RESULTS / filename
            self.assertTrue(path.is_file(), str(path))
            self.assertGreater(path.stat().st_size, 0)


if __name__ == "__main__":
    unittest.main()

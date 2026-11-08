"""Regression tests for GGUF C automation reporting."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(
    0, str(Path(__file__).resolve().parents[1])
)

from automate_models import (
    elapsed_seconds,
    parse_metric,
    validate_model,
)

ELAPSED_PATTERN = (
    r"^\s*Elapsed \(wall clock\) time "
    r"\([^)]*\):\s*(\S+)\s*$"
)


class TestAutomation(unittest.TestCase):

    def test_elapsed_time_from_real_log_format(self):
        log = (
            "Elapsed (wall clock) time "
            "(h:mm:ss or m:ss): 0:06.24"
        )

        value = parse_metric(ELAPSED_PATTERN, log)

        self.assertEqual(value, "0:06.24")
        self.assertEqual(elapsed_seconds(value), 6.24)

    def test_long_elapsed_time(self):
        self.assertEqual(
            elapsed_seconds("1:02:03.45"),
            3723.45,
        )

    def test_memory_parsing(self):
        log = (
            "Maximum resident set size "
            "(kbytes): 528224"
        )

        value = parse_metric(
            r"Maximum resident set size "
            r"\(kbytes\):\s*(\d+)",
            log,
        )

        self.assertEqual(value, "528224")

    def test_valid_gguf(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = Path(tmp) / "test.gguf"
            model.write_bytes(b"GGUF")

            validate_model(model)

    def test_invalid_gguf_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = Path(tmp) / "invalid.gguf"
            model.write_bytes(b"INVALID")

            with self.assertRaises(ValueError):
                validate_model(model)

    def test_missing_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = Path(tmp) / "missing.gguf"

            with self.assertRaises(ValueError):
                validate_model(model)


if __name__ == "__main__":
    unittest.main()

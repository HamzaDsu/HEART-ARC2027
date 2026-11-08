"""Standard-library tests; do not load model dependencies."""
from __future__ import annotations
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from heart_paths import workspace_root, expand_vars, reserve_new_output, validate_new_output
from init_workspace import initialize


class WorkspaceSafetyTests(unittest.TestCase):
    def test_new_workspace_and_guard(self):
        with tempfile.TemporaryDirectory() as base:
            root = Path(base) / 'run'
            initialize(root)
            with patch.dict(os.environ, {'HEART_WORK_DIR':str(root)}):
                self.assertEqual(workspace_root(), root.resolve())
                self.assertEqual(expand_vars('${HEART_WORK_DIR}/runs/x'), str(root.resolve()/'runs/x'))
                self.assertEqual(reserve_new_output(root/'runs'/'check'), root/'runs'/'check')
                with self.assertRaises(FileExistsError):
                    reserve_new_output(root/'runs'/'check')
                with self.assertRaises(RuntimeError):
                    validate_new_output(Path(base)/'outside')
                with self.assertRaises(RuntimeError):
                    validate_new_output(root)
                with self.assertRaises(FileExistsError):
                    initialize(root)

    def test_not_initialized(self):
        with tempfile.TemporaryDirectory() as base:
            with patch.dict(os.environ, {'HEART_WORK_DIR':base}):
                with self.assertRaises(RuntimeError):
                    workspace_root()


if __name__ == '__main__':
    unittest.main()

class ConfigResolutionTests(unittest.TestCase):
    def test_yaml_placeholders(self):
        import yaml
        from heart_paths import load_resolved_config
        with tempfile.TemporaryDirectory() as base:
            root=Path(base)/'new'
            initialize(root)
            yml=root/'cfg.yaml'
            yml.write_text('model_name: TinyLlama/TinyLlama-1.1B-Chat-v1.0\noutput_dir: ${HEART_WORK_DIR}/runs/x\ntraining:\n  num_train_epochs: 3\n')
            with patch.dict(os.environ, {'HEART_WORK_DIR':str(root)}):
                cfg=load_resolved_config(yml)
                self.assertEqual(cfg['output_dir'],str(root/'runs/x'))
                self.assertEqual(cfg['training']['num_train_epochs'],3)

#!/usr/bin/env python3
"""Read-only preflight for HEART training/merging/pruning stages (no CUDA required)."""
from __future__ import annotations

import argparse
import os
from pathlib import Path

from heart_paths import load_resolved_config, require_model, validate_new_output, workspace_root

SPECS = {
    'pubmed': 'tinyllama_pubmed_lora_scratch.yaml',
    'health': 'health_coaching_lora_v4_3ep.yaml',
    'pruning': 'mix_match_pruning_v4.yaml',
}


def check_stage(stage: str, config_dir: Path) -> int:
    root = workspace_root()
    print(f'Workspace marker verified: {root}')
    cfg = load_resolved_config(config_dir / SPECS[stage])
    problems = []
    for key in ('train_file', 'eval_file', 'data_file'):
        if key in cfg:
            p = Path(cfg[key])
            print(f'  {key}: {p} [{"FOUND" if p.is_file() else "MISSING"}]')
            if not p.is_file():
                problems.append(f'Missing {key}: {p}')
    for key in ('model_name', 'model'):
        if key in cfg:
            print(f'  {key}: {cfg[key]}')
            try:
                require_model(cfg[key])
            except FileNotFoundError as exc:
                problems.append(str(exc))
    try:
        validate_new_output(cfg['output_dir'])
        print(f'  output_dir: {cfg["output_dir"]} [FRESH]')
    except (RuntimeError, FileExistsError) as exc:
        problems.append(str(exc))
    if problems:
        for issue in problems:
            print(f'NOT READY: {issue}')
        return 1
    print('PREFLIGHT PASSED (paths only; GPU/dependencies/model outputs not tested)')
    return 0


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', choices=sorted(SPECS), required=True)
    args=parser.parse_args()
    raise SystemExit(check_stage(args.stage, Path(__file__).resolve().parents[1]/'configs'))

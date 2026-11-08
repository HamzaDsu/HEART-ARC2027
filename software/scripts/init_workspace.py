#!/usr/bin/env python3
"""Initialize a NEW, empty, opt-in HEART reproduction workspace."""
from __future__ import annotations
import argparse
from pathlib import Path
from heart_paths import MARKER, MARKER_CONTENT


def initialize(root: Path) -> Path:
    if root.is_symlink():
        raise ValueError('Workspace may not be a symlink')
    resolved = root.expanduser().resolve()
    if resolved in {Path('/'), Path.home().resolve(), Path('/tmp').resolve()}:
        raise ValueError(f'Unsafe workspace root: {resolved}')
    if resolved.exists() and (not resolved.is_dir() or any(resolved.iterdir())):
        raise FileExistsError(f'Workspace must be NEW or empty: {resolved}')
    resolved.mkdir(parents=True, exist_ok=True)
    (resolved / MARKER).write_text(MARKER_CONTENT, encoding='utf-8')
    for name in ('data/processed', 'data/final_health_coaching_v4/processed', 'runs', 'logs', 'evaluations'):
        (resolved / name).mkdir(parents=True, exist_ok=True)
    print(f'Initialized HEART workspace: {resolved}')
    return resolved


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True, help='A NEW empty scratch directory')
    initialize(parser.parse_args().root)

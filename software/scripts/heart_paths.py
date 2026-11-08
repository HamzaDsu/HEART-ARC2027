#!/usr/bin/env python3
"""Non-destructive workspace and YAML path helpers for HEART training.

These preflight checks prevent accidental reuse of existing checkpoint folders.
They do not alter model math or the experiment hyperparameters.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

MARKER = '.heart-arc2027-workspace'
MARKER_CONTENT = 'HEART_ARC2027_WORKSPACE_V1\n'
VARNAME = re.compile(r'\$\{([A-Za-z_][A-Za-z0-9_]*)\}')


def workspace_root() -> Path:
    raw = os.environ.get('HEART_WORK_DIR', '').strip()
    if not raw:
        raise RuntimeError('HEART_WORK_DIR is required; initialize a NEW workspace with init_workspace.py.')
    path = Path(raw).expanduser()
    if path.is_symlink():
        raise RuntimeError(f'Workspace root must not be a symlink: {path}')
    root = path.resolve()
    marker = root / MARKER
    if not marker.is_file() or marker.read_text(encoding='utf-8') != MARKER_CONTENT:
        raise RuntimeError(f'Not an initialized HEART workspace: {root}. Run init_workspace.py with a NEW empty directory.')
    return root


def expand_vars(value: str) -> str:
    env = dict(os.environ)
    if 'HEART_WORK_DIR' in value or 'HEART_BASELINE_MODEL' in value:
        root = workspace_root()
        env['HEART_WORK_DIR'] = str(root)
        env.setdefault('HEART_BASELINE_MODEL', str(root / 'precompression_baseline_v4'))
    def replace(match: re.Match) -> str:
        name = match.group(1)
        if name not in env or not env[name]:
            raise RuntimeError(f'Required environment variable is unset: {name}')
        return env[name]
    result = VARNAME.sub(replace, value)
    if '${' in result:
        raise ValueError(f'Unresolved environment variable in {result!r}')
    return result


def load_resolved_config(path: Path) -> dict:
    import yaml
    cfg = yaml.safe_load(Path(path).read_text(encoding='utf-8'))
    if not isinstance(cfg, dict):
        raise ValueError(f'Expected YAML mapping in {path}')
    def resolve(item):
        if isinstance(item, str):
            return expand_vars(item)
        if isinstance(item, dict):
            return {key: resolve(value) for key, value in item.items()}
        if isinstance(item, list):
            return [resolve(value) for value in item]
        return item
    return resolve(cfg)


def require_file(path: str, label: str) -> Path:
    file = Path(path).expanduser()
    if not file.is_file():
        raise FileNotFoundError(f'{label} not found: {file}')
    return file


def require_model(path_or_id: str) -> None:
    """For local paths, validate presence; for Hugging Face IDs, defer fetch to HF."""
    if path_or_id.startswith(('/', '.', '~')):
        path = Path(path_or_id).expanduser()
        if not path.is_dir():
            raise FileNotFoundError(f'Local model directory does not exist: {path}')


def validate_new_output(path: str | Path) -> Path:
    """Require NEW output inside marked workspace; never overwrite runs.

    Unlike the training logic, this validation has no filesystem side effects.
    """
    root = workspace_root()
    requested = Path(path).expanduser()
    if requested.is_symlink():
        raise RuntimeError(f'Refusing symlink output: {requested}')
    output = requested.resolve()
    if output == root or root not in output.parents:
        raise RuntimeError(f'Output must be a new subdirectory of HEART_WORK_DIR: {output}')
    if output.exists():
        raise FileExistsError(f'Output already exists; use a fresh workspace/run: {output}')
    return output


def reserve_new_output(path: str | Path) -> Path:
    output = validate_new_output(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir(exist_ok=False)
    return output

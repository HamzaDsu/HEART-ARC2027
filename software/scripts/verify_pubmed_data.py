#!/usr/bin/env python3
"""Verify byte-identical PubMed input/processed data against HEART's manifest.

Read-only; requires the preserved raw file, not internet or a GPU.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def verify(manifest: dict, workspace: Path, rebuilt_dir: Path | None = None) -> bool:
    source = manifest['source']
    records = {
        'raw': (workspace / source['raw_path_relative_to_workspace'], source['raw_sha256'])
    }
    for name, item in manifest['artifacts'].items():
        target = rebuilt_dir / Path(item['relative_path']).name if rebuilt_dir else workspace / item['relative_path']
        records[name] = target, item['sha256']
    ok = True
    for name, (path, expected) in records.items():
        if not path.is_file():
            print(f'[MISSING] {name}: {path}')
            ok = False
            continue
        actual = sha256(path)
        match = actual == expected
        print(f'[{"MATCH" if match else "MISMATCH"}] {name}: {path}')
        print(f'  actual:   {actual}')
        print(f'  expected: {expected}')
        ok &= match
    return ok


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--workspace', type=Path, required=True, help='HEART reproduction workspace root')
    p.add_argument('--manifest', type=Path, default=Path(__file__).resolve().parents[1] / 'data' / 'pubmed_source_manifest.json')
    p.add_argument('--rebuilt-dir', type=Path, default=None, help='Optional alternative directory holding rebuilt train/eval files')
    args = p.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    ok = verify(manifest, args.workspace, args.rebuilt_dir)
    print('PUBMED INPUTS VERIFIED' if ok else 'PUBMED INPUT VERIFICATION FAILED')
    return 0 if ok else 1


if __name__ == '__main__':
    raise SystemExit(main())

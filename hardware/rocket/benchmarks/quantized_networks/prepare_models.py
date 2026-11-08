#!/usr/bin/env python3
"""Prepare the four supplied quantized networks automatically."""

import argparse
import hashlib
import json
import shutil
import stat
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument(
    "--source",
    type=Path,
    default=Path(__file__).resolve().parent / "archives",
)
parser.add_argument(
    "--workspace",
    type=Path,
    default=Path.home() / "quantized-network-benchmarks",
)
args = parser.parse_args()

source = args.source.expanduser().resolve()
workspace = args.workspace.expanduser().resolve()

archives = {
    "resnet18": "ResNet18_MultiPrecision_IntegerInference.zip",
    "vgg11": "VGG11_MultiPrecision_Python_IntegerInference.zip",
    "swint": "SwinT_MultiPrecision_IntegerInference.zip",
    "levit384": "LeViT384_MultiPrecision_IntegerInference.zip",
}

manifest = {}

for model, filename in archives.items():
    zip_path = source / filename
    target = workspace / model

    print(f"\n===== PREPARING {model.upper()} =====", flush=True)

    if not zip_path.is_file():
        raise SystemExit(f"Missing ZIP: {zip_path}")

    target.mkdir(parents=True, exist_ok=True)

    with zip_path.open("rb") as f:
        checksum = hashlib.file_digest(f, "sha256").hexdigest()

    extracted = 0

    with ZipFile(zip_path) as z:
        for entry in z.infolist():
            path = PurePosixPath(entry.filename.replace("\\", "/"))

            if path.is_absolute() or ".." in path.parts:
                raise ValueError(f"Unsafe path: {entry.filename}")

            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError(f"Symlink not allowed: {entry.filename}")

            if entry.is_dir() or "__pycache__" in path.parts:
                continue

            if path.suffix == ".pyc":
                continue

            # Supplied CIFAR-10 dataset archive is incomplete.
            if model == "vgg11" and path.as_posix() == "data/cifar-10-python.tar.gz":
                continue

            destination = target.joinpath(*path.parts)

            if not destination.resolve().is_relative_to(target.resolve()):
                raise ValueError(f"Unsafe destination: {entry.filename}")

            if destination.exists():
                continue

            destination.parent.mkdir(parents=True, exist_ok=True)

            with z.open(entry) as src, destination.open("wb") as dst:
                shutil.copyfileobj(src, dst)

            extracted += 1

    if model == "vgg11":
        script = target / "npu_inference_integer.py"
        content = script.read_text()

        old = 'root="./data", train=False, download=True, transform=transform'
        new = 'root="./data", train=False, download=False, transform=transform'

        if old in content:
            script.write_text(content.replace(old, new))
        elif new not in content:
            raise RuntimeError("Unexpected VGG11 dataset code")

    manifest[model] = {
        "archive": filename,
        "sha256": checksum,
        "archive_size_bytes": zip_path.stat().st_size,
    }

    print(f"New files extracted: {extracted}")
    print("Status: READY")

results = workspace / "results"
results.mkdir(parents=True, exist_ok=True)

(results / "archive_manifest.json").write_text(
    json.dumps(manifest, indent=2) + "\n"
)

print(f"\n===== PREPARED: {len(manifest)}/4 NETWORKS =====")
print("Manifest:", results / "archive_manifest.json")

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SNAPSHOT="$ROOT/inventory/chipyard-conda-explicit-linux-64.txt"

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 CHIPYARD_DIRECTORY --check|--install"
    exit 1
fi

CHIPYARD="$(realpath "$1")"
MODE="$2"

if [ "$MODE" != "--check" ] && [ "$MODE" != "--install" ]; then
    echo "ERROR: Use --check or --install"
    exit 1
fi

echo "===== VERIFY CHIPYARD SOURCE ====="

EXPECTED="$(cat "$ROOT/inventory/chipyard-expected-tree.txt")"
ACTUAL="$(git -C "$CHIPYARD" rev-parse 'HEAD^{tree}')"

if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "ERROR: Unexpected Chipyard source tree"
    exit 1
fi

echo "PASS: Chipyard source"

echo "===== VERIFY CONDA SNAPSHOT ====="

(
    cd "$ROOT"
    sha256sum -c inventory/chipyard-conda-explicit-sha256.txt
)

grep -qx '@EXPLICIT' "$SNAPSHOT"

echo "===== VERIFY PLATFORM ====="

if [ "$(uname -s)" != "Linux" ] ||
   [ "$(uname -m)" != "x86_64" ]; then
    echo "ERROR: Snapshot requires Linux x86_64"
    exit 1
fi

GLIBC="$(getconf GNU_LIBC_VERSION | awk '{print $2}')"

echo "System glibc: $GLIBC"

MINIMUM="2.39"

if [ "$(printf '%s\n' "$MINIMUM" "$GLIBC" | sort -V | head -1)" != "$MINIMUM" ]; then
    echo "ERROR: Captured environment targets glibc 2.39."
    echo "This server needs a separately validated environment."
    exit 1
fi

echo "===== VERIFY CONDA ====="

command -v conda >/dev/null || {
    echo "ERROR: Conda is not installed"
    exit 1
}

conda --version

if [ "$MODE" = "--check" ]; then
    echo "PASS: Environment prerequisites checked"
    echo "No packages were installed."
    exit 0
fi

ENV_PATH="$CHIPYARD/.conda-env"

if [ -e "$ENV_PATH" ]; then
    echo "ERROR: Conda environment already exists"
    exit 1
fi

echo "===== INSTALL PINNED CONDA ENVIRONMENT ====="

conda create \
    --yes \
    --prefix "$ENV_PATH" \
    --file "$SNAPSHOT"

echo "===== VERIFY INSTALLED TOOLS ====="

"$ENV_PATH/bin/verilator" --version
"$ENV_PATH/lib/jvm/bin/java" -version

echo "===== CONDA SETUP COMPLETE ====="
echo "Environment: $ENV_PATH"

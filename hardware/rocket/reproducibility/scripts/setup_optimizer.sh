#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
DEST="${2:-}"
SOURCE="${3:-https://github.com/Reliable-and-Secure-Systems/health-slm-riscv-hardware.git}"

PIN="b4df60f12e529f5b8bc3f317ed9aaf3706fde8de"
PYTHON="${OPTIMIZER_PYTHON:-$HOME/miniconda3/bin/python}"

if [[ "$MODE" != "--check" && "$MODE" != "--install" ]] || [[ -z "$DEST" ]]; then
    echo "Usage: $0 --check|--install DESTINATION [SOURCE]"
    exit 1
fi

echo "===== VERIFY PYTHON ====="

test -x "$PYTHON" || {
    echo "ERROR: Python not found: $PYTHON"
    exit 1
}

"$PYTHON" --version

"$PYTHON" - <<'PY'
import sys
if sys.version_info[:2] != (3, 14):
    raise SystemExit("ERROR: Expected Python 3.14")
print("PASS: Python version supported")
PY

echo "===== VERIFY DESTINATION ====="

[[ "$DEST" == /* ]] || {
    echo "ERROR: Destination must be an absolute path"
    exit 1
}

[[ ! -e "$DEST" ]] || {
    echo "ERROR: Destination already exists"
    exit 1
}

echo "===== VERIFY SOURCE ====="

if [[ -d "$SOURCE/.git" ]]; then
    git -C "$SOURCE" cat-file -e "$PIN^{commit}"
    echo "PASS: Pinned optimizer commit available locally"
else
    echo "Remote source will be verified during installation"
fi

if [[ "$MODE" == "--check" ]]; then
    echo "===== OPTIMIZER SETUP PRECHECK PASSED ====="
    exit 0
fi

echo "===== CLONE OPTIMIZER ====="

git clone --no-checkout "$SOURCE" "$DEST"
git -C "$DEST" checkout --detach "$PIN"

test "$(git -C "$DEST" rev-parse HEAD)" = "$PIN"

echo "PASS: Pinned optimizer revision"

echo "===== CREATE PYTHON ENVIRONMENT ====="

"$PYTHON" -m venv "$DEST/.venv-optimizer"

VENV_PYTHON="$DEST/.venv-optimizer/bin/python"

echo "===== INSTALL DEPENDENCIES ====="

"$VENV_PYTHON" -m pip install \
    --disable-pip-version-check \
    -r "$DEST/requirements-optimizer.txt"

"$VENV_PYTHON" -m pip check

echo "===== RUN OPTIMIZER TESTS ====="

(
    cd "$DEST"
    "$VENV_PYTHON" -m pytest -q tests/optimizer
)

echo "===== OPTIMIZER INSTALLATION VERIFIED ====="

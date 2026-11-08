#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 CHIPYARD_DIRECTORY"
    exit 1
fi

CHIPYARD="$1"
SUB="$CHIPYARD/fpga/fpga-shells"
PATCH="$ROOT/patches/fpga-shells/arty_a7_100_board.patch"

echo "===== VERIFY PATCH CHECKSUM ====="

(
    cd "$ROOT"
    sha256sum -c inventory/fpga-shells-patch-sha256.txt
)

echo "===== INITIALIZE FPGA-SHELLS ====="

git -C "$CHIPYARD" submodule update \
    --init -- fpga/fpga-shells

echo "===== VERIFY SUBMODULE REVISION ====="

EXPECTED="$(git -C "$CHIPYARD" rev-parse HEAD:fpga/fpga-shells)"
ACTUAL="$(git -C "$SUB" rev-parse HEAD)"

if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "ERROR: FPGA-shells revision mismatch"
    exit 1
fi

echo "PASS: FPGA-shells revision $ACTUAL"

echo "===== APPLY ARTY A7 BOARD PATCH ====="

if git -C "$SUB" apply --reverse --check "$PATCH" 2>/dev/null; then
    echo "Patch is already applied."
else
    git -C "$SUB" apply --check "$PATCH"
    git -C "$SUB" apply "$PATCH"
    echo "PASS: Arty A7 board patch applied"
fi

echo "===== VERIFY FPGA PART ====="

grep 'set part_fpga' \
    "$SUB/xilinx/arty_a7_100/tcl/board.tcl"

echo "===== FPGA-SHELLS SETUP COMPLETE ====="

#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 CHIPYARD_DIRECTORY"
    exit 1
fi

CHIPYARD="$(realpath "$1")"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECTED_TREE="$(cat "$ROOT/inventory/chipyard-expected-tree.txt")"

echo "===== VERIFY CHIPYARD SOURCE ====="

ACTUAL_TREE="$(git -C "$CHIPYARD" rev-parse 'HEAD^{tree}')"

if [ "$EXPECTED_TREE" != "$ACTUAL_TREE" ]; then
    echo "ERROR: Unexpected Chipyard source revision"
    exit 1
fi

echo "PASS: Chipyard source tree verified"

SUBMODULES=(
    generators/rocket-chip
    generators/rocket-chip-blocks
    generators/rocket-chip-inclusive-cache
    generators/testchipip
    generators/diplomacy
    generators/hardfloat
)

echo "===== INITIALIZE PINNED SUBMODULES ====="

for SUB in "${SUBMODULES[@]}"; do
    echo
    echo "Submodule: $SUB"

    EXPECTED="$(git -C "$CHIPYARD" rev-parse "HEAD:$SUB")"

    SUB_DIR="$CHIPYARD/$SUB"

    EXISTING_ROOT="$(
        git -C "$SUB_DIR" rev-parse --show-toplevel 2>/dev/null || true
    )"

    if [ "$EXISTING_ROOT" != "$SUB_DIR" ]; then
        git -C "$CHIPYARD" submodule update --init -- "$SUB"
    fi

    ACTUAL="$(git -C "$SUB_DIR" rev-parse HEAD)"

    if [ "$EXPECTED" != "$ACTUAL" ]; then
        echo "ERROR: Revision mismatch for $SUB"
        exit 1
    fi

    echo "PASS: $SUB"
    echo "Commit: $ACTUAL"
done

echo
echo "===== CORE SUBMODULE SETUP COMPLETE ====="
echo "All six submodules match their pinned commits."
echo "Nested submodules and build tools are not yet configured."

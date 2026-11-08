#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
META="$ROOT/inventory/chipyard-patch-base.txt"

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 DESTINATION [GIT_SOURCE]"
    exit 1
fi

DEST="$1"
BASE="$(sed -n 's/^base_commit=//p' "$META")"
UPSTREAM="$(sed -n 's/^upstream_url=//p' "$META")"
SOURCE="${2:-$UPSTREAM}"
EXPECTED="$(cat "$ROOT/inventory/chipyard-expected-tree.txt")"

if [ -e "$DEST" ]; then
    echo "ERROR: Destination already exists: $DEST"
    exit 1
fi

echo "===== VERIFY PATCH CHECKSUMS ====="
(
    cd "$ROOT"
    sha256sum -c inventory/chipyard-patch-sha256.txt
)

PATCHES=("$ROOT"/patches/chipyard/*.patch)

if [ "${#PATCHES[@]}" -ne 6 ]; then
    echo "ERROR: Expected exactly six patches"
    exit 1
fi

echo "===== CLONE PINNED CHIPYARD ====="
git clone --quiet --no-checkout "$SOURCE" "$DEST"

git -C "$DEST" checkout --quiet --detach "$BASE"

echo "===== APPLY THESIS PATCHES ====="
git -C "$DEST" \
    -c user.name="Thesis Reproduction" \
    -c user.email="reproduction@local.invalid" \
    am "${PATCHES[@]}"

echo "===== VERIFY SOURCE TREE ====="
ACTUAL="$(git -C "$DEST" rev-parse 'HEAD^{tree}')"

if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "ERROR: Reconstructed source differs"
    exit 1
fi

echo "PASS: Chipyard source reconstructed successfully"
echo "Tree: $ACTUAL"
echo "Note: Submodules and build dependencies are not installed."

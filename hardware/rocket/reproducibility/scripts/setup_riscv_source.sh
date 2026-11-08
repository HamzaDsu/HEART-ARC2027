#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: $0 DESTINATION [GIT_SOURCE]"
    exit 1
fi

DEST="$1"
SOURCE="${2:-https://github.com/riscv-collab/riscv-gnu-toolchain.git}"

PIN="d118e5335a33d4dc77fdc64e5a5223931ab422a0"

if [ -e "$DEST" ]; then
    echo "ERROR: Destination already exists: $DEST"
    exit 1
fi

echo "===== CLONE RISC-V TOOLCHAIN ====="

git clone --quiet --no-checkout "$SOURCE" "$DEST"
git -C "$DEST" checkout --quiet --detach "$PIN"

echo "===== INITIALIZE COMPILER SOURCES ====="

if [ -n "${RISCV_LOCAL_SUBMODULES:-}" ]; then
    echo "===== USING EXISTING LOCAL SOURCES ====="

    for SUB in binutils gcc newlib; do
        LOCAL="$RISCV_LOCAL_SUBMODULES/$SUB"

        if ! git -C "$LOCAL" rev-parse HEAD >/dev/null 2>&1; then
            echo "ERROR: Missing local source: $LOCAL"
            exit 1
        fi

        EXPECTED="$(git -C "$DEST" rev-parse "HEAD:$SUB")"
        AVAILABLE="$(git -C "$LOCAL" rev-parse HEAD)"

        if [ "$EXPECTED" != "$AVAILABLE" ]; then
            echo "ERROR: Local $SUB revision mismatch"
            exit 1
        fi

        git -C "$DEST" config "submodule.$SUB.url" "$LOCAL"
    done

    git -C "$DEST" -c protocol.file.allow=always \
        submodule update --init --jobs 1 -- \
        binutils gcc newlib
else
    git -C "$DEST" submodule update \
        --init --jobs 3 -- \
        binutils gcc newlib
fi

echo "===== VERIFY SOURCE REVISIONS ====="

for SUB in binutils gcc newlib; do
    EXPECTED="$(git -C "$DEST" rev-parse "HEAD:$SUB")"
    ACTUAL="$(git -C "$DEST/$SUB" rev-parse HEAD)"

    if [ "$EXPECTED" != "$ACTUAL" ]; then
        echo "FAIL: $SUB revision mismatch"
        exit 1
    fi

    echo "PASS: $SUB ($ACTUAL)"
done

echo "===== VERIFY MAIN REVISION ====="

test "$(git -C "$DEST" rev-parse HEAD)" = "$PIN"

echo "PASS: Exact RISC-V source revision"
echo "===== RISC-V SOURCE SETUP COMPLETE ====="
echo "Compiler has not yet been built."

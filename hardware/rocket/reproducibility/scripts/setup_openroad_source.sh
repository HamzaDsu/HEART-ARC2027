#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 3 ]; then
    echo "Usage: $0 DESTINATION [FLOW_SOURCE] [ENGINE_SOURCE]"
    exit 1
fi

DEST="$1"
SOURCE="${2:-https://github.com/The-OpenROAD-Project/OpenROAD-flow-scripts.git}"
ENGINE_SOURCE="${3:-}"

FLOW_PIN="b74a7293ea57fc4154a08471bcf78042ed497e4e"
ENGINE_PIN="943a4d2c9284cce991a916b67937157d890fe133"

if [ -e "$DEST" ]; then
    echo "ERROR: Destination already exists: $DEST"
    exit 1
fi

echo "===== CLONE OPENROAD FLOW SCRIPTS ====="

git clone --quiet --no-checkout "$SOURCE" "$DEST"

git -C "$DEST" checkout --quiet --detach "$FLOW_PIN"

echo "===== INITIALIZE OPENROAD ENGINE ====="

if [ -n "$ENGINE_SOURCE" ]; then
    git -C "$DEST" config \
        submodule.tools/OpenROAD.url \
        "$ENGINE_SOURCE"
fi

git -C "$DEST" \
    -c protocol.file.allow=always \
    submodule update --init -- tools/OpenROAD

echo "===== VERIFY SOURCE REVISIONS ====="

FLOW_ACTUAL="$(git -C "$DEST" rev-parse HEAD)"
ENGINE_EXPECTED="$(git -C "$DEST" rev-parse HEAD:tools/OpenROAD)"
ENGINE_ACTUAL="$(git -C "$DEST/tools/OpenROAD" rev-parse HEAD)"

test "$FLOW_ACTUAL" = "$FLOW_PIN" || {
    echo "ERROR: Flow scripts revision mismatch"
    exit 1
}

test "$ENGINE_EXPECTED" = "$ENGINE_PIN" || {
    echo "ERROR: Pinned engine revision mismatch"
    exit 1
}

test "$ENGINE_ACTUAL" = "$ENGINE_PIN" || {
    echo "ERROR: OpenROAD engine revision mismatch"
    exit 1
}

echo "PASS: OpenROAD-flow-scripts revision"
echo "PASS: OpenROAD engine revision"
echo "===== OPENROAD SOURCE SETUP COMPLETE ====="
echo "OpenROAD has not yet been compiled."

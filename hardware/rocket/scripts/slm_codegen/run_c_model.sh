#!/usr/bin/env bash
set -euo pipefail

MODEL="${1:?Usage: run_c_model.sh MODEL.gguf [PROMPT]}"
PROMPT="${2:-Give three tips for maintaining a healthy lifestyle.}"

ROOT="$HOME/llama-native-rebuild-test"
LIB="$ROOT/build/bin"
SRC="scripts/slm_codegen/healthslm_c_api.c"
BIN="/tmp/healthslm_c_api"

if [[ ! -f "$MODEL" ]]; then
    echo "ERROR: Model not found: $MODEL"
    exit 1
fi

echo "===== COMPILING C INFERENCE PROGRAM ====="

gcc -std=c11 -O2 \
    -I "$ROOT/include" \
    -I "$ROOT/ggml/include" \
    "$SRC" \
    "$LIB/libllama.so.0" \
    -Wl,-rpath,"$LIB" \
    -o "$BIN"

echo "===== RUNNING MODEL ====="

LD_LIBRARY_PATH="$LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$BIN" "$MODEL" "$PROMPT"

echo
echo "===== INFERENCE COMPLETED ====="

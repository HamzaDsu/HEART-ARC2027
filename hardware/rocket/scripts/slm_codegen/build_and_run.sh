#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 MODEL.gguf [PROMPT]"
    exit 2
fi

MODEL="$(realpath "$1")"
PROMPT="${2:-Give three general tips for maintaining a healthy lifestyle.}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LLAMA_ROOT="${LLAMA_ROOT:-$HOME/llama-native-rebuild-test}"
LIB="$LLAMA_ROOT/build/bin"

OUTPUT_DIR="$HOME/health-slm-inference-results"
mkdir -p "$OUTPUT_DIR"

echo "===== GENERATE C SOURCE ====="

GENERATION_OUTPUT="$(python3 "$SCRIPT_DIR/generate_c.py" "$MODEL")"
printf '%s\n' "$GENERATION_OUTPUT"

SOURCE="$(printf '%s\n' "$GENERATION_OUTPUT" |
    sed -n 's/^Generated C source: //p')"

if [[ ! -f "$SOURCE" ]]; then
    echo "ERROR: Generated C source not found"
    exit 1
fi

NAME="$(basename "$SOURCE" .c)"
BINARY="$OUTPUT_DIR/${NAME}_native"
LOG="$OUTPUT_DIR/${NAME}_inference.log"

echo "===== COMPILE C PROGRAM ====="

gcc -std=c11 -O2 \
    -I "$LLAMA_ROOT/include" \
    -I "$LLAMA_ROOT/ggml/include" \
    "$SOURCE" \
    "$LIB/libllama.so.0" \
    -Wl,-rpath,"$LIB" \
    -o "$BINARY"

echo "===== RUN FULL MODEL ====="

export LD_LIBRARY_PATH="$LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

/usr/bin/time -v \
    "$BINARY" "$PROMPT" \
    > "$LOG" 2>&1

echo "===== RESULTS ====="

grep -A 5 "===== GENERATED RESPONSE =====" "$LOG" || true

grep -E \
    "Elapsed \(wall clock\)|Maximum resident set size|Exit status" \
    "$LOG"

echo
echo "Log saved: $LOG"
echo "===== SUCCESS ====="

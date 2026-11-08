#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 MODEL.gguf [PROMPT]"
    exit 2
fi

MODEL="$(realpath "$1")"
PROMPT="${2:-Give three general tips for maintaining a healthy lifestyle.}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${RISCV_LLAMA_ROOT:-$HOME/llama-riscv-rebuild-test}"
BUILD="$ROOT/build"
RESULTS="$HOME/health-slm-inference-results"
TIMEOUT="${QEMU_TIMEOUT:-240}"

mkdir -p "$RESULTS"

echo "===== GENERATE C SOURCE ====="

OUTPUT="$(python3 "$SCRIPT_DIR/generate_c.py" "$MODEL")"
printf '%s\n' "$OUTPUT"

SOURCE="$(printf '%s\n' "$OUTPUT" |
    sed -n 's/^Generated C source: //p')"

if [[ ! -f "$SOURCE" ]]; then
    echo "ERROR: C source generation failed"
    exit 1
fi

NAME="$(basename "$SOURCE" .c)"
OBJECT="$RESULTS/${NAME}_riscv.o"
BINARY="$RESULTS/${NAME}_riscv"
LOG="$RESULTS/${NAME}_riscv_qemu.log"

echo "===== COMPILE FOR RISC-V ====="

riscv64-linux-gnu-gcc \
    -std=c11 -O2 \
    -I "$ROOT/include" \
    -I "$ROOT/ggml/include" \
    -c "$SOURCE" \
    -o "$OBJECT"

echo "===== LINK RISC-V EXECUTABLE ====="

riscv64-linux-gnu-g++ \
    "$OBJECT" \
    -Wl,--start-group \
    "$BUILD/src/libllama.a" \
    "$BUILD/ggml/src/libggml.a" \
    "$BUILD/ggml/src/libggml-cpu.a" \
    "$BUILD/ggml/src/libggml-base.a" \
    -Wl,--end-group \
    -pthread -lm -ldl -latomic \
    -o "$BINARY"

echo "===== VERIFY ARCHITECTURE ====="
file "$BINARY"

echo "===== RUN RISC-V QEMU ====="

if /usr/bin/time -f \
    'Elapsed: %e seconds | Peak RAM: %M KB' \
    timeout "${TIMEOUT}s" \
    qemu-riscv64 \
        -L /usr/riscv64-linux-gnu \
        "$BINARY" "$PROMPT" \
        > "$LOG" 2>&1; then
    STATUS=0
else
    STATUS=$?
fi

echo "===== MODEL RESPONSE ====="
grep -A 5 "===== GENERATED RESPONSE =====" "$LOG" || true

echo
echo "===== BENCHMARK RESULTS ====="
grep -E "Elapsed:|Peak RAM:" "$LOG" || true

echo
echo "Exit status: $STATUS"
echo "Log: $LOG"

if [[ "$STATUS" -ne 0 ]]; then
    tail -n 15 "$LOG"
    exit "$STATUS"
fi

echo "===== SUCCESS ====="

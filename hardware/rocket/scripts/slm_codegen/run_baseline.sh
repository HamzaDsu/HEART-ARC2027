#!/usr/bin/env bash
set -euo pipefail

MODEL="$HOME/health-slm-thesis/model_artifacts/health_slm_final/health-slm-v4-Q3_K_S-safety.gguf"
LLAMA="$HOME/llama-native-rebuild-test/build/bin/llama-cli"

OUTPUT_DIR="$HOME/health-slm-inference-results"
mkdir -p "$OUTPUT_DIR"

LOG="$OUTPUT_DIR/healthslm_native_baseline.log"

if [[ ! -f "$MODEL" ]]; then
    echo "ERROR: HealthSLM model not found"
    exit 1
fi

if [[ ! -x "$LLAMA" ]]; then
    echo "ERROR: llama.cpp executable not found"
    exit 1
fi

echo "Running complete HealthSLM model..."
echo "Log: $LOG"

"$LLAMA" \
    -m "$MODEL" \
    -p "Give three general tips for maintaining a healthy lifestyle." \
    -n 64 \
    -c 1024 \
    -t 4 \
    -ngl 0 \
    --temp 0 \
    2>&1 | tee "$LOG"

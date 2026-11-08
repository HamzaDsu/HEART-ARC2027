#!/bin/bash

#SBATCH --job-name=gguf_eval
#SBATCH --partition=students
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=03:00:00
#SBATCH --output=logs/gguf_eval_%j.log
#SBATCH --error=logs/gguf_eval_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

: "${HEART_LLAMA_DIR:?Set HEART_LLAMA_DIR to llama.cpp checkout}"
MODEL="${MODEL:?Provide MODEL via sbatch --export}"
NAME="${NAME:?Provide NAME via sbatch --export}"
[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo 'NAME must be a short safe identifier' >&2; exit 2; }
LIMIT="${LIMIT:-100}"
EVAL_FILE="$HEART_WORK_DIR/data/final_health_coaching_v4/processed/health_coaching_eval.jsonl"
OUT="$HEART_WORK_DIR/evaluations/gguf_v4/$NAME"
SERVER="$HEART_LLAMA_DIR/build/bin/llama-server"
heart_need_file "$MODEL"; heart_need_file "$EVAL_FILE"; heart_new_dir "$OUT"
PORT=$((18080 + ${SLURM_JOB_ID:-1000} % 1000))
"$SERVER" -m "$MODEL" --host 127.0.0.1 --port "$PORT" \
 -c 1024 -t "${SLURM_CPUS_PER_TASK:-8}" -ngl 0 > "$OUT/server.log" 2>&1 &
SERVER_PID=$!
cleanup() { kill "$SERVER_PID" 2>/dev/null || true; }
trap cleanup EXIT
READY=0
for i in $(seq 1 60); do
 if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then READY=1; break; fi
 if ! kill -0 "$SERVER_PID" 2>/dev/null; then echo 'llama-server exited' >&2; cat "$OUT/server.log"; exit 1; fi
 sleep 2
done
[[ "$READY" -eq 1 ]] || { echo 'llama-server did not become ready' >&2; exit 1; }
"$HEART_PYTHON" -u scripts/evaluate_gguf_safety.py \
 --model "$MODEL" --eval-file "$EVAL_FILE" --output-dir "$OUT" \
 --server-url "http://127.0.0.1:${PORT}" --max-new-tokens 160 --limit "$LIMIT"
cat "$OUT/metrics.json"

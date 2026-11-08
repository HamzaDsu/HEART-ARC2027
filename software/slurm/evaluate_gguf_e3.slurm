#!/bin/bash

#SBATCH --job-name=gguf_eval
#SBATCH --partition=students
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=03:00:00
#SBATCH --output=logs/gguf_eval_%j.log
#SBATCH --error=logs/gguf_eval_%j.err

set -euo pipefail

REPO=/home/hamzamuh/projects/health-slm-thesis

PYTHON=/scratch/hamzamuh/.conda/envs/health-slm/bin/python

SERVER=/scratch/hamzamuh/tools/llama.cpp/build/bin/llama-server

EVAL_FILE=/scratch/hamzamuh/health-slm-experiments/data/e3_challenge_v1/health_coaching_e3.jsonl

MODEL="${MODEL:?MODEL must be provided}"
NAME="${NAME:?NAME must be provided}"
LIMIT="${LIMIT:-100}"

OUT=/scratch/hamzamuh/health-slm-experiments/evaluations/e3_challenge_v1/${NAME}

mkdir -p "$REPO/logs"
mkdir -p "$OUT"

PORT=$((18080 + SLURM_JOB_ID % 1000))

echo "================================"
echo "GGUF evaluation"
echo "================================"
echo "Model: $MODEL"
echo "Name: $NAME"
echo "Limit: $LIMIT"
echo "Port: $PORT"
echo

"$SERVER" \
    -m "$MODEL" \
    --host 127.0.0.1 \
    --port "$PORT" \
    -c 1024 \
    -t "$SLURM_CPUS_PER_TASK" \
    -ngl 0 \
    > "$OUT/server.log" \
    2>&1 &

SERVER_PID=$!

cleanup() {
    kill "$SERVER_PID" 2>/dev/null || true
}

trap cleanup EXIT

echo "Waiting for llama-server..."

READY=0

for i in $(seq 1 60); do

    if curl -fsS \
        "http://127.0.0.1:${PORT}/health" \
        >/dev/null 2>&1
    then
        READY=1
        break
    fi

    if ! kill -0 "$SERVER_PID" 2>/dev/null
    then
        echo "ERROR: llama-server exited."
        cat "$OUT/server.log"
        exit 1
    fi

    sleep 2
done

if [ "$READY" -ne 1 ]; then
    echo "ERROR: server did not become ready."
    cat "$OUT/server.log"
    exit 1
fi

echo "Server ready."
echo

cd "$REPO"

"$PYTHON" \
    scripts/evaluate_gguf_safety.py \
    --model "$MODEL" \
    --eval-file "$EVAL_FILE" \
    --output-dir "$OUT" \
    --server-url "http://127.0.0.1:${PORT}" \
    --max-new-tokens 160 \
    --limit "$LIMIT"

echo
echo "================================"
echo "SERVER MEMORY"
echo "================================"

grep -E 'VmPeak|VmHWM|VmRSS' \
    "/proc/${SERVER_PID}/status" \
    | tee "$OUT/server_memory.txt" \
    || true

echo
echo "================================"
echo "FINAL METRICS"
echo "================================"

cat "$OUT/metrics.json"

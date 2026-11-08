#!/bin/bash

#SBATCH --job-name=safety_imatrix
#SBATCH --partition=students
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=02:00:00
#SBATCH --output=logs/safety_imatrix_%j.log
#SBATCH --error=logs/safety_imatrix_%j.err

set -euo pipefail

LLAMA=/scratch/hamzamuh/tools/llama.cpp

MODEL=/scratch/hamzamuh/health-slm-experiments/deployment_v4_unpruned/health-slm-v4-f16.gguf

CALIB=/scratch/hamzamuh/health-slm-experiments/deployment_v4_unpruned/safety_calibration.txt

OUT=/scratch/hamzamuh/health-slm-experiments/deployment_v4_unpruned/safety_imatrix.gguf

mkdir -p /home/hamzamuh/projects/health-slm-thesis/logs

"$LLAMA/build/bin/llama-imatrix" \
    -m "$MODEL" \
    -f "$CALIB" \
    -o "$OUT" \
    --output-format gguf \
    --parse-special \
    --no-ppl \
    -t "$SLURM_CPUS_PER_TASK"

echo
echo "================================"
echo "IMATRIX CREATED"
echo "================================"

ls -lh "$OUT"

"$LLAMA/build/bin/llama-imatrix" \
    --in-file "$OUT" \
    --show-statistics \
    | tail -50 || true

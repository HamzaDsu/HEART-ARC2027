#!/bin/bash

#SBATCH --job-name=safety_imatrix
#SBATCH --partition=students
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=02:00:00
#SBATCH --output=logs/safety_imatrix_%j.log
#SBATCH --error=logs/safety_imatrix_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

: "${HEART_LLAMA_DIR:?Set HEART_LLAMA_DIR to your llama.cpp checkout}"
MODEL="${HEART_GGUF_F16:-$HEART_WORK_DIR/deployment_v4_unpruned/health-slm-v4-f16.gguf}"
CALIB="$HEART_SOFTWARE_DIR/data/calibration/safety_calibration.txt"
OUTDIR="$HEART_WORK_DIR/deployment_v4_unpruned/imatrix_run"
heart_need_file "$MODEL"; heart_need_file "$CALIB"; heart_new_dir "$OUTDIR"
"$HEART_LLAMA_DIR/build/bin/llama-imatrix" \
 -m "$MODEL" -f "$CALIB" -o "$OUTDIR/safety_imatrix.gguf" \
 --output-format gguf --parse-special --no-ppl -t "${SLURM_CPUS_PER_TASK:-8}"
"$HEART_LLAMA_DIR/build/bin/llama-imatrix" \
 -m "$MODEL" --in-file "$OUTDIR/safety_imatrix.gguf" --show-statistics | tail -50 || true

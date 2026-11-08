#!/bin/bash
#SBATCH --job-name=merge_pubmed_lora
#SBATCH --partition=students
#SBATCH --gres=gpu:student:1
#SBATCH --mem=16G
#SBATCH --time=02:00:00
#SBATCH --output=logs/slurm_merge_pubmed_lora_%j.log
#SBATCH --error=logs/slurm_merge_pubmed_lora_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

echo "HEART work directory: $HEART_WORK_DIR"
echo "Python: $HEART_PYTHON"
"$HEART_PYTHON" --version

heart_need_dir "$HEART_WORK_DIR/runs/tinyllama_pubmed_lora/checkpoints"
"$HEART_PYTHON" -u scripts/merge_lora.py \
  --base-model TinyLlama/TinyLlama-1.1B-Chat-v1.0 \
  --adapter "$HEART_WORK_DIR/runs/tinyllama_pubmed_lora/checkpoints" \
  --output "$HEART_WORK_DIR/runs/tinyllama_pubmed_lora/merged_model" \
  --dtype float16

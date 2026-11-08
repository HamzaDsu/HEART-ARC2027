#!/bin/bash
#SBATCH --job-name=merge_health_v4
#SBATCH --partition=students
#SBATCH --gres=gpu:student:1
#SBATCH --mem=16G
#SBATCH --time=02:00:00
#SBATCH --output=logs/slurm_merge_health_v4_%j.log
#SBATCH --error=logs/slurm_merge_health_v4_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

echo "HEART work directory: $HEART_WORK_DIR"
echo "Python: $HEART_PYTHON"
"$HEART_PYTHON" --version

heart_need_dir "$HEART_WORK_DIR/runs/tinyllama_pubmed_lora/merged_model"
heart_need_dir "$HEART_WORK_DIR/runs/health_coaching_lora_v4_3ep/checkpoints"
"$HEART_PYTHON" -u scripts/merge_lora.py \
  --base-model "$HEART_WORK_DIR/runs/tinyllama_pubmed_lora/merged_model" \
  --adapter "$HEART_WORK_DIR/runs/health_coaching_lora_v4_3ep/checkpoints" \
  --output "$HEART_WORK_DIR/runs/health_coaching_lora_v4_3ep/merged_model" \
  --dtype float16

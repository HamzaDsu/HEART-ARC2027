#!/bin/bash
#SBATCH --job-name=health_v4_3ep
#SBATCH --partition=students
#SBATCH --gres=gpu:student:1
#SBATCH --mem=16G
#SBATCH --time=08:00:00
#SBATCH --output=logs/slurm_health_v4_3ep_%j.log
#SBATCH --error=logs/slurm_health_v4_3ep_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

echo "HEART work directory: $HEART_WORK_DIR"
echo "Python: $HEART_PYTHON"
"$HEART_PYTHON" --version

heart_need_dir "$HEART_WORK_DIR/runs/tinyllama_pubmed_lora/merged_model"
heart_need_file "$HEART_WORK_DIR/data/final_health_coaching_v4/processed/health_coaching_train.jsonl"
heart_need_file "$HEART_WORK_DIR/data/final_health_coaching_v4/processed/health_coaching_eval.jsonl"
"$HEART_PYTHON" -u scripts/train_lora_assistant_only.py --config configs/health_coaching_lora_v4_3ep.yaml
find "$HEART_WORK_DIR/runs/health_coaching_lora_v4_3ep/checkpoints" -maxdepth 2 -name 'adapter_config.json' -print

#!/bin/bash
#SBATCH --job-name=pubmed_lora_10k
#SBATCH --partition=students
#SBATCH --gres=gpu:student:1
#SBATCH --cpus-per-task=8
#SBATCH --mem=16G
#SBATCH --time=08:00:00
#SBATCH --output=logs/slurm_pubmed_lora_10k_%j.log
#SBATCH --error=logs/slurm_pubmed_lora_10k_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

echo "HEART work directory: $HEART_WORK_DIR"
echo "Python: $HEART_PYTHON"
"$HEART_PYTHON" --version

heart_need_file "$HEART_WORK_DIR/data/processed/pubmed_domain_train.jsonl"
heart_need_file "$HEART_WORK_DIR/data/processed/pubmed_domain_eval.jsonl"
"$HEART_PYTHON" -u scripts/train_lora.py --config configs/tinyllama_pubmed_lora_scratch.yaml
find "$HEART_WORK_DIR/runs/tinyllama_pubmed_lora/checkpoints" -maxdepth 2 -name 'adapter_config.json' -print

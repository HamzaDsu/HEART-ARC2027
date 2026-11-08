#!/bin/bash
#SBATCH --job-name=eval_pruned_v4
#SBATCH --partition=students
#SBATCH --gres=gpu:student:1
#SBATCH --mem=16G
#SBATCH --time=04:00:00
#SBATCH --output=logs/slurm_eval_pruned_v4_%j.log
#SBATCH --error=logs/slurm_eval_pruned_v4_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

echo "HEART work directory: $HEART_WORK_DIR"
echo "Python: $HEART_PYTHON"
"$HEART_PYTHON" --version

EVAL="$HEART_WORK_DIR/data/final_health_coaching_v4/processed/health_coaching_eval.jsonl"
heart_need_file "$EVAL"
for STRATEGY in min_conservative balanced; do
  MODEL="$HEART_WORK_DIR/pruning_v4/$STRATEGY"
  OUT="$HEART_WORK_DIR/evaluations/pruning_v4/$STRATEGY"
  heart_need_dir "$MODEL"; heart_new_dir "$OUT"
  "$HEART_PYTHON" -u scripts/evaluate_precompression_baseline.py \
    --model "$MODEL" --eval-file "$EVAL" --output-dir "$OUT" --max-new-tokens 160
done

#!/bin/bash
#SBATCH --job-name=eval_v4_l30
#SBATCH --partition=students
#SBATCH --gres=gpu:student:1
#SBATCH --mem=16G
#SBATCH --time=02:00:00
#SBATCH --output=logs/slurm_eval_v4_l30_%j.log
#SBATCH --error=logs/slurm_eval_v4_l30_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

echo "HEART work directory: $HEART_WORK_DIR"
echo "Python: $HEART_PYTHON"
"$HEART_PYTHON" --version

MODEL="$HEART_WORK_DIR/pruning_v4_lower30/lower_30th_percentile"
EVAL="$HEART_WORK_DIR/data/final_health_coaching_v4/processed/health_coaching_eval.jsonl"
OUT="$HEART_WORK_DIR/evaluations/pruning_v4/lower30"
heart_need_dir "$MODEL"; heart_need_file "$EVAL"; heart_new_dir "$OUT"
"$HEART_PYTHON" -u scripts/evaluate_precompression_baseline.py \
 --model "$MODEL" --eval-file "$EVAL" --output-dir "$OUT" --max-new-tokens 160

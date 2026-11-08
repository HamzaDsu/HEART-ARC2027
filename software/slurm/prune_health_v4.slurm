#!/bin/bash
#SBATCH --job-name=prune_health_v4
#SBATCH --partition=students
#SBATCH --gres=gpu:student:1
#SBATCH --mem=16G
#SBATCH --time=04:00:00
#SBATCH --output=logs/slurm_prune_health_v4_%j.log
#SBATCH --error=logs/slurm_prune_health_v4_%j.err

set -euo pipefail
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from software/}}"
source "$HEART_SOFTWARE_DIR/slurm/heart_env.sh"

echo "HEART work directory: $HEART_WORK_DIR"
echo "Python: $HEART_PYTHON"
"$HEART_PYTHON" --version

MODEL="${HEART_BASELINE_MODEL}"
DATA="$HEART_WORK_DIR/data/final_health_coaching_v4/processed/health_coaching_train.jsonl"
OUTPUT="$HEART_WORK_DIR/pruning_v4"
heart_need_dir "$MODEL"
heart_need_file "$DATA"
"$HEART_PYTHON" -u scripts/mix_match_prune_llm_assistant_only.py \
  --model "$MODEL" --data-file "$DATA" --output-dir "$OUTPUT" \
  --text-field text --strategies min_conservative balanced \
  --num-batches 16 --batch-size 1 --max-length 512 --dtype float16 \
  --device cuda --assistant-marker '<|assistant|>'
cat "$OUTPUT/all_pruning_metrics.csv"

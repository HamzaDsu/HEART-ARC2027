#!/usr/bin/env bash
# Source from Slurm entrypoints only. No deletion or overwrite logic.
set -euo pipefail
: "${HEART_WORK_DIR:?Set HEART_WORK_DIR to a NEW initialized reproduction workspace}"
: "${HEART_SOFTWARE_DIR:=${SLURM_SUBMIT_DIR:?Submit from the software directory}}"
HEART_SOFTWARE_DIR="$(cd "$HEART_SOFTWARE_DIR" && pwd -P)"
HEART_WORK_DIR="$(cd "$HEART_WORK_DIR" && pwd -P)"
[[ "$(cat "$HEART_WORK_DIR/.heart-arc2027-workspace" 2>/dev/null)" == "HEART_ARC2027_WORKSPACE_V1" ]] || {
    echo 'ERROR: initialize a NEW HEART_WORK_DIR with scripts/init_workspace.py first' >&2
    exit 2
}
[[ "$HEART_WORK_DIR" != "$HEART_SOFTWARE_DIR" ]] || { echo 'ERROR: workspace cannot be software source root' >&2; exit 2; }
export HEART_SOFTWARE_DIR HEART_WORK_DIR
export HEART_PYTHON="${HEART_PYTHON:-python}"
export HEART_LLAMA_DIR="${HEART_LLAMA_DIR:-}"
export HEART_BASELINE_MODEL="${HEART_BASELINE_MODEL:-$HEART_WORK_DIR/precompression_baseline_v4}"
export HF_HOME="${HF_HOME:-$HEART_WORK_DIR/cache/hf}"
export HF_DATASETS_CACHE="${HF_DATASETS_CACHE:-$HF_HOME/datasets}"
export TORCH_HOME="${TORCH_HOME:-$HEART_WORK_DIR/cache/torch}"
export TOKENIZERS_PARALLELISM=false
export PYTHONFAULTHANDLER=1
mkdir -p "$HF_HOME" "$HF_DATASETS_CACHE" "$TORCH_HOME" "$HEART_WORK_DIR/logs"
cd "$HEART_SOFTWARE_DIR"
heart_need_file() { [[ -f "$1" ]] || { echo "Missing file: $1" >&2; return 2; }; }
heart_need_dir() { [[ -d "$1" ]] || { echo "Missing directory: $1" >&2; return 2; }; }
heart_new_dir() {
    [[ "$1" == "$HEART_WORK_DIR/"* ]] || { echo "Unsafe output outside workspace: $1" >&2; return 2; }
    [[ ! -e "$1" && ! -L "$1" ]] || { echo "Refusing existing output directory: $1" >&2; return 2; }
    mkdir -p "$(dirname "$1")"
    mkdir "$1"
}

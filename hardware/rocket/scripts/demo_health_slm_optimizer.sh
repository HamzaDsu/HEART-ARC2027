#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Locate Vivado automatically in a fresh terminal.
if ! command -v vivado >/dev/null 2>&1; then
    for SETTINGS in         "$HOME"/Xilinx/Vivado/*/settings64.sh         /opt/Xilinx/Vivado/*/settings64.sh
    do
        if [ -f "$SETTINGS" ]; then
            set +u
            source "$SETTINGS"
            set -u
            break
        fi
    done
fi

if ! command -v vivado >/dev/null 2>&1; then
    echo "ERROR: Vivado is not available."
    exit 1
fi


PYTHON="$PWD/.venv-optimizer/bin/python"

MODEL="$HOME/health-slm-thesis/model_artifacts/health_slm_final/health-slm-v4-Q3_K_S-safety.gguf"

if [ ! -f "$MODEL" ]; then
    echo "ERROR: Health-SLM model not found"
    exit 1
fi

if [ ! -x "$PYTHON" ]; then
    echo "ERROR: Optimizer Python environment not found"
    exit 1
fi

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

echo "======================================"
echo " HEALTH-SLM HARDWARE OPTIMIZER DEMO"
echo "======================================"

echo
echo "===== 1. LIVE MODEL OPTIMIZATION ====="

"$PYTHON" -m optimizer.cli \
    --model "$MODEL" \
    --board "Arty A7-100T" \
    --objective balanced \
    --scope kernel \
    --top 5 \
    --output "$TMPDIR/balanced.json"

echo
echo "===== 2. VALIDATED OBJECTIVE COMPARISON ====="

"$PYTHON" - <<'PY'
import json
from pathlib import Path

root = Path("results/optimizer")

for objective in (
    "balanced", "performance", "resources", "power"
):
    data = json.loads(
        (root / f"final_health_slm_{objective}.json").read_text()
    )
    assert data["status"] == "heuristic_ranking_complete"

    candidate = data["recommended_exploration_candidate"]
    config = candidate["configuration"]

    print(
        f"{objective:12} | "
        f"L1={config['dcache_kib']:2} KiB | "
        f"Clock={config['target_clock_mhz']:2} MHz"
    )
PY

echo
echo "===== 3. MEASURED RISC-V PERFORMANCE ====="

"$PYTHON" - <<'PY'
from pathlib import Path
from optimizer.optimization.performance_evidence import (
    summarize_q3k_l1_evidence,
)

manifest = Path(
    "results/optimizer/measurements/"
    "controlled_q3k_l1_16blocks/manifest.json"
)

evidence = summarize_q3k_l1_evidence(manifest)

for name, item in evidence["comparisons"].items():
    print(
        f"{name:4} | "
        f"{item['cycles']:7} cycles | "
        f"{item['cycle_change_percent']:+.4f}%"
    )

print("Checksum:", evidence["checksum"])
print("Physical FPGA execution:", False)
PY

echo
echo "===== 4. FULL-MODEL FEASIBILITY ====="

"$PYTHON" - <<'PY'
import json
from pathlib import Path

data = json.loads(
    Path(
        "results/optimizer/final_health_slm_full_model.json"
    ).read_text()
)

assert data["status"] == "resident_model_memory_blocked"

print("Previously validated status:", data["status"])
print("Reason:", data["reason"])
print("Full-model deployment verified: No")
PY

echo
echo "===== DEMONSTRATION COMPLETED ====="
echo "Note: Performance rankings are heuristic."
echo "RISC-V kernel benchmarks are simulation measurements."

#!/usr/bin/env bash
set -euo pipefail

SIM="${1:-$HOME/chipyard/sims/verilator/simulator-chipyard.harness-HealthSLMNoScratchpadSimConfig}"
BENCH="${2:-$HOME/fpbench-repro-test/fpbench.riscv}"
LOG="${3:-$HOME/fpbench-auto-verification.log}"

echo "===== VERIFY SIMULATION DEPENDENCIES ====="

test -x "$SIM" || {
    echo "ERROR: Simulator executable missing"
    exit 1
}

test -f "$BENCH" || {
    echo "ERROR: FPBench executable missing"
    exit 1
}

echo "PASS: Verilator simulator"
echo "PASS: RISC-V FPBench"

echo
echo "===== RUN FPBENCH SIMULATION ====="

START=$(date +%s)

set +e
(
    cd "$(dirname "$SIM")" || exit 1
    timeout --foreground 300s "$SIM" "$BENCH"
) > "$LOG" 2>&1
STATUS=$?
set -e

echo "Simulator exit code: $STATUS"
echo "Elapsed seconds: $(($(date +%s) - START))"

if [[ "$STATUS" -eq 0 ]]; then
    echo "PASS: Simulator exited successfully"
else
    echo "FAIL: Simulator execution"

    if [[ "$STATUS" -eq 124 ]]; then
        echo "CAUSE: Simulation exceeded 300 seconds"
    fi

    tail -n 30 "$LOG"
    exit 1
fi

echo
echo "===== SIMULATION RESULTS ====="

grep -E \
    'FMA operations|Total cycles|Cycles/op|Verilog.*finish' \
    "$LOG"

echo
echo "===== VERIFY BASELINE ====="

grep -Eq 'FMA operations[[:space:]]*:[[:space:]]*10000' "$LOG"
echo "PASS: 10,000 FMA operations"

grep -Eq 'Total cycles[[:space:]]*:[[:space:]]*60010' "$LOG"
echo "PASS: 60,010 benchmark cycles"

grep -Eq 'Cycles/op[[:space:]]*:[[:space:]]*6\.001' "$LOG"
echo "PASS: 6.001 cycles per operation"

grep -Fq 'Verilog $finish' "$LOG"
echo "PASS: Simulation reached Verilog finish"

echo
echo "===== SIMULATION VERIFICATION PASSED ====="

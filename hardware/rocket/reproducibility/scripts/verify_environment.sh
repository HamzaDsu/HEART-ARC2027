#!/usr/bin/env bash
set -uo pipefail

MODE="${1:---local}"
ROOT="${HEALTH_SLM_BUILD_ROOT:-$HOME/health-slm-repro-build}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "$MODE" != "--local" && "$MODE" != "--root" ]]; then
    echo "Usage: $0 --local|--root"
    exit 1
fi

if [[ "$MODE" == "--local" ]]; then
    CHIP="$HOME/chipyard"
    RISCV="$HOME/riscv-medany-rebuild-test"
    OPENROAD="$HOME/openroad-rebuild-test/bin/openroad"
    OPT="$HOME/optimizer-rebuild-test"
    NATIVE="$HOME/llama-native-rebuild-test/build/bin"
    RISCV_LLAMA="$HOME/llama-riscv-rebuild-test/build/bin"
    BENCH="$HOME/fpbench-repro-test/fpbench.riscv"
else
    CHIP="$ROOT/chipyard"
    RISCV="$ROOT/riscv-medany"
    OPENROAD="$ROOT/openroad-install/bin/openroad"
    OPT="$ROOT/health-slm-optimizer"
    NATIVE="$ROOT/llama-native/build/bin"
    RISCV_LLAMA="$ROOT/llama-riscv-linux/build/bin"
    BENCH="$ROOT/fpbench/fpbench.riscv"
fi

PASSES=0
FAILURES=0

pass() {
    echo "PASS: $1"
    PASSES=$((PASSES + 1))
}

fail() {
    echo "FAIL: $1"
    FAILURES=$((FAILURES + 1))
}

check_file() {
    if [[ -f "$1" ]]; then
        pass "$2"
    else
        fail "$2 — missing $1"
    fi
}

check_executable() {
    if [[ -x "$1" ]]; then
        pass "$2"
    else
        fail "$2 — missing $1"
    fi
}

check_architecture() {
    local executable="$1"
    local architecture="$2"
    local name="$3"

    if [[ -f "$executable" ]] &&
       file "$executable" | grep -q "$architecture"; then
        pass "$name architecture"
    else
        fail "$name architecture"
    fi
}

echo "============================================"
echo " HEALTH-SLM ENVIRONMENT VERIFICATION"
echo "============================================"
echo "Mode: $MODE"
echo

echo "===== CHIPYARD ====="

check_file "$CHIP/tests/fpbench.c" "FPBench source"
check_file "$CHIP/tests/atexit-workaround.c" "Atexit workaround"
check_file "$CHIP/tests/CMakeLists.txt" "Benchmark CMake configuration"

echo
echo "===== RISC-V GNU TOOLCHAIN ====="

GCC="$RISCV/bin/riscv64-unknown-elf-gcc"

check_executable "$GCC" "RISC-V GCC"

if [[ -x "$GCC" ]]; then
    "$GCC" --version | head -1

    if [[ "$("$GCC" -dumpmachine)" == "riscv64-unknown-elf" ]]; then
        pass "Bare-metal RISC-V compiler target"
    else
        fail "Bare-metal RISC-V compiler target"
    fi
fi

echo
echo "===== HTIF RUNTIME ====="

if [[ -x "$GCC" ]]; then
    for name in htif_nano.specs htif.ld libgloss_htif.a; do
        location="$("$GCC" -print-file-name="$name")"
        check_file "$location" "$name"
    done
fi

echo
echo "===== OPENROAD ====="

check_executable "$OPENROAD" "OpenROAD executable"

echo
echo "===== HEALTH-SLM OPTIMIZER ====="

check_file "$OPT/requirements-optimizer.txt" "Optimizer dependencies"

if git -C "$OPT" rev-parse --verify HEAD >/dev/null 2>&1; then
    pass "Optimizer Git repository"
else
    fail "Optimizer Git repository"
fi

echo
echo "===== NATIVE LLAMA.CPP ====="

check_executable "$NATIVE/llama-cli" "Native llama-cli"
check_executable "$NATIVE/llama-server" "Native llama-server"
check_architecture "$NATIVE/llama-cli" "x86-64" "Native llama-cli"

echo
echo "===== RISC-V LINUX LLAMA.CPP ====="

check_executable "$RISCV_LLAMA/llama-cli" "RISC-V llama-cli"
check_architecture "$RISCV_LLAMA/llama-cli" "RISC-V" "RISC-V llama-cli"

echo
echo "===== CHIPYARD BENCHMARK ====="

check_file "$BENCH" "FPBench executable"
check_architecture "$BENCH" "RISC-V" "FPBench"

echo
echo "===== REPRODUCIBILITY METADATA ====="

check_file \
    "$REPO/inventory/health-slm-model.sha256" \
    "Health-SLM model checksum"

check_file \
    "$REPO/inventory/fpbench-rebuilt-verification.txt" \
    "FPBench verification evidence"

echo
echo "===== SIMULATION STATUS ====="

if [[ "$MODE" == "--local" ]]; then
    SIM="$CHIP/sims/verilator/simulator-chipyard.harness-HealthSLMNoScratchpadSimConfig"
    check_executable "$SIM" "Existing Health-SLM Verilator simulator"

    echo "NOTE: This script checks the simulator binary but does not run it."
else
    echo "NOTE: Bootstrap does not yet reconstruct the Verilator simulator."
fi

echo
echo "============================================"
echo " VERIFICATION SUMMARY"
echo "============================================"
echo "Passed checks: $PASSES"
echo "Failed checks: $FAILURES"

if [[ "$FAILURES" -ne 0 ]]; then
    echo "RESULT: ENVIRONMENT VERIFICATION FAILED"
    exit 1
fi

echo "RESULT: CHECKED COMPONENTS VERIFIED"
echo "NOTE: This is not a full inference or FPGA validation."

#!/usr/bin/env bash
set -euo pipefail

MODE="${1:---plan}"
ROOT="${HEALTH_SLM_BUILD_ROOT:-$HOME/health-slm-repro-build}"

if [[ "$MODE" != "--plan" && "$MODE" != "--run" ]]; then
    echo "Usage: $0 --plan|--run"
    exit 1
fi

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS="$REPO/scripts"

CHIP="$ROOT/chipyard"
RISCV_SOURCE="$ROOT/riscv-gnu-toolchain"
RISCV_PREFIX="$ROOT/riscv-medany"
HTIF_SOURCE="$ROOT/libgloss-htif"
HTIF_BUILD="$ROOT/htif-build"
OPENROAD="$ROOT/openroad-flow"
OPENROAD_PREFIX="$ROOT/openroad-install"
OPTIMIZER="$ROOT/health-slm-optimizer"

HTIF_PIN="177ce07db657bd674286d4aa06443166d33135e5"

stage() {
    echo
    echo "===== $1 ====="
}

run() {
    printf 'COMMAND:'
    printf ' %q' "$@"
    printf '\n'

    if [[ "$MODE" == "--run" ]]; then
        "$@"
    fi
}

echo "============================================"
echo " HEALTH-SLM REPRODUCIBILITY BOOTSTRAP"
echo "============================================"
echo "Mode: $MODE"
echo "Build root: $ROOT"
echo "Repository: $REPO"

if [[ "$MODE" == "--run" ]]; then
    if [[ -e "$ROOT" ]]; then
        echo "ERROR: Build root already exists."
        echo "Choose a new HEALTH_SLM_BUILD_ROOT."
        echo "Existing files will not be overwritten."
        exit 1
    fi

    for TOOL in git cmake make ninja; do
        command -v "$TOOL" >/dev/null || {
            echo "ERROR: Missing prerequisite: $TOOL"
            exit 1
        }
    done
fi

stage "0. Verify host prerequisites"
run "$SCRIPTS/preflight.sh"

stage "1. Create isolated build directory"
run mkdir -p "$ROOT"

stage "2. Reconstruct Chipyard"
run "$SCRIPTS/setup_chipyard.sh" "$CHIP"
run "$SCRIPTS/setup_core_submodules.sh" "$CHIP"
run "$SCRIPTS/setup_arty_fpga_shells.sh" "$CHIP"

stage "3. Install Chipyard Conda environment"
run "$SCRIPTS/setup_chipyard_conda.sh" "$CHIP" --install

stage "4. Reconstruct RISC-V GNU toolchain"
run "$SCRIPTS/setup_riscv_source.sh" "$RISCV_SOURCE"
run "$SCRIPTS/build_riscv_medany.sh" \
    "$RISCV_SOURCE" "$RISCV_PREFIX" --build

stage "5. Build HTIF runtime"
run git clone \
    https://github.com/ucb-bar/libgloss-htif.git \
    "$HTIF_SOURCE"
run git -C "$HTIF_SOURCE" checkout --detach "$HTIF_PIN"
run "$SCRIPTS/build_htif.sh" \
    "$HTIF_SOURCE" "$RISCV_PREFIX" "$HTIF_BUILD"

stage "6. Compile Chipyard FPBench"
run "$SCRIPTS/build_fpbench.sh" \
    --build "$CHIP" "$RISCV_PREFIX" "$ROOT/fpbench"

stage "7. Reconstruct OpenROAD"
run "$SCRIPTS/setup_openroad_source.sh" "$OPENROAD"

stage "8. Initialize OpenROAD engine submodules"
run git -C "$OPENROAD/tools/OpenROAD" \
    submodule update --init --recursive

stage "9. Build OpenROAD"
run "$SCRIPTS/build_openroad.sh" \
    --build "$OPENROAD/tools/OpenROAD" "$OPENROAD_PREFIX"

stage "10. Install Health-SLM optimizer"
run "$SCRIPTS/setup_optimizer.sh" \
    --install "$OPTIMIZER"

stage "11. Build native llama.cpp"
run "$SCRIPTS/build_llama_cpp.sh" \
    --build native \
    https://github.com/ggml-org/llama.cpp.git \
    "$ROOT/llama-native"

stage "12. Build RISC-V Linux llama.cpp"
run "$SCRIPTS/build_llama_cpp.sh" \
    --build riscv-linux \
    https://github.com/ggml-org/llama.cpp.git \
    "$ROOT/llama-riscv-linux"

echo
stage "13. Verify installed environment"
run "$SCRIPTS/verify_environment.sh" --root

echo "============================================"
echo " BOOTSTRAP SEQUENCE FINISHED: $MODE"
echo "============================================"

if [[ "$MODE" == "--plan" ]]; then
    echo "No installation commands were executed."
    echo "Review the sequence before using --run."
else
    echo "Core software installation stages completed."
    echo "Chipyard simulator and FPGA builds require separate validation."
fi

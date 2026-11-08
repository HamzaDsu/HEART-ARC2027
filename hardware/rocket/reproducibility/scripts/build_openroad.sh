#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
ENGINE="${2:-}"
PREFIX="${3:-$HOME/openroad-rebuild-test}"

if [[ "$MODE" != "--check" && "$MODE" != "--build" ]] || [[ -z "$ENGINE" ]]; then
    echo "Usage: $0 --check|--build ENGINE_DIR [INSTALL_PREFIX]"
    exit 1
fi

ENGINE="$(realpath "$ENGINE")"

echo "===== VERIFY OPENROAD SOURCE ====="

EXPECTED="943a4d2c9284cce991a916b67937157d890fe133"
ACTUAL="$(git -C "$ENGINE" rev-parse HEAD)"

if [[ "$ACTUAL" != "$EXPECTED" ]]; then
    echo "ERROR: OpenROAD source revision mismatch"
    exit 1
fi

echo "PASS: OpenROAD engine revision"

for SUB in src/sta third-party/abc third-party/slang-elab; do
    EXPECTED_SUB="$(git -C "$ENGINE" rev-parse "HEAD:$SUB")"
    ACTUAL_SUB="$(git -C "$ENGINE/$SUB" rev-parse HEAD)"

    if [[ "$EXPECTED_SUB" != "$ACTUAL_SUB" ]]; then
        echo "ERROR: Submodule revision mismatch: $SUB"
        exit 1
    fi

    echo "PASS: $SUB"
done

if [[ -n "$(git -C "$ENGINE" status --porcelain --untracked-files=no)" ]]; then
    echo "ERROR: OpenROAD source contains tracked modifications"
    exit 1
fi

echo "PASS: Source working tree clean"

echo "===== VERIFY BUILD TOOLS ====="

for TOOL in bazelisk git gcc g++; do
    command -v "$TOOL" >/dev/null || {
        echo "ERROR: Missing $TOOL"
        exit 1
    }
    echo "PASS: $TOOL"
done

if [[ "$PREFIX" != /* ]]; then
    echo "ERROR: Installation prefix must be an absolute path"
    exit 1
fi

if [[ -e "$PREFIX" ]]; then
    echo "ERROR: Installation prefix already exists: $PREFIX"
    exit 1
fi

echo "===== BUILD CONFIGURATION ====="
echo "Source: $ENGINE"
echo "Installation: $PREFIX"
echo "Build system: Bazel"
echo "Jobs: 2"
echo "GUI: Disabled"

if [[ "$MODE" == "--check" ]]; then
    echo "===== OPENROAD BUILD PRECHECK PASSED ====="
    exit 0
fi

echo "===== BUILD OPENROAD ====="

(
    cd "$ENGINE"

    ./etc/Build.sh \
        -no-gui \
        -no-tests \
        -threads=2 \
        "-prefix=$PREFIX" \
)

echo "===== VERIFY INSTALLED OPENROAD ====="

test -x "$PREFIX/bin/openroad"

"$PREFIX/bin/openroad" -version

echo "===== OPENROAD BUILD COMPLETE ====="

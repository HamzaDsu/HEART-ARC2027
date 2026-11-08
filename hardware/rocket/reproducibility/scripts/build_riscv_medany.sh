#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "Usage: $0 SOURCE_DIR INSTALL_PREFIX --check|--build"
    exit 1
fi

SRC="$(realpath "$1")"
PREFIX="$(realpath -m "$2")"
MODE="$3"

PIN="d118e5335a33d4dc77fdc64e5a5223931ab422a0"

if [[ "$MODE" != "--check" && "$MODE" != "--build" ]]; then
    echo "ERROR: Invalid mode"
    exit 1
fi

echo "===== VERIFY RISC-V TOOLCHAIN SOURCE ====="

ACTUAL="$(git -C "$SRC" rev-parse HEAD)"

if [ "$ACTUAL" != "$PIN" ]; then
    echo "ERROR: Unexpected toolchain revision"
    exit 1
fi

echo "PASS: Toolchain revision"

for SUB in binutils gcc newlib; do
    EXPECTED="$(git -C "$SRC" rev-parse "HEAD:$SUB")"
    ACTUAL="$(git -C "$SRC/$SUB" rev-parse HEAD)"

    if [ "$EXPECTED" != "$ACTUAL" ]; then
        echo "ERROR: $SUB revision mismatch"
        exit 1
    fi

    echo "PASS: $SUB"
done

echo "===== VERIFY BUILD PREREQUISITES ====="

for TOOL in make gcc g++ git; do
    command -v "$TOOL" >/dev/null || {
        echo "ERROR: Missing $TOOL"
        exit 1
    }
    echo "PASS: $TOOL"
done

if [ -e "$PREFIX" ]; then
    echo "ERROR: Installation prefix already exists:"
    echo "$PREFIX"
    exit 1
fi

echo "===== COMPILER BUILD CONFIGURATION ====="

echo "Source: $SRC"
echo "Install: $PREFIX"
echo "Architecture: rv64gc"
echo "ABI: lp64d"
echo "Code model: medany"
echo "ISA specification: 20191213"
echo "Multilib: disabled"
echo "Runtime: Newlib"

if [ "$MODE" = "--check" ]; then
    echo
    echo "PASS: Source and basic prerequisites verified"
    echo "No compiler was built."
    exit 0
fi

echo "===== CONFIGURE COMPILER ====="

cd "$SRC"

./configure \
    --prefix="$PREFIX" \
    --disable-linux \
    --disable-multilib \
    --with-arch=rv64gc \
    --with-abi=lp64d \
    --with-cmodel=medany \
    --with-isa-spec=20191213 \
    --with-languages=c,c++

echo "===== BUILD NEWLIB TOOLCHAIN ====="

make -j "${JOBS:-2}"

echo "===== VERIFY INSTALLED COMPILER ====="

GCC="$PREFIX/bin/riscv64-unknown-elf-gcc"

"$GCC" --version
"$GCC" -dumpmachine
"$GCC" -v 2>&1

echo "===== COMPILE SMOKE TEST ====="

SMOKE_DIR="$(mktemp -d)"
trap 'rm -rf "$SMOKE_DIR"' EXIT

printf 'int add(int a,int b){return a+b;}\n' \
    > "$SMOKE_DIR/repro_smoke.c"

"$GCC" -march=rv64gc -mabi=lp64d \
    -mcmodel=medany \
    -c "$SMOKE_DIR/repro_smoke.c" \
    -o "$SMOKE_DIR/repro_smoke.o"

"$PREFIX/bin/riscv64-unknown-elf-readelf" \
    -h "$SMOKE_DIR/repro_smoke.o" | grep -E 'Class:|Machine:'

echo "===== RISC-V COMPILER BUILD COMPLETE ====="

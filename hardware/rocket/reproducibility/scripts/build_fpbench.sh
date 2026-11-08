#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
CHIP="${2:-}"
PREFIX="${3:-}"
OUTPUT="${4:-}"

if [[ "$MODE" != "--check" && "$MODE" != "--build" ]] ||
   [[ -z "$CHIP" || -z "$PREFIX" || -z "$OUTPUT" ]]; then
    echo "Usage: $0 --check|--build CHIPYARD_DIR RISCV_PREFIX OUTPUT_DIR"
    exit 1
fi

GCC="$PREFIX/bin/riscv64-unknown-elf-gcc"
READELF="$PREFIX/bin/riscv64-unknown-elf-readelf"
SOURCE="$CHIP/tests"

echo "===== VERIFY FPBENCH DEPENDENCIES ====="

test -x "$GCC"
test -x "$READELF"
test -f "$SOURCE/fpbench.c"
test -f "$SOURCE/atexit-workaround.c"

echo "PASS: Compiler and benchmark sources"

for FILE in htif_nano.specs htif.ld libgloss_htif.a; do
    LOCATION="$("$GCC" -print-file-name="$FILE")"

    if [[ ! -f "$LOCATION" ]]; then
        echo "ERROR: Missing HTIF dependency: $FILE"
        exit 1
    fi

    echo "PASS: $FILE"
done

if [[ "$MODE" == "--check" ]]; then
    echo "===== FPBENCH PRECHECK PASSED ====="
    exit 0
fi

echo "===== COMPILE FPBENCH ====="

mkdir -p "$OUTPUT"

"$GCC" \
    -O2 \
    -std=gnu99 \
    -march=rv64imafd \
    -mabi=lp64d \
    -mcmodel=medany \
    -fno-common \
    -fno-builtin-printf \
    -specs=htif_nano.specs \
    -static \
    -Wl,--wrap=atexit \
    "$SOURCE/fpbench.c" \
    "$SOURCE/atexit-workaround.c" \
    -o "$OUTPUT/fpbench.riscv"

echo "===== VERIFY EXECUTABLE ====="

test -s "$OUTPUT/fpbench.riscv"

file "$OUTPUT/fpbench.riscv"

"$READELF" -h "$OUTPUT/fpbench.riscv" |
    grep -q 'Machine:.*RISC-V'

sha256sum "$OUTPUT/fpbench.riscv"

echo "===== FPBENCH BUILD COMPLETE ====="

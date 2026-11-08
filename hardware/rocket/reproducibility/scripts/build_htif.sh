#!/usr/bin/env bash
set -euo pipefail

SOURCE="${1:-$HOME/libgloss-htif-rebuild-source}"
TOOLCHAIN="${2:-$HOME/riscv-medany-rebuild-test}"
BUILD="${3:-$HOME/htif-rebuild-test}"

PIN="177ce07db657bd674286d4aa06443166d33135e5"
SYSROOT="$TOOLCHAIN/riscv64-unknown-elf"

echo "===== VERIFY HTIF SOURCE ====="

test "$(git -C "$SOURCE" rev-parse HEAD)" = "$PIN"
echo "PASS: HTIF source revision"

echo "===== VERIFY RISC-V COMPILER ====="

test -x "$TOOLCHAIN/bin/riscv64-unknown-elf-gcc"
export PATH="$TOOLCHAIN/bin:$PATH"

riscv64-unknown-elf-gcc --version | head -1

echo "===== CONFIGURE HTIF ====="

mkdir -p "$BUILD"
cd "$BUILD"

"$SOURCE/configure" \
    --prefix="$SYSROOT" \
    --host=riscv64-unknown-elf \
    --disable-multilib

echo "===== BUILD HTIF ====="

make -j2

echo "===== INSTALL HTIF ====="

make install

echo "===== VERIFY INSTALLED FILES ====="

for FILE in htif_nano.specs htif.ld libgloss_htif.a; do
    test -s "$SYSROOT/lib/$FILE"
    echo "PASS: $FILE"
done

echo "===== HTIF LINK TESTS ====="

make check

echo "===== HTIF BUILD AND LINK TESTS COMPLETE ====="

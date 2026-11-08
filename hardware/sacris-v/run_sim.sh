#!/usr/bin/env bash
# Build and run the Verilator simulation of one SaCriS-V configuration.
#
#   ./run_sim.sh [<dir-with-instruction_mem.bin-and-data_mem.bin>]
#
#   With a directory: the memories are preloaded with the program and the core
#   starts at 0x8000_0000 (no bootloader). The run ends when the program writes
#   to SIMULATION_END (0x6400_0F04).
#   Without a directory: bootloader mode. The core starts at 0 in the BootROM,
#   prints 'B' and then waits for an image over the UART, which the testbench
#   cannot feed, so this only smoke-tests the boot path.
#
# Environment:
#   SACRIS_CONFIG=<name>   configuration from TopParams.all (default: I)
#   SACRIS_REBUILD=1       re-elaborate even if generated/<name>/TopLevel.sv exists
#   SACRIS_V_VCD=<file>    write a VCD waveform
set -e

if [ $# -gt 1 ]; then
    sed -n '2,17p' "$0"; exit 1
fi

BIN_DIR="${1:-}"
CFG="${SACRIS_CONFIG:-I}"
SV="generated/${CFG}/TopLevel.sv"

if [ "${SACRIS_REBUILD:-0}" = "1" ] || [ ! -f "$SV" ]; then
    sbt "runMain sacris_v.TopLevel ${CFG}"
fi
[ -f "$SV" ] || { echo "ERROR: $SV missing after elaboration"; exit 1; }

VERILATOR_CFLAGS=""
# dual-issue configurations use the 64-bit IMEM; the testbench needs to know
if grep -q "InstructionMem64" "$SV"; then
    VERILATOR_CFLAGS="-CFLAGS -DSACRIS_DUAL_ISSUE"
fi
# memories with ECC check bits need the ECC-aware preloader
if grep -q "checkMem_ext" "$SV"; then
    VERILATOR_CFLAGS="$VERILATOR_CFLAGS -CFLAGS -DSACRIS_MEM_ECC"
fi

rm -rf obj_dir
verilator -Wall --trace -DENABLE_INITIAL_MEM_ --public-flat-rw -Wno-fatal --inline-mult 10000000 \
    $VERILATOR_CFLAGS -cc "$SV" --exe sim/tb_TopLevel.cpp
make -j"$(nproc)" -C obj_dir -f VTopLevel.mk VTopLevel

if [ -n "$BIN_DIR" ]; then
    for f in instruction_mem.bin data_mem.bin; do
        [ -f "$BIN_DIR/$f" ] || { echo "ERROR: $BIN_DIR/$f missing"; exit 1; }
    done
    echo "Running $CFG with program from $BIN_DIR"
    exec ./obj_dir/VTopLevel "$BIN_DIR"
else
    echo "Running $CFG in bootloader mode"
    exec ./obj_dir/VTopLevel
fi

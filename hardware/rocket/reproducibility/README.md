# Health-SLM Rocket Chip Reproducibility

Reproducible software environment for the thesis project
involving a domain-specific Health Small Language Model,
RISC-V Rocket Chip, Chipyard, FPGA-aware optimization,
and OpenROAD.

## Repository Contents

- `scripts/` — installation, build, and verification scripts
- `patches/` — custom Chipyard and hardware configurations
- `inventory/` — pinned revisions, checksums, and test evidence

## Main Components

- Chipyard and Rocket Chip
- RISC-V GCC 16.1.0 with medany support
- HTIF bare-metal runtime
- OpenROAD
- Health-SLM hardware optimizer
- Native and RISC-V Linux llama.cpp
- Chipyard FPBench benchmark
- Verilator simulation verification

## Prerequisites

The captured Conda environment requires Linux x86-64
with glibc 2.39 or newer.

Additional tools include Conda, CMake, Ninja, GCC,
RISC-V Linux cross-compilers, and Bazelisk.

Vivado 2022.2 is a separate proprietary dependency
for FPGA workflows.

## Verify Prerequisites

    ./scripts/preflight.sh

## Inspect Installation Plan

    ./scripts/bootstrap.sh --plan

The full bootstrap installation requires substantial
disk space and computation.

The full clean-install run was intentionally stopped
to conserve disk space. Therefore, end-to-end
installation reproducibility remains unverified.

## Verified Results

Independent component rebuilds succeeded for:

- RISC-V GCC and HTIF
- OpenROAD
- Native llama.cpp
- RISC-V Linux llama.cpp
- Health-SLM optimizer

The optimizer passed 104 automated tests.

The independently rebuilt FPBench matched the original
Chipyard Verilator simulation results:

- FMA operations: 10,000
- Total benchmark cycles: 60,010
- Cycles per operation: 6.001

The local environment verification passed 21 checks.

These measurements are simulation results, not physical
FPGA performance measurements.

## Health-SLM Model

The GGUF model is intentionally excluded from Git.

Model checksum information is preserved in:

    inventory/health-slm-model.sha256

The original model artifact must be obtained separately
and verified against its recorded SHA-256 checksum.

## Deployment Distinction

The RISC-V Linux llama.cpp executable is intended for
RISC-V Linux. It is not a bare-metal Rocket Chip program.

The full Health-SLM model has not been demonstrated
running on the Rocket core or FPGA.

## Remaining Validation

- Full clean-install bootstrap verification
- Independent reconstruction of Verilator simulators
- FPGA synthesis and implementation validation
- Actual Health-SLM inference on target hardware
- Hardware power, timing, and performance measurement

## Branch

    final-slm-rocket-chip

# Reproducing the hardware workflow on Nexys Video

This is a **source-only** runbook. It deliberately contains no experimental
results or binary deliverables. The team must verify exact Chipyard and Vivado
build commands against the provided source and board-specific scripts before
publishing the paper's claimed reproducibility path.

## 1. Prerequisites

- Linux/WSL environment with a working RISC-V toolchain, Python 3, and Git.
- For Rocket, the pinned Chipyard/Verilator/FIRRTL toolchain used by the project.
- For SaCriS-V, Java/sbt, Scala/Chisel dependencies, Verilator, and a matching
  RV32 C toolchain (including a soft-float multilib for the IMB variant).
- Xilinx Vivado for the Nexys Video XC7A200T device (2022.2 was used in the
  original lab); openFPGALoader and the board's USB/JTAG drivers.
- A UART connection and the original project's UART-TSI loader for Rocket.

Tool versions and the hardware scripts must be checked on the local machine.

## 2. Inspect the code and regenerate RTL

- `sacris-v/README.md` describes SaCriS-V build requirements.
- `sacris-v/build.sbt` and `sacris-v/project/` contain its Chisel build setup.
- `sacris-v/run_sim.sh` and `sacris-v/sim/` contain the RTL test harness.
- `sacris-v/software/health_slm/Makefile` builds the test program from source.
- `sacris-v/fpga/synthesis/` contains any previously committed synthesis TCL
  scripts relocated from generated result folders. Check their input/output
  paths before use; the scripts are preserved as source, not proof of a run.
- Rocket FPGA build scripts and custom sources, if available in the original
  repository, are in `rocket/`.

Do not treat `sacris-v/generated/` or bitstreams as source prerequisites;
regenerate them with the appropriate tools.

## 3. Simulate before synthesizing

Rebuild the relevant software workload from its Makefile, generate RTL for the
chosen configuration, and run the supplied Verilator harness. Confirm the
expected checksum and stop condition before using any cycle measurement.
The actual simulator options are defined in `sacris-v/run_sim.sh`.

## 4. Synthesize / implement

For each chosen processor configuration:

1. Select the corresponding generated RTL and Nexys Video constraints.
2. Run the repository's Vivado synthesis and, for physical deployment,
   placement and routing with a valid clock constraint.
3. Check utilization, setup/hold timing, and generated bitstream validity.
4. Save reports **outside version control**; `hardware/.gitignore` excludes
   these outputs. No bitstream is included in the source-only repository.

**Synthesis resource counts are not proof of timing closure.** SaCriS-V was
validated by RTL simulation and synthesis, not by physical board execution.

## 5. Program and run Rocket on the physical FPGA

This section applies to the prior Rocket Nexys Video workflow, **not** to a
claimed SaCriS-V board deployment.

```bash
# After generating the bitstream from the verified Vivado build:
openFPGALoader -b nexysVideo /absolute/path/to/generated/rocket.bit
```

Next, connect to the board UART and invoke the original `rocket/fpga/uart_tsi`
loader with the actual RISC-V executable and the UART device from your setup.
UART device numbering may change. Inspect `rocket/fpga/` for the corresponding
scripts and the original harness instructions; the exact invocation and test
payload must be recorded before publication.

## 6. Record and validate a fresh experiment

Check end-of-program status, memory integrity, checksums, and, where available,
cycles, retired instructions, IPC/CPI, and output classifications. Retain
measurements in an external artifact/archive or in locally ignored `results/`.
Do not commit generated experimental results into this source-only folder.

## 7. Optimizer interface

```bash
cd hardware
python3 -m optimizer.unified_cli --processor rocket --help
python3 -m optimizer.unified_cli --processor sacris-v --help
# After regenerating the required measurements:
python3 -m optimizer.unified_cli --processor sacris-v --objective all
```

The SaCriS-V measured selector needs the benchmark and synthesis input files
that the original experiment produced. Because this publication tree excludes
those files, the selection command is **not immediately executable** from a
fresh clone. Provide a documented measurement-generation step or an approved
separately hosted data archive to make the end-to-end pipeline reproducible.

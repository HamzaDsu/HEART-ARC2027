# Hardware — RISC-V / FPGA

This is the reserved workspace for the HEART paper's hardware collaborator.

Please contribute only the paper-relevant RISC-V / Rocket / Chipyard configuration code, workload benchmarks, simulation drivers, FPGA constraint/build scripts, and concise evidence files needed to reproduce reported hardware measurements. Do not commit full Vivado build trees, generated bitstreams without prior agreement, huge waveform traces, private credentials, or unrelated platform experiments.

Suggested areas (create as needed): `rtl/`, `configs/`, `benchmarks/`, `scripts/`, and `results/`.

Work on a feature branch and open a pull request so software and hardware contributions can be reviewed independently. Document board part, tool versions, build commands, input data, and measured-versus-estimated evidence clearly.

## Hardware source included with the paper

This folder contains the source-only RISC-V/FPGA component of HEART (ARC 2027).
The original hardware contributor guidance above is retained.

- `rocket/`: Rocket/Chipyard-related configuration, FPGA and simulation source,
  reproducibility scripts and the available model kernel source.
- `sacris-v/`: SaCriS-V Chisel sources, simulation harness, benchmark source,
  dependencies and synthesis scripts.
- `optimizer/`: shared Rocket/SaCriS-V command-line entry point and optimization source.
- `FPGA_RUNBOOK.md`: environment setup, simulation, synthesis, programming workflow,
  explicit validation boundaries and outstanding reproduction steps.

From the repository root:

```bash
cd hardware
python3 -m optimizer.unified_cli --processor rocket --help
python3 -m optimizer.unified_cli --processor sacris-v --help
```

**Publication scope:** This repository omits results, model weights, compiled
executables, synthesis reports, generated RTL, bitstreams and Vivado checkpoints.
The measured optimizer selectors require recreated measurement inputs, and the
exact Rocket UART-TSI invocation and complete FPGA build must be validated
against the local board/toolchain before claiming a fresh-clone, end-to-end run.
SaCriS-V physical FPGA deployment was not performed in the reported work.

Retain upstream copyright notices and third-party licenses. Verify release
permissions and the instructions before making the repository public.

# Hardware — RISC-V / FPGA

**HEART | Hardware research artifact for ARC 2027**

This directory contains the **RISC-V processor, FPGA, benchmark, and architecture-optimization source code** associated with the hardware portion of HEART. The aim is to make the reported hardware methodology inspectable and, where the required tools and measurement inputs are available, reproducible.

The repository brings together two **separate processor flows**, Rocket Chip and SaCriS-V, under a shared optimization interface. **Their evaluation methods are not interchangeable:** the original work included physical Nexys Video FPGA execution with Rocket, whereas SaCriS-V was evaluated through RTL simulation and Vivado synthesis. This release does not claim physical SaCriS-V deployment or an end-to-end fresh-clone FPGA reproduction.

**Start here:** [FPGA setup and execution guide](FPGA_RUNBOOK.md) · [Rocket source](rocket/) · [SaCriS-V source](sacris-v/) · [Optimizer](optimizer/)

## 1. Repository layout

```text
hardware/
├── README.md                 # This guide
├── FPGA_RUNBOOK.md           # Board setup, RTL/FPGA workflow, UART-TSI
├── rocket/                   # Rocket/Chipyard overlays, benchmarks, scripts
│   ├── benchmarks/           # Quantized kernels and workload-related source
│   ├── chipyard/             # Chipyard-related source/configurations
│   ├── reproducibility/     # Setup/build helpers and board configuration overlays
│   ├── scripts/             # Code-generation and benchmark helpers
│   └── tests/               # Software-side optimizer/selection tests
├── sacris-v/                # SaCriS-V processor and peripheral source
│   ├── src/                 # Chisel RTL generation source
│   ├── sim/                 # Verilator simulation harness
│   ├── software/            # Small RISC-V test and benchmark programs
│   ├── fpga/                # Nexys Video constraints and synthesis scripts
│   └── dependencies/        # Included source dependencies and notices
├── optimizer/               # Shared processor/objective-selection code
│   └── unified_cli.py       # Common command-line entry point
└── scripts/                 # FPGA environment and UART-TSI helpers
```

The full Chipyard framework, vendor toolchain, generated RTL, bitstreams, numerical measurement results, and trained model artifacts are **not** vendored into this directory. Some included configuration files and build scripts are **overlays** for a separate, compatible Chipyard checkout, not a complete standalone hardware project.

## 2. Evaluation paths and scope

| Flow | What is included | How it was evaluated | Important boundary |
| --- | --- | --- | --- |
| **Rocket Chip** | Custom Chipyard configuration overlays, benchmarks, RISC-V workload code, setup/build helpers | Verilator/FPGA-related development and physical Rocket-based Nexys Video boot and workload execution in the original lab | The published source has **not** been rebuilt and executed from a clean clone end to end |
| **SaCriS-V** | Chisel processor/peripheral sources, simulation harness, small quantized-kernel benchmark sources, Vivado synthesis configurations | RTL benchmark simulations and post-synthesis FPGA resource analysis | **No physical SaCriS-V FPGA boot or full-model inference** is claimed; synthesis does not establish timing closure |
| **Unified optimizer** | Architecture/objective-selection code for both processors | Selection using separately gathered workload and hardware evidence | Measured-data selection needs regenerated benchmark/resource inputs, which are not shipped here |

The RTL quantized-kernel workloads (including Q3_K × Q8_K operations) are **representative microbenchmarks**, not a substitute for full neural-network inference on every processor. Numeric performance, utilization, and model-related findings should be interpreted using the methods and experimental scope defined in the ARC paper.

## 3. Supported research objectives

The shared optimization work considers three architectural goals:

- **Performance:** prioritize measured/estimated execution cost subject to available architecture choices and constraints.
- **Balanced:** trade execution cost against FPGA resource use.
- **Resources:** prioritize lower hardware utilization, while preserving a viable implementation.

These are **selection objectives**, not claims of independently measured power or deployed physical FPGA performance. Processor-specific candidate sets and workload assumptions differ. Do not compare Rocket and SaCriS-V cycle counts as if they came from the same runtime or measurement setup.

## 4. Requirements

The specific requirements depend on the path being reproduced. Typical tools in the original hardware environment included:

| Tool or environment | Purpose |
| --- | --- |
| Linux / Ubuntu under WSL | Build and experiment host |
| Python 3 | Optimizer, profiling and preparation scripts |
| Chipyard and compatible Java/Scala build tools | Rocket/FPGA RTL elaboration |
| Verilator and C/C++ toolchain | RTL simulation and benchmark harness |
| RISC-V bare-metal cross compiler | Building target RISC-V ELF programs |
| AMD/Xilinx Vivado 2022.2 | FPGA synthesis/implementation and report generation |
| Digilent Nexys Video (Artix-7 `XC7A200T-SBG484-1`) | Physical Rocket FPGA evaluation |
| `openFPGALoader`, `uart_tsi` | Programming and host-to-FPGA test loading |
| `usbipd-win` (when using WSL) | Passing JTAG/UART USB devices from Windows to WSL |

**Version compatibility matters.** Confirm the Chipyard revision, board support, RISC-V ISA/ABI, serial baud rate, constraints, and host-loader version before using any generated artifact. See the [FPGA runbook](FPGA_RUNBOOK.md) and the [Rocket reproducibility notes](rocket/reproducibility/README.md) for the detailed workflow. Vivado is proprietary and must be installed separately.

## 5. Quick start: inspect the source and optimizer

From the **repository root**:

```bash
cd hardware

# Show the two supported processor interfaces.
python3 -m optimizer.unified_cli --processor rocket --help
python3 -m optimizer.unified_cli --processor sacris-v --help

# Check syntax of the included Python package without running experiments.
python3 -m compileall -q optimizer
```

The `--help` commands exercise the CLI entry point only. They do **not** reproduce the optimization selections or validate FPGA execution. The measured SaCriS-V selector, in particular, requires experiment-derived performance and synthesis inputs to be regenerated first. No fabricated or placeholder measurement files are supplied.

## 6. Rocket Chip / Nexys Video workflow

The Rocket path is based on **configurations and source overlays integrated into a matching external Chipyard checkout**. A high-level workflow is:

1. Prepare a compatible Chipyard checkout, RISC-V toolchain, FPGA board support, and environment according to `rocket/reproducibility/`.
2. Integrate the selected Rocket Scala configuration into Chipyard. Select a configuration compatible with the intended Nexys Video hardware, UART client, and target ISA.
3. Generate the RTL and, separately, build/inspect an FPGA implementation in Vivado. Check constraints, timing, resource usage, and bitstream provenance before loading it.
4. Compile a small compatible RISC-V test program (for example a Hello World or memory probe) before trying larger workloads.
5. Connect JTAG and UART, program the board with `openFPGALoader`, and load the target ELF using `uart_tsi`.
6. Check the test program's output and exit status against a known-good reference. Keep local logs, checksums and reports outside the source repository.

See **[FPGA_RUNBOOK.md](FPGA_RUNBOOK.md)** for board-detection commands, WSL USB forwarding, SRAM programming, the model-independent UART-TSI invocation, and the limitations of the original verification.

A typical host-side invocation, **after** a compatible bitstream has been loaded, is:

```bash
export UART_TSI=/absolute/path/to/uart_tsi
export FPGA_PORT=/dev/ttyUSBX
export WORKLOAD_ELF=/absolute/path/to/workload.riscv

"$UART_TSI" "+tty=$FPGA_PORT" +selfcheck "$WORKLOAD_ELF"
```

The ELF, UART device, bitstream and serial configuration must match the generated Rocket hardware. The paths are examples to be supplied by the person reproducing the experiment; no executable or bitstream is bundled here.

The repository also includes read-only preflight and configurable execution helpers:

```bash
# Run from the repository root, not from hardware/.
bash hardware/scripts/check_nexys_video.sh
bash hardware/scripts/run_nexys_uart_tsi.sh --help
```

The second command prints options; **do not program the FPGA using an unreviewed command**. See the runbook before real hardware use.

## 7. SaCriS-V RTL simulation and synthesis

The SaCriS-V tree contains the Chisel processor and periphery sources, a Verilator harness, small RISC-V test programs and quantized workload kernels. The research configurations include the processor variants referenced by `sacris-v/fpga/synthesis/*/synth.tcl`.

A reproduction should use the following stages:

1. Follow [`sacris-v/README.md`](sacris-v/README.md) to set up the compatible Scala/Chisel and Verilator environment.
2. Inspect `sacris-v/run_sim.sh`, `sacris-v/sim/`, and the relevant `sacris-v/software/` makefiles; adapt any original filesystem paths before running simulations.
3. Build and run the small quantized-kernel workload with the intended ISA, memory layout and compiler options.
4. Use the desired `sacris-v/fpga/synthesis/*/synth.tcl` configuration with compatible Vivado/device settings and inspect any timing/resource report locally.
5. Compare the output against the paper's defined methodology; distinguish simulation cycles, synthesis resource counts, and estimates.

**Do not interpret synthesis success as physical board execution or demonstrated timing closure.** SaCriS-V was not physically deployed in the reported work.

## 8. Reproducing the optimizer selections

The optimizer has a shared entry point, but **Rocket and SaCriS-V have different candidate definitions and evidence sources**. Reproduction requires regenerating the relevant benchmark performance inputs and FPGA utilization reports before requesting data-dependent selections. The source-only archive does not include the complete original experiment evidence, so a fresh clone alone cannot necessarily emit the paper's chosen configurations.

Review [`optimizer/unified_cli.py`](optimizer/unified_cli.py), the processor-specific optimization modules, and the hardware benchmark/synthesis drivers before executing a measured-data selection. Record the versioned configuration, toolchain, objective, workload, input hashes and candidate set in your local experiment log. Do not substitute arbitrary estimates for missing measurements.

## 9. Reproducibility and publication checklist

- [ ] Verify source provenance and check the exact commit used for the paper.
- [ ] Confirm third-party copyright notices, licenses and redistribution permissions.
- [ ] Record compatible Chipyard, Java/Scala, Verilator, GCC, Vivado and board-support versions.
- [ ] Verify RTL generation and simulator test execution on a clean environment.
- [ ] Rebuild, constrain and timing-check the Nexys Video bitstream from source.
- [ ] Repeat the Rocket boot/memory/workload checks on the physical board.
- [ ] Regenerate optimizer input measurements from the documented workload and synthesis flows.
- [ ] Verify the distinction between measured, estimated, simulated and physical results in paper and code.
- [ ] Confirm release timing and repository visibility with the paper's submission requirements.

**Current release boundary:** The original lab carried out FPGA programming and selected physical Rocket workload tests. A *fresh-clone → RTL → bitstream → ELF → FPGA execution* reproduction using only the publication source is **not yet validated**. This statement should be updated only after that complete workflow is repeated and checked.

## 10. Contributor guidance

This directory is the HEART paper's hardware workspace. Keep contributions limited to relevant RISC-V/Rocket/Chipyard and SaCriS-V configuration code, benchmark sources, simulator and synthesis scripts, board constraints, and clear reproduction instructions. Avoid full Vivado build trees, large waveform captures, credentials, generated artifacts, and unrelated platform experiments.

Coordinate updates with the software contributors before publishing changes to the shared `main` branch. Document the target board, software/tool versions, build instructions, reference input expectations, and whether any reported observation came from simulation, synthesis or physical hardware. Preserve upstream licenses and attribution.

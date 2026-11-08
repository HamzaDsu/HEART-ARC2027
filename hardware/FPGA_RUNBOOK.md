# FPGA setup and execution — Rocket / SaCriS-V

This guide describes the Nexys Video FPGA workflow for the HEART hardware
sources. It is **model-independent**: substitute any compatible RISC-V test
program or inference workload, provided its ISA, memory requirements, linker
script, runtime inputs, and UART interface match the selected Rocket design.

**Validation boundary:** The original lab verified JTAG detection and FPGA
programming, UART-TSI boot/smoke tests, memory checks, and workload execution
on a physical Rocket-based Nexys Video design. The publication repository has
**not** yet been validated end-to-end from a fresh clone through bitstream
creation and workload execution. SaCriS-V was simulated and synthesized, not
physically booted on the board. The two processor paths must not be conflated.

No model weights, input blobs, prebuilt RISC-V executables, bitstreams, Vivado
outputs, benchmark results, or proprietary tool distributions are included.
Generated numerical inputs required by optimizer selection must be recreated
from the documented benchmark/synthesis workflow.

## 1. Source layout and tools

- `rocket/reproducibility/chipyard/configs/`: custom Rocket/Chipyard Scala
  configuration source to integrate into a compatible Chipyard checkout.
- `rocket/benchmarks/`: benchmark kernels and workload source/preparation code.
- `rocket/reproducibility/scripts/`: environment, toolchain, and build helpers.
- `sacris-v/`: SaCriS-V processor RTL generator, simulation harness, and
  synthesis scripts.
- `optimizer/`: unified Rocket/SaCriS-V optimizer CLI.
- `scripts/check_nexys_video.sh`: read-only prerequisite and board inspection.
- `scripts/run_nexys_uart_tsi.sh`: parameterized FPGA programming/ELF loader.

Target board: Digilent **Nexys Video** with Artix-7
`XC7A200T-SBG484-1`. The original setup used Vivado 2022.2, Chipyard,
a RISC-V cross toolchain, `openFPGALoader`, `uart_tsi`, Ubuntu/WSL, and
`usbipd-win`. Check source/tool revisions and third-party licenses before
public redistribution.

## 2. Attach board interfaces (Windows + WSL)

The Windows PowerShell administrator may need to pass both USB interfaces to
WSL. Identify BUSIDs using `usbipd list`; do not copy machine-specific IDs.

```powershell
wsl -l -v
usbipd list
usbipd bind --busid <JTAG_BUSID>
usbipd bind --busid <UART_BUSID>
usbipd attach --wsl --busid <JTAG_BUSID>
usbipd attach --wsl --busid <UART_BUSID>
```

Power the FPGA and connect the appropriate JTAG/programming and serial UART
interfaces. In Ubuntu/WSL:

```bash
lsusb
ls -l /dev/ttyUSB* 2>/dev/null || true
openFPGALoader --list-boards | grep -i nexys
openFPGALoader --detect -b nexysVideo
bash hardware/scripts/check_nexys_video.sh  # from repository root
```

For reference, the original lab detected JTAG IDCODE `0x3636093` and used a
serial interface enumerated as `/dev/ttyUSB2`. **The device path is not
portable**: determine the correct port for your machine and verify permissions.

## 3. Generate Rocket hardware and build a compatible bitstream

The custom Scala files are source overlays, not a vendored Chipyard tree.
Follow `rocket/reproducibility/README.md` for environment setup, verify the
Chipyard revision, and install the relevant Scala configuration in its
`fpga/src/main/scala/nexysvideo/` package.

The following Chipyard command was used successfully for **Verilog generation**
with a custom fast-UART configuration:

```bash
cd "${CHIPYARD_DIR:-$HOME/chipyard}"
source env.sh
CONFIG=UnifiedResource1Core16K50FastUARTNexysVideoConfig
make -C fpga SUB_PROJECT=nexysvideo CONFIG="$CONFIG" verilog
```

The additional source
`rocket/reproducibility/chipyard/configs/UnifiedNexysVideoFastUARTConfigs.scala`
uses `WithUARTTSIClient(initBaudRate = 921600)`. The earlier baseline FPGA
bitstream used `UnifiedResource1Core16K50NexysVideoConfig` (without fast UART).
**Do not mix UART baud rate, bitstream, host loader, or hardware configuration.**

The repository does not yet provide a fresh-clone-validated, one-command
Vivado place-and-route/bitstream build. Use the matching Chipyard FPGA
Makefile and board packages; check implementation reports, constraints,
setup/hold timing, and bitstream provenance before programming.

## 4. Program the FPGA (bitstream supplied locally)

Set an **absolute path** to your locally built compatible bitstream:

```bash
export FPGA_BITSTREAM=/absolute/path/to/rocket_design.bit

test -s "$FPGA_BITSTREAM"
sha256sum "$FPGA_BITSTREAM"
openFPGALoader --detect -b nexysVideo
openFPGALoader -b nexysVideo -m "$FPGA_BITSTREAM"
```

`-m` programs volatile SRAM. Generated `.bit` files are intentionally not
included. Reset or reprogram the board between runs as required by the design.

## 5. Load a generic RISC-V test or inference executable

Compile the application for the **selected Rocket ISA/ABI** and its memory
map. Ensure embedded or externally supplied runtime data is available and
compatible with DDR capacity. First validate the board with a small known-good
Hello World or memory probe, before running a large workload.

Supply paths to your own loader, target ELF, and serial device:

```bash
export UART_TSI=/absolute/path/to/uart_tsi
export FPGA_PORT=/dev/ttyUSBX
export WORKLOAD_ELF=/absolute/path/to/workload.riscv

# From HEART-ARC2027 repository root; inspect commands before interacting with hardware:
bash hardware/scripts/run_nexys_uart_tsi.sh \
  --bitstream "$FPGA_BITSTREAM" \
  --uart-tsi "$UART_TSI" \
  --elf "$WORKLOAD_ELF" \
  --port "$FPGA_PORT" \
  --flash --dry-run

# Remove --dry-run only after checking the output, connections, and configuration.
bash hardware/scripts/run_nexys_uart_tsi.sh \
  --bitstream "$FPGA_BITSTREAM" \
  --uart-tsi "$UART_TSI" \
  --elf "$WORKLOAD_ELF" \
  --port "$FPGA_PORT" \
  --flash
```

The same host loader can be used directly if the FPGA is already programmed:

```bash
"$UART_TSI" "+tty=$FPGA_PORT" +selfcheck "$WORKLOAD_ELF"
```

**Provenance distinction:** The `+tty=... +selfcheck` UART-TSI invocation
pattern was exercised in the original lab. The generic parameterized helper
and public source directory have not yet undergone an independent, end-to-end
physical reproduction test. A successful program exit does not itself prove
numerical inference correctness; compare output against a trusted reference.

## 6. SaCriS-V simulation and FPGA synthesis (separate from Rocket execution)

From `hardware/sacris-v/`, consult `README.md`, `run_sim.sh`, and
`software/health_slm/Makefile` for RTL simulation and quantized kernel tests.
The original four configurations were `DualIMFB_SLM_128K`, `IMB_SLM_128K`,
`IMF_SLM_128K`, and `IMF_TMR_SLM_128K`. Their synthesis source is in
`fpga/synthesis/*/synth.tcl`. Inspect the scripts' original path assumptions
and adapt them to the publication layout before executing.

SaCriS-V performance numbers were gathered using RTL kernel simulation,
not physical neural-network inference. Post-synthesis resources must not be
presented as a completed timing-closed or programmed FPGA deployment.

## 7. Unified optimizer

From the repository root:

```bash
cd hardware
python3 -m optimizer.unified_cli --processor rocket --help
python3 -m optimizer.unified_cli --processor sacris-v --help
```

The measured-data selectors need regenerated input traces/resource summaries.
The source-only release does not include all experiment inputs required to
recreate optimizer recommendations automatically. Running `--help` tests
CLI parsing, not FPGA operation or result reproducibility.

## 8. Evidence and public-release checklist

Keep experimental outputs in a **separate local, ignored directory**: hardware
revision, tool versions, device and bitstream hash, compile/link commands,
program exit code, UART transcript, reference output checks, cycle counters,
resource utilization, Vivado timing and relevant power-assumption reports.
Do not commit these large/generated files to the source-only repository.

- [ ] Pin and validate Chipyard/toolchain/tool versions on a clean machine.
- [ ] Rebuild the bitstream with the correct board constraints and timing checks.
- [ ] Build or obtain a portable RISC-V workload executable and required data.
- [ ] Repeat the FPGA Hello/memory test and workload execution from fresh source.
- [ ] Verify which measurements are simulation, synthesis, or physical-board data.
- [ ] Document regeneration of any benchmark inputs needed by the optimizer.
- [ ] Review third-party licenses, secret scanning, and public-release rights.

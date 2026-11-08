# HEART / ARC 2027: Nexys Video FPGA reproduction runbook

This source-only guide documents the **Rocket/Chipyard Nexys Video physical
execution workflow** actually exercised in the November 2026 lab, plus the
separate **SaCriS-V RTL/synthesis** path. Reproduction commands must use
locally rebuilt or separately supplied software and bitstream artifacts.

**Evidence boundaries.** We verified the Nexys Video JTAG interface and a
Rocket 16 KiB bitstream load, UART-TSI Hello World, model payload probes,
DDR checks and a physical LeViT-384 inference run in the original local lab.
The public repository **does not include** model weights, parameter binary
blobs, large ELF executables, bitstreams, or generated Vivado outputs. We have
**not demonstrated an end-to-end fresh clone -> bitstream -> LeViT run** from
this publication directory. SaCriS-V was simulated and synthesized, **not
booted on the physical board**. The original lab also used a faster UART
configuration; compatibility must be checked before reusing its artifacts.

## 1. Directory map and requirements

- `rocket/reproducibility/chipyard/configs/`: custom Rocket/Chipyard Scala
  configurations to be integrated with a matching Chipyard checkout.
- `rocket/benchmarks/quantized_networks/riscv/levit384/`: LeViT C source and
  model preparation helpers, **not** model weights or prepared input binaries.
- `rocket/reproducibility/scripts/`: toolchain/bootstrap scripts. Read these
  scripts and verify pinned revisions; Chipyard is not vendored here.
- `sacris-v/`: processor Chisel sources, benchmark and Verilator harness.
- `optimizer/`: unified CLI source. The measured SaCriS-V selector needs
  generated performance/resource files and cannot select a configuration from
  this source-only repository without them.

Hardware: Digilent Nexys Video, Artix-7 `xc7a200tsbg484-1`.
The lab used Vivado 2022.2, a RISC-V bare-metal toolchain, Chipyard,
`openFPGALoader`, `uart_tsi`, Ubuntu/WSL and `usbipd-win`. Use the matching
board support packages, cable drivers, peripheral configuration and tool
revisions. Retain all third-party notices and licenses before public release.

## 2. Windows USB passthrough and WSL serial preflight

The **Windows PowerShell administrator** side of the lab used:

```powershell
wsl -l -v
usbipd --version
usbipd list
# Discover the actual BUSIDs; example lab IDs are not universal:
usbipd bind --busid 1-1
usbipd bind --busid 1-2
usbipd attach --wsl --busid 1-1
usbipd attach --wsl --busid 1-2
```

Connect **J12 PROG/JTAG** and **J13 UART** as separate USB interfaces, and
power the board correctly. In Ubuntu/WSL:

```bash
lsusb
lsusb | grep -Ei '0403:6010|0403:6001|FTDI|Digilent' || true
ls -l /dev/ttyUSB* 2>/dev/null || true
id -nG
openFPGALoader --list-boards | grep -i nexys
openFPGALoader --detect -b nexysVideo
```

The original lab observed JTAG IDCODE `0x3636093`, 6 MHz, and UART
`/dev/ttyUSB2`. **Do not assume that serial path on a different machine.**
For permission errors, arrange dialout-group access and sign in again, or
use `sudo` only for the trusted UART wrapper. The helper
`scripts/check_nexys_video.sh` performs a read-only local inspection.

## 3. Rebuild Rocket / generate the design source

The verified lab used a separate Chipyard checkout at `~/chipyard`. To prepare
that checkout, follow `rocket/reproducibility/README.md` and the setup scripts.
Integrate the relevant Scala config definitions into the matching Chipyard
`fpga/src/main/scala/nexysvideo/` package before elaboration.

From the **Chipyard directory** (not the HEART repository), the earlier lab
successfully generated Verilog using this *fast UART* variant:

```bash
cd ~/chipyard
source env.sh
CONFIG=UnifiedResource1Core16K50FastUARTNexysVideoConfig
make -C fpga SUB_PROJECT=nexysvideo CONFIG="$CONFIG" verilog
```

That variant uses a UART-TSI initialization baud rate of **921600** in its
custom Scala config. The standard bitstream below used the non-FastUART
`UnifiedResource1Core16K50NexysVideoConfig`. Match the actual bitstream,
UART-TSI host settings, and baud rate; they are **not interchangeable by
assumption**. A publication copy of the fast-UART Scala source is included
only if it was recovered from the local lab checkout.

The run shown above verified **Verilog generation**, not the bitstream build.
The precise place/route and `make` target for building the bitstream must be
confirmed against your pinned Chipyard Makefile. Do not claim the publication
can create a working bitstream merely because Scala elaboration succeeds.
Before programming, verify placement, setup and hold timing, part number,
constraints, and generated `.bit` provenance. Do not substitute a bitstream
from an unrelated board/configuration.

## 4. Program known-good Rocket bitstream onto Nexys Video

The following **exact command form** succeeded in the original physical lab.
Set `BIT` to a *local* generated or separately provided compatible bitstream:

```bash
BIT="$HOME/nexys-video-sunday-deployment/fpga/UnifiedResource1Core16K50NexysVideoConfig.bit"
test -s "$BIT" || { echo 'Missing local bitstream'; exit 1; }
sha256sum "$BIT"          # record provenance outside the repo
openFPGALoader --detect -b nexysVideo
openFPGALoader -b nexysVideo -m "$BIT"  # SRAM programming; volatile
```

The lab's JTAG programming completed successfully. The `.bit` file above is
**not shipped** in HEART. Reprogram/reset between repeated benchmark runs.
Do not confuse the *original* non-FastUART bitstream with the later FastUART
build unless both configuration and host baud are verified.

## 5. Check UART-TSI with Hello World before model inference

The original deployment's wrapper called the host loader as follows:

```bash
PORT=/dev/ttyUSB2      # example only; inspect your own device
UART_TSI="$HOME/nexys-video-sunday-deployment/fpga/uart_tsi"
HELLO="$HOME/nexys-video-sunday-deployment/fpga/hello.riscv"
test -x "$UART_TSI" && test -s "$HELLO" && test -e "$PORT"
"$UART_TSI" "+tty=$PORT" +selfcheck "$HELLO"
```

The operator also verified this equivalent packaged invocation:

```bash
cd ~/nexys-video-sunday-deployment
sudo timeout --foreground 120s ./run_boot_test.sh /dev/ttyUSB2 hello
```

The original board returned a Hello World message from a Rocket core with
successful ELF load/self-check and clean exit. If the UART loader warns about
resetting before another run, **reset or reprogram** the FPGA before repeating.

## 6. LeViT-384 source and physical inference pathway

**LeViT is the name of the model; the physical target is the Rocket processor,
not SaCriS-V.** The recorded executable in the original lab was:

```text
~/levit384-fpga-build/levit384-port/levit384_cyclehex_fpga.riscv
```

The lab's recorded physical log was outside this repository:

```text
~/levit384-fpga-build/levit384_physical_16k_cyclehex.log
```

A source file used for cycle instrumentation was locally named
`levit384_connected_timed_fpga.c` in the same `levit384-port` directory.
The program printed markers such as `LEVIT384_CYCLES_HI`,
`LEVIT384_CYCLES_LO`, `LEVIT384_LOGIT_*_BITS`, and `predicted_class`.
The `rdcycle`-based count is **program timing instrumentation**, not a direct
Vivado timing result. The original model used embedded/linked payloads and
required a compatible memory map and DDR capacity. The **model executable,
weights and embedded inputs are intentionally omitted** from HEART.

Once the physical board has been programmed with the matching Rocket
bitstream and a **known-compatible** 64-bit RISC-V ELF has been rebuilt:

```bash
PORT=/dev/ttyUSB2   # replace with your detected serial device
UART_TSI="$HOME/nexys-video-sunday-deployment/fpga/uart_tsi"
ELF="$HOME/levit384-fpga-build/levit384-port/levit384_cyclehex_fpga.riscv"
test -x "$UART_TSI" && test -s "$ELF" && test -e "$PORT"
"$UART_TSI" "+tty=$PORT" +selfcheck "$ELF"
```

This is the verified **UART-TSI invocation pattern** from the original
wrapper, adapted to the recorded LeViT ELF; the exact original LeViT launch
line was not recovered from the available logs. Do not represent this example
as an independently re-executed LeViT session. Before interpreting a new run,
check model runtime payload provisioning, serial speed, boot/reset state,
exit code and classification/logit markers. For a convenient, explicit local
run, use `scripts/run_nexys_uart_tsi.sh --help`.

Physical smoke testing was performed first with small probe/Hello programs
(including memory and DDR checks), not by assuming a full neural network
would immediately run after loading the bitstream.

## 7. SaCriS-V: independent simulation/synthesis path

From `hardware/sacris-v/`, see `README.md`, `run_sim.sh` and the C benchmark
Makefile. Generate config-specific Chisel RTL and use Verilator to run the
`small` and `small-spad` Q3_K x Q8_K kernels. Confirm completion and checksums.
The memory-matched configs are `DualIMFB_SLM_128K`, `IMB_SLM_128K`,
`IMF_SLM_128K`, and `IMF_TMR_SLM_128K`.

Synthesis scripts are in `sacris-v/fpga/synthesis/*/synth.tcl`, but were
moved from an old results directory. **Inspect and update their paths** for
this publication layout before running Vivado. They are not yet portable
one-command scripts. SaCriS-V was **not deployed to Nexys Video** in the
experiments reported here; its results must not be described as physical
FPGA model inference.

## 8. Collect evidence without committing results

To reproduce the paper, retain a separate **local, ignored** lab record of:
board/device/bitstream hash, tool versions, Git revision, exact build and
run commands, timing reports, UART-TSI output, program exit, hardware cycle
counters, logits/classification, input/model checksums and physical reset
state. Distinguish (1) synthesis resource utilization, (2) Verilator kernel
cycles, (3) emulator output, and (4) on-board model inference.

This public source tree intentionally excludes `.bit`, `.riscv`, `.bin`,
`.dcp`, `.rpt`, `.log`, model weights, result tables and raw experiments.
A **separate provenance-linked artifact bundle** or documented regenerators
are still needed for an external researcher to reproduce numerical paper
results end-to-end; do not present this as completed until verified.

## 9. Unified optimizer CLI

```bash
cd /path/to/HEART-ARC2027/hardware
python3 -m optimizer.unified_cli --processor rocket --help
python3 -m optimizer.unified_cli --processor sacris-v --help
# To run selections, first regenerate the required measured-data inputs.
```

The SaCriS-V selector reads benchmark logs and synthesis resource files that
are not included in HEART. Likewise, the Rocket five-model workflow may
reference saved measurement/profile inputs that must be regenerated or
obtained separately. A `--help` success validates argument parsing, not an
end-to-end optimizer or on-board execution.

## 10. Release checklist

- [ ] Verify the exact Chipyard commit, Scala config installation and Vivado
      synthesis/implementation make target on a clean environment.
- [ ] Validate the complete FPGA load/LeViT inference path **from this public
      source plus documented external artifacts**, recording the UART baud.
- [ ] Rebuild or document the source for the 64-bit `levit384_cyclehex_fpga.riscv`
      ELF, including runtime parameter creation and linker/memory requirements.
- [ ] Review third-party dependency licenses and public redistribution rights.
- [ ] Document how to regenerate optimizer measurement inputs without shipping
      generated experiment results in this source-only repository.
- [ ] Verify no credentials, private datasets or confidential material appear
      in any tracked file or Git history before changing visibility.

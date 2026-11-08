# SaCriS-V

SaCriS-V is a 32-bit in-order RISC-V core generator (RV32I, optional M/B/F/C, dual issue)
written in Chisel. This directory is the core-only distribution with the Health-SLM
benchmark in `software/health_slm/`. The benchmark variants, their memory requirements and the
build are described in [software/health_slm/README.md](software/health_slm/README.md).

Prerequisites: JDK 17, sbt, Verilator, `riscv32-unknown-elf-gcc` (ISA/ABI and libgcc must match the
configuration, see [software/health_slm/README.md](software/health_slm/README.md)).

```bash
sbt "runMain sacris_v.TopLevel IMF_SLM"          # generated RTL -> generated/IMF_SLM/TopLevel.sv
make -C software/health_slm                      # benchmark variants -> software/health_slm/build/<variant>/
SACRIS_CONFIG=IMF_SLM ./run_sim.sh software/health_slm/build/small   # Verilator run of one variant
```

Configurations are listed in `src/main/scala/sacris_v/Configs.scala` (`sbt "runMain sacris_v.TopLevel ?"`
prints the names). The `*_SLM` configurations were added for the Health-SLM benchmark:

| Configuration | Core | Data memory | DMA memory | Note |
|---|---|---:|---:|---|
| `IMF_SLM` | IMF | 1 MiB | 1 MiB | baseline, all four benchmark variants fit |
| `DualIMFB_SLM` | IMFB, dual issue | 1 MiB | 1 MiB | |
| `IMB_SLM` | IMB | 1 MiB | 1 MiB | no FPU, soft-float build |
| `IMF_SLM_128K` | IMF | 128 KiB | 128 KiB | small enough for an FPGA; `small` variants only |
| `IMF_TMR_SLM` | IMFB, TMR | 1 MiB | 1 MiB | `I_TMR_B3_F` with the SLM memories |

Ready-made netlists (`TopLevel.sv` plus device tree) for the five `*_SLM` configurations are under
`prebuilt/<configuration>/`, so no sbt is needed to simulate or synthesise them. `prebuilt/software/`
holds the `hello` and `lauflicht` example binaries and the four benchmark variants as
`health_slm/imf/<variant>/` (FPU configurations) and `health_slm/imb/<variant>/` (`IMB_SLM`, soft-float),
so no RISC-V toolchain is needed either:

```bash
SACRIS_CONFIG=IMF_SLM ./run_sim.sh prebuilt/software/health_slm/imf/small
```

Vivado files for the Digilent Nexys Video are under `fpga/nexys_video/`, example programs and
the UART loader under `software/`. Licence: BSD 3-Clause (`LICENSE`), third-party parts in `NOTICE`.

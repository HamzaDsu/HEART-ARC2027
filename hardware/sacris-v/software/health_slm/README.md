# Health-SLM Q3_K x Q8_K benchmark on SaCriS-V

`q3k_bench.c` is `benchmarks/q3k-bench*.c` with the kernel and data initialisation unchanged.
Only the platform layer differs: `mini_printf` over the UART, `cycle`/`cycleh` instead of `rdcycle`,
the DMA memory at `0x3000_0000` in place of the Chipyard SBus scratchpad, `SIMULATION_END` instead
of `exit`. The four original files are compile-time variants:

| Variant | Blocks | Repeats | Blocks live in |
|---|---:|---:|---|
| `small` | 32 | 10 | data memory |
| `small-spad` | 32 | 10 | DMA memory, Q8 at `+0x1000` |
| `stream` | 2048 | 1 | data memory |
| `stream-spad` | 2048 | 1 | DMA memory, Q8 at `+0x40000` |

The `stream` variants need about 804 KiB for the blocks, so they only run on the configurations with
1 MiB memories (`IMF_SLM`, `DualIMFB_SLM`, `IMB_SLM`, `IMF_TMR_SLM`). `IMF_SLM_128K` takes the
`small` variants only. Memory sizes are in 32-bit words in `Configs.scala` (262144 = 1 MiB).

```bash
make                                        # all variants -> build/<variant>/
make build/stream/data_mem.bin              # one variant
make PREFIX=/path/to/riscv32-unknown-elf-   # toolchain not in PATH
make MARCH=... MABI=...                     # default rv32imf_zicsr_zifencei / ilp32f
make BUILD=build-imb MARCH=rv32im_zba_zbb_zbc_zbs_zicsr_zifencei MABI=ilp32   # for IMB_SLM (no FPU)
```

The toolchain must ship a libgcc for the chosen `MARCH`/`MABI`. The `IMB_SLM` build needs the
soft-float routines (`__mulsf3`, `__addsf3`, ...) for `ilp32`; a toolchain built only for
`rv32imf.../ilp32f` does not have them and the link fails with undefined references. Either use a
multilib toolchain or one configured with `--with-arch=rv32im... --with-abi=ilp32`, and point
`PREFIX` at it.

Ready-made binaries of all four variants are in `../../prebuilt/software/health_slm/`: `imf/` built with
the default flags for the FPU configurations, `imb/` built with the `IMB_SLM` flags above (soft-float).

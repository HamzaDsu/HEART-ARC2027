# Health-SLM Q3_K x Q8_K benchmark for SaCriS-V.
#
#   make                      builds all four variants into build/<variant>/
#   make MARCH=... MABI=...   other ISA (default: rv32imf_zicsr_zifencei / ilp32f)
#   make BUILD=build-imb ...  other output directory (default: build)
#
# Variants (same names as in health-slm-riscv-hardware/benchmarks):
#   small        32 blocks x 10 repeats, blocks in DMEM
#   small-spad   32 blocks x 10 repeats, blocks in the DMA memory (0x3000_0000)
#   stream       2048 blocks x 1, blocks in DMEM
#   stream-spad  2048 blocks x 1, blocks in the DMA memory
#
# Each build/<variant>/ holds instruction_mem.bin + data_mem.bin for run_sim.sh.

PREFIX  ?= riscv32-unknown-elf-
CC      := $(PREFIX)gcc
OBJCOPY := $(PREFIX)objcopy
OBJDUMP := $(PREFIX)objdump
SIZE    := $(PREFIX)size

BUILD   ?= build
MARCH   ?= rv32imf_zicsr_zifencei
MABI    ?= ilp32f
# Same optimisation flags as the Chipyard build (CMakeLists.txt):
# -O2, gnu99, -fno-common, -fno-builtin-printf. No -ffreestanding so that
# memset/memcpy inside the kernel are compiled the same way as on Rocket.
CFLAGS  := -march=$(MARCH) -mabi=$(MABI) -std=gnu99 -O2 -Wall -Wextra -fno-common -fno-builtin-printf
LDFLAGS := -march=$(MARCH) -mabi=$(MABI) -nostartfiles -Tlinker_script.ld -Wl,--gc-sections

VARIANTS := small small-spad stream stream-spad
DEFS_small       := -DBLOCKS=32   -DREPEATS=10
DEFS_small-spad  := -DBLOCKS=32   -DREPEATS=10 -DQ3K_SPAD
DEFS_stream      := -DBLOCKS=2048 -DREPEATS=1
DEFS_stream-spad := -DBLOCKS=2048 -DREPEATS=1  -DQ3K_SPAD

all: $(foreach v,$(VARIANTS),$(BUILD)/$(v)/data_mem.bin)

$(BUILD)/%/data_mem.bin: $(BUILD)/%/q3k_bench.elf
	$(OBJCOPY) -O binary -j .init -j .text $< $(BUILD)/$*/instruction_mem.bin
	$(OBJCOPY) -O binary -j .rodata -j '.rodata.*' -j .srodata -j '.srodata.*' -j .data -j '.data.*' -j .sdata $< $@
	$(OBJDUMP) -d -M no-aliases $< > $(BUILD)/$*/dump.txt

.SECONDARY:
$(BUILD)/%/q3k_bench.elf: q3k_bench.c mini_printf.c start.S linker_script.ld sacris_v_config.h mini_printf.h
	mkdir -p $(BUILD)/$*
	$(CC) $(CFLAGS) $(DEFS_$*) -c -o $(BUILD)/$*/q3k_bench.o q3k_bench.c
	$(CC) $(CFLAGS) -c -o $(BUILD)/$*/mini_printf.o mini_printf.c
	$(CC) $(CFLAGS) -c -o $(BUILD)/$*/start.o start.S
	$(CC) $(LDFLAGS) -o $@ $(BUILD)/$*/q3k_bench.o $(BUILD)/$*/mini_printf.o $(BUILD)/$*/start.o
	$(SIZE) $@

clean:
	$(RM) -r $(BUILD)

.PHONY: all clean

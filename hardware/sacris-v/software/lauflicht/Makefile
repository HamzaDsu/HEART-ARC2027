# Lauflicht fuer SaCriS-V (RV32I). Ergebnis: instruction_mem.bin + data_mem.bin
# fuer software/uart_loader.py.

PREFIX  ?= riscv32-unknown-elf-
ARCH    ?= rv32i_zicsr          # bei Konfiguration IMB: rv32im_zba_zbb_zbc_zbs_zicsr
CC       = $(PREFIX)gcc
OBJCOPY  = $(PREFIX)objcopy
CFLAGS   = -march=$(ARCH) -mabi=ilp32 -O2 -Wall -ffreestanding -fno-builtin -std=gnu99

OBJS = start.o main.o mini_printf.o

all: instruction_mem.bin data_mem.bin

lauflicht.elf: $(OBJS) linker_script.ld
	$(CC) $(CFLAGS) -nostartfiles -Tlinker_script.ld -o $@ $(OBJS)
	$(PREFIX)size $@

instruction_mem.bin: lauflicht.elf
	$(OBJCOPY) -O binary -j .init -j .text $< $@

data_mem.bin: lauflicht.elf
	$(OBJCOPY) -O binary -j .rodata -j .data -j .sdata $< $@

%.o: %.c
	$(CC) $(CFLAGS) -c -o $@ $<

%.o: %.S
	$(CC) $(CFLAGS) -c -o $@ $<

clean:
	$(RM) $(OBJS) lauflicht.elf instruction_mem.bin data_mem.bin

.PHONY: all clean

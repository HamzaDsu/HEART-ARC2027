.PHONY: all clean

%.bin: %.elf
	riscv32-unknown-elf-objcopy -O binary -j .init -j .text $< instruction_mem.bin
	riscv32-unknown-elf-objcopy -O binary -j .rodata -j .data -j .sdata -j sbss $< data_mem.bin
	hexdump -v -e '1/4 "%08x " "\n"' instruction_mem.bin > instruction_mem.hex
	hexdump -v -e '1/4 "%08x " "\n"' data_mem.bin > data_mem.hex

OBJECTS := main.o start.o printf.o

all: test_printf.elf test_printf.bin

test_printf.elf: $(OBJECTS)
	riscv32-unknown-elf-gcc -o test_printf.elf -v -march=rv32i_zicsr -nostartfiles -Tlinker_script.ld -W $(OBJECTS)
	riscv32-unknown-elf-size test_printf.elf

clean:
	-$(RM) $(OBJECTS)
	-$(RM) test_printf.elf r.bin test_printf.map instruction_mem.bin data_mem.bin instruction_mem.hex bin data_mem.hex

main.o: main_rv32.c
	riscv32-unknown-elf-gcc -c -o $@ -march=rv32i_zicsr -Wall -O2  -ffreestanding -fno-builtin -std=gnu99 -Wall -Werror=implicit-function-declaration  $<
printf.o: mini_printf.c mini_printf.h
	riscv32-unknown-elf-gcc -c -o $@ -march=rv32i_zicsr -Wall -O2  -ffreestanding -fno-builtin -std=gnu99 -Wall -Werror=implicit-function-declaration  $<
start.o: start.S
	riscv32-unknown-elf-gcc -c -o $@ -march=rv32i_zicsr -Wall -O2  -ffreestanding -fno-builtin -std=gnu99 -Wall -Werror=implicit-function-declaration $<

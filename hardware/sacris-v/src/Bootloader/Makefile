.PHONY: all clean

# ELF file 2 binary file:
#%.bin: %.elf
#	riscv32-unknown-elf-objcopy -j .text -j .data -j .rodata -O binary $< $@

# binary file 2 coefficient file for initializing Xilinx block RAM resources:
#%.coe: %.bin
#	echo "memory_initialization_radix=16;" > $@
#	echo "memory_initialization_vector=" >> $@
#	hexdump -v -e '1/4 "%08x\n"' $< >> $@
#	echo ";" >> $@

# ELF file 2 instruction memory binary file:
%.bin: %.elf
	#riscv32-unknown-elf-objcopy -j .text -O binary $< $@
	riscv32-unknown-elf-objcopy -O binary -j .init -j .text $< instruction_mem.bin
	riscv32-unknown-elf-objcopy -O binary -j .rodata -j .data -j .sdata -j sbss $< data_mem.bin
	hexdump -v -e '1/4 "%08x " "\n"' instruction_mem.bin > instruction_mem.hex
	hexdump -v -e '1/4 "%08x " "\n"' data_mem.bin > data_mem.hex

OBJECTS := main.o start.o 

all: bootloader.elf bootloader.bin

bootloader.elf: $(OBJECTS)
#	riscv32-unknown-elf-gcc -o bootloader.elf -v -march=rv32i_zicsr -nostartfiles -Tlinker_script.ld -Wl,--Map,bootloader.map $(OBJECTS) 
	riscv32-unknown-elf-gcc -o bootloader.elf -v -march=rv32i_zicsr -nostartfiles -Tlinker_script.ld -W $(OBJECTS)
	riscv32-unknown-elf-size bootloader.elf

clean:
	-$(RM) $(OBJECTS)
	-$(RM) bootloader.elf r.bin bootloader.map instruction_mem.bin data_mem.bin instruction_mem.hex bin data_mem.hex

# Object file rules:
# -fo-ffreestanding
#           Assert that compilation targets a freestanding environment.  This implies -fno-builtin.  A freestanding environment is one in which
#           the standard library may not exist, and program startup may not necessarily be at "main".  The most obvious example is an OS kernel.
#           This is equivalent to -fno-hosted.mit-frame-pointer 


main.o: main_rv32.c
	riscv32-unknown-elf-gcc -c -o $@ -march=rv32i_zicsr -Wall -O2  -ffreestanding -fno-builtin -std=gnu99 -Wall -Werror=implicit-function-declaration  $<
start.o: start.S
	riscv32-unknown-elf-gcc -c -o $@ -march=rv32i_zicsr -Wall -O2  -ffreestanding -fno-builtin -std=gnu99 -Wall -Werror=implicit-function-declaration $<


MEMORY {
  iram (rx)  : ORIGIN = 0x00000000, LENGTH = 2048
  dram (rwx) : ORIGIN = 0x00000000, LENGTH = 32K
}

SECTIONS {
  .init : { KEEP(*(.init*)) } > iram
  .text : { *(.text .text.*) } > iram
  .rodata : { *(.rodata .rodata.*) } > iram
}

/* stack top in DRAM */
PROVIDE(_stack_top = ORIGIN(dram) + LENGTH(dram));


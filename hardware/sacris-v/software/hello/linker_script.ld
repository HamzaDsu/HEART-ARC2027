ENTRY(_start)

MEMORY
{
    iram (rx)  : ORIGIN = 0x80000000, LENGTH = 64K
    dram (rwx) : ORIGIN = 0x00000000, LENGTH = 32K
}

SECTIONS
{
    .init : {
        KEEP(*(.init))
        KEEP(*(.init.*))
    } > iram

    .text : {
        *(.text .text.*)
    } > iram

    .rodata : {
        *(.rodata .rodata.*)
    } > dram

    .data : {
        _sdata = .;
        *(.data .data.*)
        *(.sdata .sdata.*)
        _edata = .;
    } > dram

    .bss (NOLOAD) : {
        _sbss = .;
        *(.sbss .sbss.*)
        *(.bss .bss.*)
        *(COMMON)
        _ebss = .;
    } > dram
}

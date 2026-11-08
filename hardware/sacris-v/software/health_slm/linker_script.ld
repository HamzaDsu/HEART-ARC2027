/* SaCriS-V health_slm: the 1 MiB data memory (dmemSize = 262144 words) of
   IMF_SLM, DualIMFB_SLM, IMB_SLM and IMF_TMR_SLM holds the 2048-block streaming
   set (~804 KiB) next to the stack. IMF_SLM_128K has 128 KiB and takes the
   small variants only; its stack pointer wraps into the top of that memory. */
ENTRY(_start)

MEMORY
{
    iram (rx)  : ORIGIN = 0x80000000, LENGTH = 128K
    dram (rwx) : ORIGIN = 0x00000000, LENGTH = 1M
}

_stack_top = ORIGIN(dram) + LENGTH(dram);

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
        *(.srodata .srodata.*)
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

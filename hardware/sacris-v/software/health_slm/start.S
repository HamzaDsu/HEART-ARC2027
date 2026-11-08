    #include "sacris_v_config.h"

    .section .init
    .globl _start
    .globl main

_start:
    /* Stack at the top of the data memory (_stack_top from the linker script). */
    la    sp, _stack_top

    /* Clear .bss */
    la    t0, _sbss
    la    t1, _ebss
1:  bgeu  t0, t1, 2f
    sw    zero, 0(t0)
    addi  t0, t0, 4
    j     1b

2:
    /* UART16550 init (base 0x69000000, reg_shift=2), 8N1. */
    lui   t0, 0x69000
    li    t1, 0x83
    sw    t1, 0x0C(t0)       /* LCR = 0x83 (DLAB=1, 8N1) */
    li    t1, UART_BAUD_DIVISOR
    sw    t1, 0x00(t0)       /* DLL */
    sw    zero, 0x04(t0)     /* DLM = 0 */
    li    t1, 0x03
    sw    t1, 0x0C(t0)       /* LCR = 0x03 (DLAB=0, 8N1) */
    li    t1, 0x07
    sw    t1, 0x08(t0)       /* FCR = 0x07 (enable + reset FIFOs) */

    jal   main

3:  j     3b

    .section .init
    .globl _start
    .globl main

_start:
    lui   sp, 0x8          # sp = 0x00008000

    lui   t0, 0x69000      # t0 = 0x69000000 (UART16550 base)
    li    t1, 0x83
    sw    t1, 0x0C(t0)     # LCR = 0x83 (DLAB=1, 8N1)
    li    t1, 43
    sw    t1, 0x00(t0)     # DLL = 43
    sw    zero, 0x04(t0)   # DLM = 0
    li    t1, 0x03
    sw    t1, 0x0C(t0)     # LCR = 0x03 (DLAB=0, 8N1)
    li    t1, 0x07
    sw    t1, 0x08(t0)     # FCR = 0x07 (enable + reset FIFOs)

    jal   main

1:  j     1b               # falls main je zurückkommt → Endlosschleife

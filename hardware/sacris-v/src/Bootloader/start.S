.section .init
.globl _start
_start:
    /* ------------------------------------------------------------
     * Clear integer register file (x1..x31). x0 is hardwired to 0.
     * ------------------------------------------------------------ */
    li   t0, 0
    mv   ra, t0        /* x1  */
    mv   sp, t0        /* x2  */
    mv   gp, t0        /* x3  */
    mv   tp, t0        /* x4  */
    mv   t1, t0        /* x6  */
    mv   t2, t0        /* x7  */
    mv   s0, t0        /* x8  */
    mv   s1, t0        /* x9  */
    mv   a0, t0        /* x10 */
    mv   a1, t0        /* x11 */
    mv   a2, t0        /* x12 */
    mv   a3, t0        /* x13 */
    mv   a4, t0        /* x14 */
    mv   a5, t0        /* x15 */
    mv   a6, t0        /* x16 */
    mv   a7, t0        /* x17 */
    mv   s2, t0        /* x18 */
    mv   s3, t0        /* x19 */
    mv   s4, t0        /* x20 */
    mv   s5, t0        /* x21 */
    mv   s6, t0        /* x22 */
    mv   s7, t0        /* x23 */
    mv   s8, t0        /* x24 */
    mv   s9, t0        /* x25 */
    mv   s10, t0       /* x26 */
    mv   s11, t0       /* x27 */
    mv   t3, t0        /* x28 */
    mv   t4, t0        /* x29 */
    mv   t5, t0        /* x30 */
    mv   t6, t0        /* x31 */

    /* Trap vector -> infinite loop */
    la   t0, trap
    csrw mtvec, t0

    /* --------------------------------------------------------
     * LED TEST: GPO = 0x64000FF8


     * -------------------------------------------------------- */
    lui  t0, 0x64001           /* t0 = 0x64001000        */
    addi t0, t0, -8            /* t0 = 0x6400_0FF8 (GPO) */
    li   t1, 0xFF              /* alle 8 LEDs an              */
    sw   t1, 0(t0)

    /* --------------------------------------------------------
     * UART16550 init: 115200 baud @ 80 MHz, 8N1
     * Base = 0x6900_0000, reg_shift=2
     * -------------------------------------------------------- */
    lui  s0, 0x69000           /* s0 = 0x6900_0000 (UART16550 base / THR / RBR) */
    addi s1, s0, 0x14          /* s1 = 0x6900_0014 (LSR)                        */

    /* Set DLAB=1: LCR = 0x83 (8N1 + DLAB) */
    li   t1, 0x83
    sw   t1, 0x0C(s0)         /* LCR */

    /* DLL = 43 (80 MHz / 16 / 115200 ≈ 43), DLM = 0 */
    li   t1, 43
    sw   t1, 0x00(s0)         /* DLL (offset 0 when DLAB=1) */
    sw   zero, 0x04(s0)       /* DLM (offset 4 when DLAB=1) */

    /* Clear DLAB: LCR = 0x03 (8N1, no parity, 1 stop) */
    li   t1, 0x03
    sw   t1, 0x0C(s0)

    /* Enable + reset FIFOs: FCR = 0x07 */
    li   t1, 0x07
    sw   t1, 0x08(s0)         /* FCR */

    /* IMEM MMIO base */
    lui  s3, 0x65000           /* s3 = 0x6500_0000 (IMEM_WDATA) */

    /* Alive marker */
    li   a0, 'B'
    jal  ra, uart_putc

/* ============================================================
   1) Load IMEM: expect "IMEM" + len(u32 LE) + payload(words)
   ============================================================ */
sync_I:
    jal  ra, uart_getc
    li   t0, 'I'
    bne  a0, t0, sync_I

sync_Mi:
    jal  ra, uart_getc
    li   t0, 'M'
    beq  a0, t0, sync_Ei
    li   t0, 'I'
    beq  a0, t0, sync_Mi
    j    sync_I

sync_Ei:
    jal  ra, uart_getc
    li   t0, 'E'
    beq  a0, t0, sync_M2i
    li   t0, 'I'
    beq  a0, t0, sync_Mi
    j    sync_I

sync_M2i:
    jal  ra, uart_getc
    li   t0, 'M'
    beq  a0, t0, read_len_imem
    li   t0, 'I'
    beq  a0, t0, sync_Mi
    j    sync_I

read_len_imem:
    jal  ra, uart_getc
    mv   t3, a0
    jal  ra, uart_getc
    slli a0, a0, 8
    or   t3, t3, a0
    jal  ra, uart_getc
    slli a0, a0, 16
    or   t3, t3, a0
    jal  ra, uart_getc
    slli a0, a0, 24
    or   t3, t3, a0            /* t3 = len (bytes) */

    andi t0, t3, 3
    bnez t0, error

    srli t5, t3, 2

    /* LED: IMEM-Empfang läuft → LED[1] an */
    lui  t0, 0x64001
    addi t0, t0, -8
    li   t1, 0x03              /* LED[0] + LED[1] */
    sw   t1, 0(t0)

imem_loop:
    beqz t5, imem_done
    jal  ra, uart_getc
    mv   t6, a0
    jal  ra, uart_getc
    slli a0, a0, 8
    or   t6, t6, a0
    jal  ra, uart_getc
    slli a0, a0, 16
    or   t6, t6, a0
    jal  ra, uart_getc
    slli a0, a0, 24
    or   t6, t6, a0
    sw   t6, 0(s3)
    addi t5, t5, -1
    j    imem_loop

imem_done:
    li   a0, 'I'
    jal  ra, uart_putc

/* ============================================================
   1.5) Clear DMEM (32 KiB)
   ============================================================ */
    li   t4, 0x00000000
    li   t5, 8192
    li   t6, 0

clear_dmem_loop:
    beqz t5, clear_dmem_done
    sw   t6, 0(t4)
    addi t4, t4, 4
    addi t5, t5, -1
    j    clear_dmem_loop

clear_dmem_done:

/* ============================================================
   2) Load DMEM: expect "DMEM" + len(u32 LE) + payload(bytes)
   ============================================================ */
sync_D:
    jal  ra, uart_getc
    li   t0, 'D'
    bne  a0, t0, sync_D

sync_M:
    jal  ra, uart_getc
    li   t0, 'M'
    beq  a0, t0, sync_E
    li   t0, 'D'
    beq  a0, t0, sync_M
    j    sync_D

sync_E:
    jal  ra, uart_getc
    li   t0, 'E'
    beq  a0, t0, sync_M2
    li   t0, 'D'
    beq  a0, t0, sync_M
    j    sync_D

sync_M2:
    jal  ra, uart_getc
    li   t0, 'M'
    beq  a0, t0, read_len
    li   t0, 'D'
    beq  a0, t0, sync_M
    j    sync_D

read_len:
    jal  ra, uart_getc
    mv   t3, a0
    jal  ra, uart_getc
    slli a0, a0, 8
    or   t3, t3, a0
    jal  ra, uart_getc
    slli a0, a0, 16
    or   t3, t3, a0
    jal  ra, uart_getc
    slli a0, a0, 24
    or   t3, t3, a0

    li   t0, 32768
    bgtu t3, t0, error

    li   t4, 0x00000000
    mv   t5, t3

dmem_loop:
    beqz t5, done
    jal  ra, uart_getc
    sb   a0, 0(t4)
    addi t4, t4, 1
    addi t5, t5, -1
    j    dmem_loop

done:
    li   a0, 'O'
    jal  ra, uart_putc

    /* LED: alles OK → alle 8 LEDs an */
    lui  t0, 0x64001
    addi t0, t0, -8
    li   t1, 0xFF
    sw   t1, 0(t0)

    /* Jump to app @ 0x8000_0000 */
    lui  t0, 0x80000
    jalr x0, 0(t0)

hang:
    j    hang

error:
    li   a0, 'E'
    jal  ra, uart_putc
    /* LED: Fehler → nur LED[7] blinkt nicht, bleibt an als Fehlermarker */
    lui  t0, 0x64001
    addi t0, t0, -8
    li   t1, 0x80
    sw   t1, 0(t0)
    j    hang

/* --------------------------
   UART helpers
   -------------------------- */
uart_getc:
1:  lw   t0, 0(s1)            /* read LSR */
    andi t0, t0, 1             /* bit 0 = DR (Data Ready) */
    beqz t0, 1b
    lw   a0, 0(s0)            /* read RBR */
    andi a0, a0, 0xFF
    ret

uart_putc:
2:  lw   t0, 0(s1)            /* read LSR */
    andi t0, t0, 0x20         /* bit 5 = THRE (TX Holding Register Empty) */
    beqz t0, 2b
    sw   a0, 0(s0)            /* write THR */
    ret

trap:
    j trap

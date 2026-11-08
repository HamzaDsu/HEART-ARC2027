#include <stdint.h>

/* UART16550 registers (reg_shift=2) */
#define UART16550_BASE 0x69000000u
#define UART16550_THR  (*(volatile uint32_t*)(UART16550_BASE + 0x00u)) // W: TX data
#define UART16550_RBR  (*(volatile uint32_t*)(UART16550_BASE + 0x00u)) // R: RX data
#define UART16550_LSR  (*(volatile uint32_t*)(UART16550_BASE + 0x14u)) // R: Line Status
#define LSR_DR   (1u << 0)  // Data Ready
#define LSR_THRE (1u << 5)  // TX Holding Register Empty

// Adjust to your real DMEM size if needed
#define DMEM_MAX_BYTES (32u * 1024u)

static inline void uart_putc(uint8_t ch)
{
    while ((UART16550_LSR & LSR_THRE) == 0u) { __asm__ volatile("nop"); }
    UART16550_THR = (uint32_t)ch;
}

static inline uint8_t uart_getc_blocking(void)
{
    while ((UART16550_LSR & LSR_DR) == 0u) { __asm__ volatile("nop"); }
    return (uint8_t)(UART16550_RBR & 0xFFu);
}

// Optional: jump to app entry (0x8000_0000)
static inline void jump_to_app(void)
{
    __asm__ volatile(
        "lui  t0, 0x80000\n"
        "jalr x0, 0(t0)\n"
    );
    __builtin_unreachable();
}

typedef enum {
    ST_SYNC_D,      // wait for 'D'
    ST_SYNC_M,      // wait for 'M'
    ST_SYNC_E,      // wait for 'E'
    ST_SYNC_M2,     // wait for 'M' (4th char)
    ST_LEN_0,       // read len byte 0 (LSB)
    ST_LEN_1,
    ST_LEN_2,
    ST_LEN_3,       // read len byte 3 (MSB)
    ST_LOAD,        // receive payload and write to DMEM
    ST_DONE,        // finished
    ST_ERROR        // error
} state_t;

void bootloader_main(void)
{
    // Simple alive marker
    uart_putc('B');

    volatile uint8_t* dmem = (volatile uint8_t*)0x00000000u;

    state_t st = ST_SYNC_D;

    uint32_t len = 0;
    uint32_t idx = 0;

    while (1) {
        uint8_t b = uart_getc_blocking();

        switch (st) {
            // ---- Magic sync: "DMEM" ----
            case ST_SYNC_D:
                st = (b == (uint8_t)'D') ? ST_SYNC_M : ST_SYNC_D;
                break;

            case ST_SYNC_M:
                if (b == (uint8_t)'M') st = ST_SYNC_E;
                else st = (b == (uint8_t)'D') ? ST_SYNC_M : ST_SYNC_D; // overlap-safe
                break;

            case ST_SYNC_E:
                if (b == (uint8_t)'E') st = ST_SYNC_M2;
                else st = (b == (uint8_t)'D') ? ST_SYNC_M : ST_SYNC_D;
                break;

            case ST_SYNC_M2:
                if (b == (uint8_t)'M') {
                    // got full "DMEM"
                    len = 0;
                    st = ST_LEN_0;
                } else {
                    st = (b == (uint8_t)'D') ? ST_SYNC_M : ST_SYNC_D;
                }
                break;

            // ---- Length u32 little-endian ----
            case ST_LEN_0:
                len = (uint32_t)b;
                st = ST_LEN_1;
                break;

            case ST_LEN_1:
                len |= ((uint32_t)b << 8);
                st = ST_LEN_2;
                break;

            case ST_LEN_2:
                len |= ((uint32_t)b << 16);
                st = ST_LEN_3;
                break;

            case ST_LEN_3:
                len |= ((uint32_t)b << 24);

                if (len > DMEM_MAX_BYTES) {
                    uart_putc('E');      // length too large
                    st = ST_ERROR;
                } else {
                    idx = 0;
                    st = (len == 0) ? ST_DONE : ST_LOAD;
                }
                break;

            // ---- Payload: write len bytes to DMEM ----
            case ST_LOAD:
                dmem[idx] = b;
                idx++;

                if (idx >= len) {
                    st = ST_DONE;
                }
                break;

            case ST_DONE:
                uart_putc('O'); // OK
                // Option A: stay here
                // while (1) { __asm__ volatile("nop"); }

                // Option B: jump to app
                jump_to_app();
                break;

            case ST_ERROR:
            default:
                // stay in error state
                while (1) { __asm__ volatile("nop"); }
        }
    }
}


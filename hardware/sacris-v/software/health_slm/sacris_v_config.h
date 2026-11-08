#ifndef SACRIS_V_CONFIG_H
#define SACRIS_V_CONFIG_H
#ifndef CLOCK_HZ
#define CLOCK_HZ           50000000U
#endif
#ifndef UART_BAUD_RATE
#define UART_BAUD_RATE     115200
#endif
#define UART_BAUD_DIVISOR  (CLOCK_HZ / (16 * UART_BAUD_RATE))
#endif

#include <stdint.h>
#include <string.h>
#include "mini_printf.h"

#define APP_START (0x80000000)
#define APP_LEN   (0x200)
#define APP_ENTRY (0x00000000)

#define SIMULATION_END (*(volatile uint32_t*)0x64000F04)

static inline void delay(volatile uint32_t n) {
  while (n--) __asm__ volatile ("nop");
}

void main(void) {
   mini_printf("RISC-V Processor from the Humboldt-University of Berlin\nDep. of Computer Eng.\nHallo From Main\n");
   delay(100000);
   SIMULATION_END = 1;

}

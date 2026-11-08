// Lauflicht auf den LEDs des Nexys Video.
//
// Das Programm laeuft endlos: ein Lichtpunkt wandert von LED0 nach LED7 und
// zurueck, nach jeder Runde kommt eine Zeile ueber die UART. Es schreibt
// absichtlich NICHT auf SIMULATION_END, sonst wuerde der Prozessor neu starten
// und wieder im Bootloader landen.

#include <stdint.h>
#include "mini_printf.h"

// GPO-Register: 16 Bit Ausgang, Bits 0..7 liegen ueber den Slice-Block auf LED0..LED7
#define GPO       (*(volatile uint32_t*)0x64000FF8u)

// ACLINT mtime: 64-Bit-Zaehler, der mit 1 MHz laeuft (50 MHz Takt / timerTickCompare 50)
#define MTIME_LO  (*(volatile uint32_t*)0x0200BFF8u)
#define MTIME_HI  (*(volatile uint32_t*)0x0200BFFCu)
#define MTIME_HZ  1000000u

#define SCHRITT_MS 100u   // Verweildauer pro LED

static uint64_t mtime(void) {
  uint32_t hi, lo;
  do {                    // beide Haelften konsistent lesen (Ueberlauf von lo nach hi)
    hi = MTIME_HI;
    lo = MTIME_LO;
  } while (hi != MTIME_HI);
  return ((uint64_t)hi << 32) | lo;
}

static void delay_ms(uint32_t ms) {
  uint64_t ende = mtime() + (uint64_t)ms * (MTIME_HZ / 1000u);
  while (mtime() < ende) {
  }
}

void main(void) {
  mini_printf("SaCriS-V Lauflicht: %u ms pro Schritt\n", SCHRITT_MS);

  uint32_t pos = 0;       // welche LED leuchtet
  int richtung = 1;       // +1 nach links (LED7), -1 nach rechts (LED0)
  uint32_t runde = 0;

  for (;;) {
    GPO = 1u << pos;
    delay_ms(SCHRITT_MS);

    if (richtung > 0 && pos == 7) {
      richtung = -1;
    } else if (richtung < 0 && pos == 0) {
      richtung = 1;
      runde++;
      mini_printf("Runde %u\n", runde);
    }
    pos += richtung;
  }
}

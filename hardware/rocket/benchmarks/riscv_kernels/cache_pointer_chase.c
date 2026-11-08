#include <stdint.h>
#include <stdio.h>

#define WORKING_SET_KIB 24
#define ROUNDS 12
#define WORDS_PER_LINE 16
#define LINES (WORKING_SET_KIB * 1024 / 64)

static volatile uint32_t next[LINES * WORDS_PER_LINE];

int main(void) {
    for (int i = 0; i < LINES; i++) {
        int successor = (i + 73) % LINES;
        next[i * WORDS_PER_LINE] =
            (uint32_t)(successor * WORDS_PER_LINE);
    }

    uint32_t position = 0;
    uint32_t checksum = 0;

    for (int r = 0; r < ROUNDS; r++) {
        for (int i = 0; i < LINES; i++) {
            position = next[position];
            checksum = checksum * 33u + position + 1u;
        }
    }

    if (position != 0) {
        puts("FAIL CACHE POINTER CHASE");
        return 1;
    }

    printf("PASS CACHE CHASE KIB=%d CHECKSUM=%d\n",
           WORKING_SET_KIB,
           (int)(checksum & 0x7fffffffU));

    return 0;
}

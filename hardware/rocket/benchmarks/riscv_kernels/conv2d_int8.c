#include <stdint.h>
#include <stdio.h>

#define IC 3
#define OC 16
#define H 32
#define W 32
#define KS 3

static int8_t input[IC][H][W];
static int8_t weights[OC][IC][KS][KS];
static int32_t output[OC][H][W];
static int32_t reference[OC][H][W];

int main(void) {
    for (int c = 0; c < IC; c++)
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                input[c][y][x] =
                    (int8_t)((c * 3 + y * 5 + x * 7) % 13 - 6);

    for (int o = 0; o < OC; o++)
        for (int c = 0; c < IC; c++)
            for (int ky = 0; ky < KS; ky++)
                for (int kx = 0; kx < KS; kx++)
                    weights[o][c][ky][kx] =
                        (int8_t)((o * 2 + c * 3 +
                                   ky * 5 + kx * 7) % 11 - 5);

    /* Main convolution: stride 1, zero padding 1. */
    for (int o = 0; o < OC; o++)
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int32_t acc = 0;

                for (int c = 0; c < IC; c++)
                    for (int ky = 0; ky < KS; ky++)
                        for (int kx = 0; kx < KS; kx++) {
                            int iy = y + ky - 1;
                            int ix = x + kx - 1;

                            if (iy >= 0 && iy < H &&
                                ix >= 0 && ix < W)
                                acc += (int32_t)input[c][iy][ix]
                                     * weights[o][c][ky][kx];
                        }

                output[o][y][x] = acc;
            }

    /* Independent reference: different loop ordering. */
    for (int o = 0; o < OC; o++)
        for (int c = 0; c < IC; c++)
            for (int ky = 0; ky < KS; ky++)
                for (int kx = 0; kx < KS; kx++)
                    for (int y = 0; y < H; y++)
                        for (int x = 0; x < W; x++) {
                            int iy = y + ky - 1;
                            int ix = x + kx - 1;

                            if (iy >= 0 && iy < H &&
                                ix >= 0 && ix < W)
                                reference[o][y][x] +=
                                    (int32_t)input[c][iy][ix]
                                    * weights[o][c][ky][kx];
                        }

    long long checksum = 0;

    for (int o = 0; o < OC; o++)
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                if (output[o][y][x] != reference[o][y][x]) {
                    printf("FAIL convolution mismatch\n");
                    return 1;
                }

                checksum += (long long)output[o][y][x]
                          * (o + 1) * (y + 1) * (x + 1);
            }

    printf("PASS INT8 CONV2D CHECKSUM=%lld\n", checksum);
    return 0;
}

#include <stdint.h>
#include <stdio.h>

#define HEADS 2
#define TOKENS 16
#define DIM 32

static int8_t Q[HEADS][TOKENS][DIM];
static int8_t K[HEADS][TOKENS][DIM];
static int8_t V[HEADS][TOKENS][DIM];

static int32_t scores[HEADS][TOKENS][TOKENS];
static int32_t score_ref[HEADS][TOKENS][TOKENS];

static int8_t A[HEADS][TOKENS][TOKENS];

static int32_t output[HEADS][TOKENS][DIM];
static int32_t output_ref[HEADS][TOKENS][DIM];

int main(void) {
    for (int h = 0; h < HEADS; h++)
        for (int i = 0; i < TOKENS; i++)
            for (int d = 0; d < DIM; d++) {
                Q[h][i][d] = (int8_t)((h+i+d*3)%11-5);
                K[h][i][d] = (int8_t)((h*3+i*2+d)%13-6);
                V[h][i][d] = (int8_t)((h+i*5+d*2)%9-4);
            }

    /* Q x K-transpose */
    for (int h = 0; h < HEADS; h++)
        for (int i = 0; i < TOKENS; i++)
            for (int j = 0; j < TOKENS; j++)
                for (int d = 0; d < DIM; d++)
                    scores[h][i][j] +=
                        (int32_t)Q[h][i][d] * K[h][j][d];

    /* Reference with different loop ordering */
    for (int h = 0; h < HEADS; h++)
        for (int d = 0; d < DIM; d++)
            for (int i = 0; i < TOKENS; i++)
                for (int j = 0; j < TOKENS; j++)
                    score_ref[h][i][j] +=
                        (int32_t)Q[h][i][d] * K[h][j][d];

    for (int h = 0; h < HEADS; h++)
        for (int i = 0; i < TOKENS; i++)
            for (int j = 0; j < TOKENS; j++) {
                if (scores[h][i][j] != score_ref[h][i][j]) {
                    puts("FAIL: QK mismatch");
                    return 1;
                }

                /* Illustrative quantized attention weights.
                   This is NOT softmax. */
                int32_t value = scores[h][i][j] / 16;
                if (value > 7) value = 7;
                if (value < -7) value = -7;
                A[h][i][j] = (int8_t)value;
            }

    /* Attention-weighted value: A x V */
    for (int h = 0; h < HEADS; h++)
        for (int i = 0; i < TOKENS; i++)
            for (int d = 0; d < DIM; d++)
                for (int j = 0; j < TOKENS; j++)
                    output[h][i][d] +=
                        (int32_t)A[h][i][j] * V[h][j][d];

    /* Independent loop ordering */
    for (int h = 0; h < HEADS; h++)
        for (int j = 0; j < TOKENS; j++)
            for (int d = 0; d < DIM; d++)
                for (int i = 0; i < TOKENS; i++)
                    output_ref[h][i][d] +=
                        (int32_t)A[h][i][j] * V[h][j][d];

    long long checksum = 0;

    for (int h = 0; h < HEADS; h++)
        for (int i = 0; i < TOKENS; i++)
            for (int d = 0; d < DIM; d++) {
                if (output[h][i][d] != output_ref[h][i][d]) {
                    puts("FAIL: AV mismatch");
                    return 1;
                }

                checksum +=
                    (long long)output[h][i][d]
                    * (h+1) * (i+1) * (d+1);
            }

    printf("PASS INT8 ATTENTION MATMUL CHECKSUM=%lld\n",
           checksum);
    return 0;
}

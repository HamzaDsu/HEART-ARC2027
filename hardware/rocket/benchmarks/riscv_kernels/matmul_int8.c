#include <stdint.h>
#include <stdio.h>

#define M 32
#define N 32
#define K 64

static int8_t A[M][K];
static int8_t B[K][N];
static int32_t C[M][N];
static int32_t reference[M][N];

int main(void) {
    for (int i = 0; i < M; i++)
        for (int k = 0; k < K; k++)
            A[i][k] = (int8_t)((i * 3 + k * 5) % 13 - 6);

    for (int k = 0; k < K; k++)
        for (int j = 0; j < N; j++)
            B[k][j] = (int8_t)((k * 7 + j * 11) % 11 - 5);

    /* Integer matrix multiplication. */
    for (int i = 0; i < M; i++)
        for (int j = 0; j < N; j++)
            for (int k = 0; k < K; k++)
                C[i][j] += (int32_t)A[i][k] * B[k][j];

    /* Independent loop ordering for correctness checking. */
    for (int i = 0; i < M; i++)
        for (int k = 0; k < K; k++)
            for (int j = 0; j < N; j++)
                reference[i][j] +=
                    (int32_t)A[i][k] * B[k][j];

    long long checksum = 0;

    for (int i = 0; i < M; i++)
        for (int j = 0; j < N; j++) {
            if (C[i][j] != reference[i][j]) {
                printf("FAIL at [%d][%d]\n", i, j);
                return 1;
            }
            checksum +=
                (long long)C[i][j] * (i + 1) * (j + 1);
        }

    printf(
        "PASS INT8 GEMM M=%d N=%d K=%d CHECKSUM=%lld\n",
        M, N, K, checksum
    );

    return 0;
}

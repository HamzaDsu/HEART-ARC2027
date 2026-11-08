#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define QK_K 256
#define BLOCKS 32
#define REPEATS 10

typedef uint16_t ggml_half;

typedef struct {
    uint8_t hmask[QK_K / 8];
    uint8_t qs[QK_K / 4];
    uint8_t scales[12];
    ggml_half d;
} block_q3_K;

typedef struct {
    float d;
    int8_t qs[QK_K];
    int16_t bsums[QK_K / 16];
} block_q8_K;

static block_q3_K q3_blocks[BLOCKS];
static block_q8_K q8_blocks[BLOCKS];

static inline uint64_t rdcycle(void)
{
    uint64_t x;
    asm volatile ("rdcycle %0" : "=r"(x));
    return x;
}

static float fp16_to_fp32(uint16_t h)
{
    uint32_t sign = ((uint32_t) h & 0x8000u) << 16;
    uint32_t exp  = ((uint32_t) h >> 10) & 0x1fu;
    uint32_t frac = (uint32_t) h & 0x03ffu;
    uint32_t out;

    if (exp == 0) {
        if (frac == 0) {
            out = sign;
        } else {
            int shift = 0;
            while ((frac & 0x0400u) == 0) {
                frac <<= 1;
                shift++;
            }
            frac &= 0x03ffu;
            out = sign |
                  ((uint32_t) (127 - 15 - shift) << 23) |
                  (frac << 13);
        }
    } else if (exp == 31) {
        out = sign | 0x7f800000u | (frac << 13);
    } else {
        out = sign |
              ((exp + (127 - 15)) << 23) |
              (frac << 13);
    }

    union {
        uint32_t u;
        float f;
    } cvt;

    cvt.u = out;
    return cvt.f;
}

/*
 * Scalar Q3_K x Q8_K dot product based on the generic llama.cpp path.
 */
static void q3k_q8k_dot(
    int n,
    float *result,
    const block_q3_K *x,
    const block_q8_K *y)
{
    const uint32_t kmask1 = 0x03030303u;
    const uint32_t kmask2 = 0x0f0f0f0fu;

    const int nb = n / QK_K;

    int8_t  aux8[QK_K];
    int16_t aux16[8];
    float   sums[8];
    int32_t aux32[8];

    uint32_t auxs[4];

    memset(sums, 0, sizeof(sums));

    const int8_t *scales = (const int8_t *) auxs;

    for (int i = 0; i < nb; ++i) {
        const uint8_t *q3 = x[i].qs;
        const uint8_t *hm = x[i].hmask;
        const int8_t  *q8 = y[i].qs;

        memset(aux32, 0, sizeof(aux32));

        int8_t *a = aux8;
        uint8_t m = 1;

        for (int j = 0; j < QK_K; j += 128) {
            for (int l = 0; l < 32; ++l)
                a[l] = q3[l] & 3;

            for (int l = 0; l < 32; ++l)
                a[l] -= (hm[l] & m) ? 0 : 4;

            a += 32;
            m <<= 1;

            for (int l = 0; l < 32; ++l)
                a[l] = (q3[l] >> 2) & 3;

            for (int l = 0; l < 32; ++l)
                a[l] -= (hm[l] & m) ? 0 : 4;

            a += 32;
            m <<= 1;

            for (int l = 0; l < 32; ++l)
                a[l] = (q3[l] >> 4) & 3;

            for (int l = 0; l < 32; ++l)
                a[l] -= (hm[l] & m) ? 0 : 4;

            a += 32;
            m <<= 1;

            for (int l = 0; l < 32; ++l)
                a[l] = (q3[l] >> 6) & 3;

            for (int l = 0; l < 32; ++l)
                a[l] -= (hm[l] & m) ? 0 : 4;

            a += 32;
            m <<= 1;

            q3 += 32;
        }

        a = aux8;

        memcpy(auxs, x[i].scales, 12);

        uint32_t tmp = auxs[2];

        auxs[2] =
            ((auxs[0] >> 4) & kmask2) |
            (((tmp >> 4) & kmask1) << 4);

        auxs[3] =
            ((auxs[1] >> 4) & kmask2) |
            (((tmp >> 6) & kmask1) << 4);

        auxs[0] =
            (auxs[0] & kmask2) |
            (((tmp >> 0) & kmask1) << 4);

        auxs[1] =
            (auxs[1] & kmask2) |
            (((tmp >> 2) & kmask1) << 4);

        for (int j = 0; j < QK_K / 16; ++j) {
            for (int l = 0; l < 8; ++l)
                aux16[l] = q8[l] * a[l];

            for (int l = 0; l < 8; ++l)
                aux32[l] +=
                    (scales[j] - 32) * aux16[l];

            q8 += 8;
            a  += 8;

            for (int l = 0; l < 8; ++l)
                aux16[l] = q8[l] * a[l];

            for (int l = 0; l < 8; ++l)
                aux32[l] +=
                    (scales[j] - 32) * aux16[l];

            q8 += 8;
            a  += 8;
        }

        const float d = fp16_to_fp32(x[i].d) * y[i].d;

        for (int l = 0; l < 8; ++l)
            sums[l] += d * aux32[l];
    }

    float sumf = 0.0f;

    for (int l = 0; l < 8; ++l)
        sumf += sums[l];

    *result = sumf;
}

static void initialize_blocks(void)
{
    uint32_t state = 0x12345678u;

    for (int b = 0; b < BLOCKS; ++b) {

        /* FP16 representation of 1.0 */
        q3_blocks[b].d = 0x3c00;

        /* Activation scale */
        q8_blocks[b].d = 1.0f;

        for (int i = 0; i < QK_K / 8; ++i) {
            state = state * 1664525u + 1013904223u;
            q3_blocks[b].hmask[i] = (uint8_t)(state >> 24);
        }

        for (int i = 0; i < QK_K / 4; ++i) {
            state = state * 1664525u + 1013904223u;
            q3_blocks[b].qs[i] = (uint8_t)(state >> 24);
        }

        for (int i = 0; i < 12; ++i) {
            state = state * 1664525u + 1013904223u;
            q3_blocks[b].scales[i] = (uint8_t)(state >> 24);
        }

        for (int i = 0; i < QK_K; ++i) {
            state = state * 1664525u + 1013904223u;

            /* approximately -63 ... +63 */
            q8_blocks[b].qs[i] =
                (int8_t)((state >> 24) & 0x7f) - 63;
        }

        for (int i = 0; i < QK_K / 16; ++i) {
            int sum = 0;

            for (int j = 0; j < 16; ++j)
                sum += q8_blocks[b].qs[i * 16 + j];

            q8_blocks[b].bsums[i] = (int16_t)sum;
        }
    }
}

int main(void)
{
    printf("Health-SLM Q3_K x Q8_K benchmark\n");
    printf("QK_K       : %d\n", QK_K);
    printf("Blocks     : %d\n", BLOCKS);
    printf("Repeats    : %d\n", REPEATS);
    printf("Q3 size    : %lu bytes\n",
           (unsigned long)sizeof(block_q3_K));
    printf("Q8 size    : %lu bytes\n",
           (unsigned long)sizeof(block_q8_K));

    initialize_blocks();

    volatile float checksum = 0.0f;

    /* Warm-up */
    float tmp;
    q3k_q8k_dot(
        BLOCKS * QK_K,
        &tmp,
        q3_blocks,
        q8_blocks);

    uint64_t start = rdcycle();

    for (int r = 0; r < REPEATS; ++r) {
        float result;

        q3k_q8k_dot(
            BLOCKS * QK_K,
            &result,
            q3_blocks,
            q8_blocks);

        checksum += result;
    }

    uint64_t end = rdcycle();

    uint64_t cycles = end - start;

    const uint64_t block_ops =
        (uint64_t)BLOCKS * REPEATS;

    printf("Total cycles : %lu\n",
           (unsigned long)cycles);

    printf("Block dots   : %lu\n",
           (unsigned long)block_ops);

    printf("Cycles/block : %lu\n",
           (unsigned long)(cycles / block_ops));

    printf("Cycles/value : %lu\n",
           (unsigned long)(cycles /
             (block_ops * QK_K)));

    union { float f; uint32_t u; } checksum_bits;
    checksum_bits.f = checksum;
    printf("Checksum bits: 0x%08lx\n", (unsigned long)checksum_bits.u);

    return 0;
}

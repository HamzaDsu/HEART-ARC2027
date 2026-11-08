#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

static float *load(const char *path, size_t n) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }
    float *p = malloc(n * sizeof(float));
    if (!p) exit(2);
    if (fread(p, sizeof(float), n, f) != n || fgetc(f) != EOF) {
        fprintf(stderr, "Invalid tensor: %s\n", path);
        exit(2);
    }
    fclose(f);
    return p;
}

static void save(const char *path, const float *p, size_t n) {
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); exit(2); }
    if (fwrite(p, sizeof(float), n, f) != n) exit(2);
    if (fclose(f)) exit(2);
}

int main(int argc, char **argv) {
    if (argc == 6 && strcmp(argv[1], "softmax") == 0) {
        size_t rows = strtoull(argv[4], NULL, 10);
        size_t cols = strtoull(argv[5], NULL, 10);
        if (!rows || !cols || rows > SIZE_MAX / cols) return 2;
        size_t n = rows * cols;
        float *x = load(argv[2], n);
        float *y = malloc(n * sizeof(float));
        if (!y) return 2;

        for (size_t r = 0; r < rows; ++r) {
            size_t off = r * cols;
            double maximum = -INFINITY;
            for (size_t j = 0; j < cols; ++j)
                if (x[off+j] > maximum) maximum = x[off+j];

            double total = 0.0;
            for (size_t j = 0; j < cols; ++j)
                total += exp((double)x[off+j] - maximum);

            for (size_t j = 0; j < cols; ++j)
                y[off+j] = (float)(
                    exp((double)x[off+j] - maximum) / total
                );
        }
        save(argv[3], y, n);
        free(x); free(y);
        return 0;
    }

    if (argc == 9 && strcmp(argv[1], "value") == 0) {
        size_t B = strtoull(argv[5], NULL, 10);
        size_t H = strtoull(argv[6], NULL, 10);
        size_t N = strtoull(argv[7], NULL, 10);
        size_t C = strtoull(argv[8], NULL, 10);
        if (!B || !H || !N || !C || C % H) return 2;
        if (B > SIZE_MAX/N || B*N > SIZE_MAX/(3*C)) return 2;
        size_t D = C/H;
        float *qkv = load(argv[2], B*N*3*C);
        float *attn = load(argv[3], B*H*N*N);
        float *out = calloc(B*N*C, sizeof(float));
        if (!out) return 2;

        for (size_t b = 0; b < B; ++b)
        for (size_t h = 0; h < H; ++h)
        for (size_t i = 0; i < N; ++i)
        for (size_t d = 0; d < D; ++d) {
            double sum = 0.0;
            for (size_t j = 0; j < N; ++j) {
                size_t ai = ((b*H+h)*N+i)*N+j;
                size_t vi = (b*N+j)*3*C+2*C+h*D+d;
                sum += (double)attn[ai] * (double)qkv[vi];
            }
            out[(b*N+i)*C+h*D+d] = (float)sum;
        }

        save(argv[4], out, B*N*C);
        free(qkv); free(attn); free(out);
        return 0;
    }

    fprintf(stderr, "Invalid arguments\n");
    return 2;
}

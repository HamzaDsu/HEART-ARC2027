
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <stdint.h>

static float *read_f32(const char *path, size_t n) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }

    float *a = malloc(n * sizeof(float));
    if (!a) { perror("malloc"); exit(2); }

    if (fread(a, sizeof(float), n, f) != n) {
        fprintf(stderr, "Incorrect tensor size: %s\n", path);
        exit(2);
    }
    if (fgetc(f) != EOF) {
        fprintf(stderr, "Unexpected extra data: %s\n", path);
        exit(2);
    }
    fclose(f);
    return a;
}

int main(int argc, char **argv) {
    if (argc != 7) {
        fprintf(stderr, "Usage: layernorm input weight bias output rows cols\n");
        return 2;
    }

    size_t rows = strtoull(argv[5], NULL, 10);
    size_t cols = strtoull(argv[6], NULL, 10);

    if (!rows || !cols || rows > SIZE_MAX / cols) return 2;

    size_t n = rows * cols;
    float *x = read_f32(argv[1], n);
    float *w = read_f32(argv[2], cols);
    float *b = read_f32(argv[3], cols);
    float *y = malloc(n * sizeof(float));
    if (!y) return 2;

    for (size_t r = 0; r < rows; ++r) {
        size_t offset = r * cols;
        double mean = 0.0;
        double variance = 0.0;

        for (size_t c = 0; c < cols; ++c)
            mean += x[offset + c];
        mean /= (double)cols;

        for (size_t c = 0; c < cols; ++c) {
            double d = (double)x[offset + c] - mean;
            variance += d * d;
        }

        double inv_std = 1.0 / sqrt(variance / (double)cols + 1e-5);

        for (size_t c = 0; c < cols; ++c) {
            double normalized = ((double)x[offset + c] - mean) * inv_std;
            y[offset + c] = (float)(
                normalized * (double)w[c] + (double)b[c]
            );
        }
    }

    FILE *f = fopen(argv[4], "wb");
    if (!f) { perror(argv[4]); return 2; }
    if (fwrite(y, sizeof(float), n, f) != n) return 2;
    if (fclose(f) != 0) return 2;

    free(x);
    free(w);
    free(b);
    free(y);
    return 0;
}

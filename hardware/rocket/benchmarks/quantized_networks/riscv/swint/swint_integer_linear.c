#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>

static void read_exact(const char *path, void *data, size_t bytes) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }
    if (fread(data, 1, bytes, f) != bytes || fgetc(f) != EOF) {
        fprintf(stderr, "Invalid tensor: %s\n", path);
        exit(2);
    }
    fclose(f);
}

int main(int argc, char **argv) {
    if (argc != 13) {
        fprintf(stderr, "Usage: linear x w scales bias out rows in out actbits calibrated sx zp\n");
        return 2;
    }

    size_t rows = strtoull(argv[6], NULL, 10);
    size_t cin = strtoull(argv[7], NULL, 10);
    size_t cout = strtoull(argv[8], NULL, 10);
    int bits = atoi(argv[9]);
    int calibrated = atoi(argv[10]);

    if (!rows || !cin || !cout || bits < 2 || bits > 16 ||
        rows > SIZE_MAX / cin || cout > SIZE_MAX / cin ||
        rows > SIZE_MAX / cout ||
        rows * cout > SIZE_MAX / sizeof(float)) return 2;

    float *x = malloc(rows * cin * sizeof(float));
    int8_t *w = malloc(cout * cin);
    float *sw = malloc(cout * sizeof(float));
    float *bias = malloc(cout * sizeof(float));
    float *out = malloc(rows * cout * sizeof(float));
    int32_t *qx = malloc(rows * cin * sizeof(int32_t));

    if (!x || !w || !sw || !bias || !out || !qx) return 2;

    read_exact(argv[1], x, rows * cin * sizeof(float));
    read_exact(argv[2], w, cout * cin);
    read_exact(argv[3], sw, cout * sizeof(float));
    read_exact(argv[4], bias, cout * sizeof(float));

    float qmax = (float)((1u << bits) - 1u);
    float sx = strtof(argv[11], NULL);
    float zp = strtof(argv[12], NULL);

    if (!calibrated) {
        float lo = x[0], hi = x[0];

        for (size_t i = 1; i < rows * cin; ++i) {
            if (x[i] < lo) lo = x[i];
            if (x[i] > hi) hi = x[i];
        }

        sx = (hi - lo) / qmax;
        if (sx < 1e-8f) sx = 1e-8f;

        zp = nearbyintf(-lo / sx);
        if (zp < 0) zp = 0;
        if (zp > qmax) zp = qmax;
    }

    if (!isfinite(sx) || sx <= 0 || !isfinite(zp)) return 2;

    for (size_t i = 0; i < rows * cin; ++i) {
        float v = nearbyintf(x[i] / sx + zp);
        if (v < 0) v = 0;
        if (v > qmax) v = qmax;
        qx[i] = (int32_t)(v - zp);
    }

    for (size_t r = 0; r < rows; ++r)
    for (size_t o = 0; o < cout; ++o) {
        int64_t acc = 0;

        for (size_t k = 0; k < cin; ++k) {
            acc += (int64_t)qx[r * cin + k]
                 * (int64_t)w[o * cin + k];
        }

        out[r * cout + o] =
            ((float)acc * (sw[o] * sx)) + bias[o];
    }

    FILE *f = fopen(argv[5], "wb");
    if (!f) { perror(argv[5]); return 2; }

    if (fwrite(out, sizeof(float), rows * cout, f) != rows * cout)
        return 2;
    if (fclose(f)) return 2;

    free(x);
    free(w);
    free(sw);
    free(bias);
    free(qx);
    free(out);
    return 0;
}

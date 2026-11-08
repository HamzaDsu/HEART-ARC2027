#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

static void read_exact(const char *path, void *buf, size_t bytes) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }
    if (fread(buf, 1, bytes, f) != bytes || fgetc(f) != EOF) {
        fprintf(stderr, "Invalid input: %s\n", path);
        exit(2);
    }
    fclose(f);
}

int main(int argc, char **argv) {
    if (argc != 11) return 2;

    size_t h = strtoull(argv[6], NULL, 10);
    size_t w = strtoull(argv[7], NULL, 10);
    size_t ci = strtoull(argv[8], NULL, 10);
    size_t co = strtoull(argv[9], NULL, 10);
    float sx = strtof(argv[10], NULL);

    if (h != 224 || w != 224 || ci != 3 || co != 96 || sx <= 0)
        return 2;

    size_t oh = h / 4, ow = w / 4;

    int16_t *x = malloc(ci*h*w*sizeof(int16_t));
    int8_t *weights = malloc(co*ci*16);
    float *scales = malloc(co*sizeof(float));
    float *bias = malloc(co*sizeof(float));
    float *out = malloc(co*oh*ow*sizeof(float));

    if (!x || !weights || !scales || !bias || !out) return 2;

    read_exact(argv[1], x, ci*h*w*sizeof(int16_t));
    read_exact(argv[2], weights, co*ci*16);
    read_exact(argv[3], scales, co*sizeof(float));
    read_exact(argv[4], bias, co*sizeof(float));

    for (size_t oc=0; oc<co; ++oc)
    for (size_t y=0; y<oh; ++y)
    for (size_t z=0; z<ow; ++z) {
        int64_t acc = 0;

        for (size_t ic=0; ic<ci; ++ic)
        for (size_t ky=0; ky<4; ++ky)
        for (size_t kx=0; kx<4; ++kx) {
            size_t xi = (ic*h+y*4+ky)*w+z*4+kx;
            size_t wi = ((oc*ci+ic)*4+ky)*4+kx;
            acc += (int64_t)x[xi]*weights[wi];
        }

        out[(oc*oh+y)*ow+z] =
            (float)acc*(scales[oc]*sx)+bias[oc];
    }

    FILE *f = fopen(argv[5], "wb");
    if (!f) { perror(argv[5]); return 2; }
    if (fwrite(out, sizeof(float), co*oh*ow, f) != co*oh*ow)
        return 2;
    if (fclose(f)) return 2;

    free(x);
    free(weights);
    free(scales);
    free(bias);
    free(out);

    return 0;
}

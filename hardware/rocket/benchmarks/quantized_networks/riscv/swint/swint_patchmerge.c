#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

int main(int argc, char **argv) {
    if (argc != 6) return 2;

    size_t H = strtoull(argv[3], NULL, 10);
    size_t W = strtoull(argv[4], NULL, 10);
    size_t C = strtoull(argv[5], NULL, 10);

    if (!H || !W || !C || H % 2 || W % 2 ||
        H > SIZE_MAX/W || H*W > SIZE_MAX/C ||
        H*W*C > SIZE_MAX/sizeof(float)) return 2;

    size_t n = H*W*C;
    float *x = malloc(n*sizeof(float));
    float *y = malloc(n*sizeof(float));
    if (!x || !y) return 2;

    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 2; }
    if (fread(x, sizeof(float), n, f) != n ||
        fgetc(f) != EOF) return 2;
    fclose(f);

    for (size_t r=0; r<H/2; ++r)
    for (size_t c=0; c<W/2; ++c)
    for (size_t dw=0; dw<2; ++dw)
    for (size_t dh=0; dh<2; ++dh)
    for (size_t k=0; k<C; ++k) {
        size_t src = ((2*r+dh)*W+(2*c+dw))*C+k;
        size_t dst = (r*(W/2)+c)*(4*C)
                     +(dw*2+dh)*C+k;
        y[dst] = x[src];
    }

    f = fopen(argv[2], "wb");
    if (!f) { perror(argv[2]); return 2; }
    if (fwrite(y, sizeof(float), n, f) != n) return 2;
    if (fclose(f) != 0) return 2;

    free(x);
    free(y);
    return 0;
}

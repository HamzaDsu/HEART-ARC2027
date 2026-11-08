
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static float *readf(const char *path, size_t n) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(2); }
    float *a = malloc(n * sizeof(float));
    if (!a) exit(2);
    if (fread(a, sizeof(float), n, f) != n || fgetc(f) != EOF) {
        fprintf(stderr, "Invalid input: %s\n", path);
        exit(2);
    }
    fclose(f);
    return a;
}

int main(int argc, char **argv) {
    if (argc != 8) return 2;

    size_t S = strtoull(argv[5], NULL, 10);
    size_t C = strtoull(argv[6], NULL, 10);
    size_t shift = strtoull(argv[7], NULL, 10);

    if (!S || !C || S % 7 || shift > 3) return 2;

    int partition = strcmp(argv[1], "partition") == 0;
    int residual = strcmp(argv[1], "residual") == 0;
    if (!partition && !residual) return 2;

    size_t count = S*S*C;
    size_t nw = S/7;

    float *a = readf(argv[2], count);
    float *b = residual ? readf(argv[3], count) : NULL;
    float *y = malloc(count * sizeof(float));
    if (!y) return 2;

    for (size_t wr = 0; wr < nw; wr++)
    for (size_t wc = 0; wc < nw; wc++)
    for (size_t r = 0; r < 7; r++)
    for (size_t c = 0; c < 7; c++)
    for (size_t d = 0; d < C; d++) {
        size_t h = (wr*7 + r + shift) % S;
        size_t w = (wc*7 + c + shift) % S;
        size_t spatial = (h*S + w)*C + d;
        size_t window = (((wr*nw+wc)*49+r*7+c)*C)+d;

        if (partition)
            y[window] = a[spatial];
        else
            y[spatial] = b[spatial] + a[window];
    }

    FILE *f = fopen(argv[4], "wb");
    if (!f) { perror(argv[4]); return 2; }
    if (fwrite(y, sizeof(float), count, f) != count) return 2;
    if (fclose(f)) return 2;

    free(a);
    free(b);
    free(y);
    return 0;
}

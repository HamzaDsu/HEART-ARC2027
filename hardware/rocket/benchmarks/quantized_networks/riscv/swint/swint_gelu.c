#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>

int main(int argc, char **argv) {
    if (argc != 4) return 2;

    size_t n = strtoull(argv[3], NULL, 10);
    if (!n || n > SIZE_MAX / sizeof(float)) return 2;

    float *x = malloc(n * sizeof(float));
    if (!x) return 2;

    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 2; }

    if (fread(x, sizeof(float), n, f) != n ||
        fgetc(f) != EOF) return 2;
    fclose(f);

    const float inv_sqrt2 = 0.7071067811865475f;

    for (size_t i = 0; i < n; ++i) {
        float v = x[i];
        x[i] = 0.5f * v *
               (1.0f + erff(v * inv_sqrt2));
    }

    f = fopen(argv[2], "wb");
    if (!f) { perror(argv[2]); return 2; }

    if (fwrite(x, sizeof(float), n, f) != n)
        return 2;
    if (fclose(f)) return 2;

    free(x);
    return 0;
}

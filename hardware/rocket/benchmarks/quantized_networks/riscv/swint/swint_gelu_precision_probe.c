#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>

int main(int argc, char **argv) {
    if (argc != 5) return 2;

    size_t n = strtoull(argv[3], NULL, 10);
    int mode = atoi(argv[4]);
    if (!n || n > SIZE_MAX / sizeof(float) ||
        mode < 0 || mode > 4) return 2;

    float *x = malloc(n * sizeof(float));
    if (!x) return 2;

    FILE *f = fopen(argv[1], "rb");
    if (!f) return 2;
    if (fread(x, sizeof(float), n, f) != n ||
        fgetc(f) != EOF) return 2;
    fclose(f);

    const float af = 0.7071067811865475f;
    const double ad = 0.7071067811865475244;

    for (size_t i = 0; i < n; ++i) {
        float v = x[i];

        switch (mode) {
        case 0:
            x[i] = 0.5f*v*(1.0f+erff(v*af));
            break;
        case 1:
            x[i] = (float)(
                ((double)v*0.5)*
                (1.0+erf((double)v*ad))
            );
            break;
        case 2:
            x[i] = 0.5f*v*erfcf(-v*af);
            break;
        case 3:
            x[i] = (float)(
                ((double)v*0.5)*
                erfc(-(double)v*ad)
            );
            break;
        case 4:
            x[i] = (v*0.5f)*
                   (1.0f+(float)erf((double)v*(double)af));
            break;
        }
    }

    f = fopen(argv[2], "wb");
    if (!f) return 2;
    int ok = fwrite(x, sizeof(float), n, f) == n;
    ok = (fclose(f) == 0) && ok;
    free(x);
    return ok ? 0 : 2;
}

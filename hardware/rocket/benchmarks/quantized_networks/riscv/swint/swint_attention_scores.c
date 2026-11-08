#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>

static float *load(const char *name, size_t n) {
    FILE *f = fopen(name, "rb");
    if (!f) { perror(name); exit(2); }

    float *p = malloc(n * sizeof(*p));
    if (!p || fread(p, sizeof(*p), n, f) != n || fgetc(f) != EOF) {
        fprintf(stderr, "Invalid tensor: %s\n", name);
        exit(2);
    }
    fclose(f);
    return p;
}

static int region(size_t z, size_t spatial, size_t shift) {
    return z < spatial - 7 ? 0 : z < spatial - shift ? 1 : 2;
}

int main(int argc, char **argv) {
    if (argc != 9) {
        fprintf(stderr, "Usage: %s qkv bias out windows heads channels spatial shift\n", argv[0]);
        return 2;
    }

    size_t B = strtoull(argv[4], NULL, 10);
    size_t H = strtoull(argv[5], NULL, 10);
    size_t C = strtoull(argv[6], NULL, 10);
    size_t S = strtoull(argv[7], NULL, 10);
    size_t shift = strtoull(argv[8], NULL, 10);
    const size_t N = 49;

    if (!B || !H || !C || C % H || S < 7 || S % 7 ||
        shift > 3 || B != (S/7)*(S/7) ||
        B > SIZE_MAX / (N*3*C)) return 2;

    size_t D = C/H;
    float *qkv = load(argv[1], B*N*3*C);
    float *bias = load(argv[2], 169*H);
    float *out = malloc(B*H*N*N*sizeof(*out));
    if (!out) return 2;

    float scale = 1.0f/sqrtf((float)D);

    for (size_t b=0; b<B; ++b) {
        size_t wr = b/(S/7), wc = b%(S/7);

        for (size_t h=0; h<H; ++h)
        for (size_t i=0; i<N; ++i)
        for (size_t j=0; j<N; ++j) {
            double sum = 0.0;

            for (size_t d=0; d<D; ++d) {
                float q = qkv[(b*N+i)*3*C + h*D+d];
                float k = qkv[(b*N+j)*3*C + C+h*D+d];
                sum += (double)(q*scale)*k;
            }

            size_t ri=i/7, ci=i%7, rj=j/7, cj=j%7;
            size_t idx=(ri+6-rj)*13+(ci+6-cj);
            float val=(float)sum + bias[idx*H+h];

            if (shift) {
                int a=3*region(wr*7+ri,S,shift)
                     +region(wc*7+ci,S,shift);
                int z=3*region(wr*7+rj,S,shift)
                     +region(wc*7+cj,S,shift);
                if (a != z) val -= 100.0f;
            }

            out[((b*H+h)*N+i)*N+j] = val;
        }
    }

    FILE *f=fopen(argv[3],"wb");
    if (!f) { perror(argv[3]); return 2; }
    if (fwrite(out,sizeof(*out),B*H*N*N,f)!=B*H*N*N) return 2;
    if (fclose(f)!=0) return 2;

    free(qkv);
    free(bias);
    free(out);
    return 0;
}

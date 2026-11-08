#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>

#define WIN 7
#define N 49
#define QCOUNT 53

typedef struct {
    char name[128];
    int bits, calibrated;
    float sx, zp;
} Quant;

static Quant qc[QCOUNT];
static char root[1024], dumpdir[1024];

static void die(const char *s) {
    fprintf(stderr, "ERROR: %s\n", s);
    exit(2);
}

static void *allocate(size_t n, size_t size) {
    if (!size || n > SIZE_MAX / size)
        die("Allocation overflow");
    void *p = malloc(n * size);
    if (!p) die("Out of memory");
    return p;
}

static void path_for(char *out, size_t cap,
                     const char *name, const char *suffix) {
    char clean[256];
    size_t n = strlen(name);
    if (n >= sizeof(clean)) die("Long tensor name");

    for (size_t i = 0; i < n; i++)
        clean[i] = (name[i] == '.') ? '_' : name[i];
    clean[n] = 0;

    if (snprintf(out, cap, "%s/%s_%s.bin",
                 root, clean, suffix) >= (int)cap)
        die("Long path");
}

static void read_array(const char *file, void *dst,
                       size_t len, size_t unit) {
    FILE *f = fopen(file, "rb");
    if (!f) { perror(file); exit(2); }
    if (fread(dst, unit, len, f) != len || fgetc(f) != EOF)
        die("Parameter size mismatch");
    if (fclose(f)) die("Error closing file");
}

static void read_named(const char *name, const char *suffix,
                       void *dst, size_t n, size_t unit) {
    char p[1536];
    path_for(p, sizeof(p), name, suffix);
    read_array(p, dst, n, unit);
}

static void save_bin(const char *file,
                     const float *data, size_t n) {
    FILE *f = fopen(file, "wb");
    if (!f) { perror(file); exit(2); }
    if (fwrite(data, sizeof(float), n, f) != n)
        die("Failed writing output");
    if (fclose(f)) die("Failed closing output");
}

static void dump_block(int stage, int block,
                       const float *x, size_t n) {
    if (!dumpdir[0]) return;
    char p[1536];
    snprintf(p, sizeof(p), "%s/layers.%d.blocks.%d.bin",
             dumpdir, stage, block);
    save_bin(p, x, n);
}

static const Quant *get_quant(const char *name) {
    for (int i = 0; i < QCOUNT; i++)
        if (!strcmp(qc[i].name, name)) return &qc[i];

    fprintf(stderr, "Missing quantization config: %s\n", name);
    exit(2);
}

static float *quantize(const float *x, size_t count,
                       const Quant *q, float *scale) {
    if (q->bits < 2 || q->bits > 16)
        die("Unsupported activation bits");

    float qmax = (float)((1u << q->bits) - 1u);
    float sx = q->sx, zp = q->zp;

    if (!q->calibrated) {
        float lo = x[0], hi = x[0];

        for (size_t i = 1; i < count; i++) {
            if (x[i] < lo) lo = x[i];
            if (x[i] > hi) hi = x[i];
        }

        sx = fmaxf((hi - lo) / qmax, 1e-8f);
        zp = fminf(qmax,
                   fmaxf(0.0f, nearbyintf(-lo / sx)));
    }

    if (!isfinite(sx) || sx <= 0 || !isfinite(zp))
        die("Bad activation scale");

    float *qinput = allocate(count, sizeof(float));

    for (size_t i = 0; i < count; i++) {
        float v = nearbyintf(x[i] / sx + zp);
        qinput[i] = fminf(qmax, fmaxf(0.0f, v)) - zp;
    }

    *scale = sx;
    return qinput;
}

static float *linear(const char *name, const float *x,
                     size_t rows, int ci, int co) {
    const Quant *q = get_quant(name);
    float sx;
    float *qi = quantize(x, rows * (size_t)ci, q, &sx);

    int8_t *w = allocate((size_t)ci * co, sizeof(int8_t));
    float *sw = allocate(co, sizeof(float));
    float *bias = allocate(co, sizeof(float));
    float *out = allocate(rows * (size_t)co, sizeof(float));

    read_named(name, "weights", w,
               (size_t)ci * co, sizeof(int8_t));
    read_named(name, "scales", sw, co, sizeof(float));
    read_named(name, "bias", bias, co, sizeof(float));

    for (size_t r = 0; r < rows; r++)
    for (int o = 0; o < co; o++) {
        int64_t sum = 0;

        for (int k = 0; k < ci; k++)
            sum += (int64_t)qi[r * ci + k]
                 * w[(size_t)o * ci + k];

        out[r * co + o] =
            (float)sum * (sw[o] * sx) + bias[o];
    }

    free(qi);
    free(w);
    free(sw);
    free(bias);
    return out;
}

static float *layer_norm(const char *name, const float *x,
                         size_t rows, int c) {
    float *gamma = allocate(c, sizeof(float));
    float *beta = allocate(c, sizeof(float));
    float *out = allocate(rows * (size_t)c, sizeof(float));

    read_named(name, "ln_weight", gamma, c, sizeof(float));
    read_named(name, "ln_bias", beta, c, sizeof(float));

    for (size_t r = 0; r < rows; r++) {
        const float *v = x + r * c;
        double mean = 0, var = 0;

        for (int j = 0; j < c; j++) mean += v[j];
        mean /= c;

        for (int j = 0; j < c; j++) {
            double d = v[j] - mean;
            var += d * d;
        }
        var /= c;

        float scale = 1.0f / sqrtf((float)var + 1e-5f);

        for (int j = 0; j < c; j++)
            out[r * c + j] =
                ((v[j] - (float)mean) * scale)
                * gamma[j] + beta[j];
    }

    free(gamma);
    free(beta);
    return out;
}

static float *patch_embed(const float *x) {
    const char *name = "patch_embed.proj";
    const Quant *q = get_quant(name);
    float sx;

    float *qi = quantize(x, 3u * 224 * 224, q, &sx);

    int8_t *w = allocate(96 * 3 * 16, sizeof(int8_t));
    float *sw = allocate(96, sizeof(float));
    float *bias = allocate(96, sizeof(float));
    float *out = allocate(56u * 56 * 96, sizeof(float));

    read_named(name, "weights", w,
               96 * 3 * 16, sizeof(int8_t));
    read_named(name, "scales", sw, 96, sizeof(float));
    read_named(name, "bias", bias, 96, sizeof(float));

    for (int y = 0; y < 56; y++)
    for (int z = 0; z < 56; z++)
    for (int o = 0; o < 96; o++) {
        int64_t sum = 0;

        for (int ic = 0; ic < 3; ic++)
        for (int dy = 0; dy < 4; dy++)
        for (int dx = 0; dx < 4; dx++) {
            size_t xi =
                ((size_t)ic * 224 + y * 4 + dy)
                * 224 + z * 4 + dx;

            size_t wi =
                ((size_t)o * 3 + ic) * 16
                + dy * 4 + dx;

            sum += (int64_t)qi[xi] * w[wi];
        }

        out[((size_t)y * 56 + z) * 96 + o] =
            (float)sum * (sw[o] * sx) + bias[o];
    }

    free(qi);
    free(w);
    free(sw);
    free(bias);
    return out;
}

static float *downsample(const float *x,
                         int h, int w, int c, int stage) {
    char name[128];
    float *grouped =
        allocate((size_t)h * w * c, sizeof(float));

    for (int y = 0; y < h / 2; y++)
    for (int z = 0; z < w / 2; z++) {
        size_t dst = ((size_t)y * (w / 2) + z) * 4 * c;

        for (int dy = 0; dy < 2; dy++)
        for (int dx = 0; dx < 2; dx++) {
            size_t src =
                ((size_t)(y * 2 + dy) * w + z * 2 + dx) * c;

            memcpy(
                grouped + dst + (dx * 2 + dy) * c,
                x + src,
                c * sizeof(float)
            );
        }
    }

    snprintf(name, sizeof(name),
             "layers.%d.downsample.norm", stage);

    float *normed = layer_norm(
        name, grouped,
        (size_t)(h / 2) * (w / 2), 4 * c
    );
    free(grouped);

    snprintf(name, sizeof(name),
             "layers.%d.downsample.reduction", stage);

    float *result = linear(
        name, normed,
        (size_t)(h / 2) * (w / 2), 4 * c, 2 * c
    );

    free(normed);
    return result;
}

static int region(int pos, int extent) {
    if (pos < extent - 7) return 0;
    if (pos < extent - 3) return 1;
    return 2;
}

static float *attention(const float *src,
                        int h, int w, int c,
                        int heads, int shift,
                        int stage, int block) {
    char name[128];
    int windows = (h / 7) * (w / 7);

    float *partitioned =
        allocate((size_t)h * w * c, sizeof(float));

    for (int win = 0; win < windows; win++) {
        int y0 = (win / (w / 7)) * 7;
        int z0 = (win % (w / 7)) * 7;

        for (int t = 0; t < N; t++) {
            int yy = (y0 + t / 7 + shift) % h;
            int zz = (z0 + t % 7 + shift) % w;

            size_t dst = ((size_t)win * N + t) * c;
            size_t from = ((size_t)yy * w + zz) * c;

            memcpy(partitioned + dst, src + from,
                   c * sizeof(float));
        }
    }

    snprintf(name, sizeof(name),
             "layers.%d.blocks.%d.attn.qkv",
             stage, block);

    float *qkv = linear(
        name, partitioned, (size_t)windows * N, c, 3 * c
    );
    free(partitioned);

    snprintf(name, sizeof(name),
             "layers.%d.blocks.%d.attn", stage, block);

    float *rel = allocate(169u * heads, sizeof(float));
    read_named(name, "relative_bias", rel,
               169u * heads, sizeof(float));

    float *weighted =
        allocate((size_t)windows * N * c, sizeof(float));

    int dim = c / heads;

    for (int win = 0; win < windows; win++)
    for (int head = 0; head < heads; head++) {
        int y0 = (win / (w / 7)) * 7;
        int z0 = (win % (w / 7)) * 7;

        for (int t = 0; t < N; t++) {
            float scores[N];
            float maxv = -INFINITY;

            for (int j = 0; j < N; j++) {
                float dot = 0;

                const float *qq =
                    qkv + ((size_t)win * N + t) * 3 * c
                    + head * dim;

                const float *kk =
                    qkv + ((size_t)win * N + j) * 3 * c
                    + c + head * dim;

                for (int d = 0; d < dim; d++)
                    dot += qq[d] * kk[d];

                int a = t / 7, b = t % 7;
                int p = j / 7, q = j % 7;

                int idx =
                    ((a - p + 6) * 13 + (b - q + 6))
                    * heads + head;

                float score =
                    dot * (1.0f / sqrtf((float)dim))
                    + rel[idx];

                if (shift &&
                    (region(y0 + a, h) != region(y0 + p, h) ||
                     region(z0 + b, w) != region(z0 + q, w)))
                    score -= 100.0f;

                scores[j] = score;
                if (score > maxv) maxv = score;
            }

            float denom = 0;

            for (int j = 0; j < N; j++) {
                scores[j] = expf(scores[j] - maxv);
                denom += scores[j];
            }

            for (int d = 0; d < dim; d++) {
                float sum = 0;

                for (int j = 0; j < N; j++) {
                    float v = qkv[
                        ((size_t)win * N + j) * 3 * c
                        + 2 * c + head * dim + d
                    ];
                    sum += (scores[j] / denom) * v;
                }

                weighted[
                    ((size_t)win * N + t) * c
                    + head * dim + d
                ] = sum;
            }
        }
    }

    free(qkv);
    free(rel);

    snprintf(name, sizeof(name),
             "layers.%d.blocks.%d.attn.proj",
             stage, block);

    float *projected = linear(
        name, weighted, (size_t)windows * N, c, c
    );
    free(weighted);

    float *result =
        allocate((size_t)h * w * c, sizeof(float));

    for (int win = 0; win < windows; win++) {
        int y0 = (win / (w / 7)) * 7;
        int z0 = (win % (w / 7)) * 7;

        for (int t = 0; t < N; t++) {
            int yy = (y0 + t / 7 + shift) % h;
            int zz = (z0 + t % 7 + shift) % w;

            memcpy(
                result + ((size_t)yy * w + zz) * c,
                projected + ((size_t)win * N + t) * c,
                c * sizeof(float)
            );
        }
    }

    free(projected);
    return result;
}

static float *block_forward(float *x,
                            int h, int w, int c,
                            int heads, int shift,
                            int stage, int block) {
    char name[128];
    size_t count = (size_t)h * w * c;
    size_t rows = (size_t)h * w;

    snprintf(name, sizeof(name),
             "layers.%d.blocks.%d.norm1", stage, block);

    float *normed = layer_norm(name, x, rows, c);

    float *att = attention(
        normed, h, w, c, heads, shift, stage, block
    );
    free(normed);

    for (size_t i = 0; i < count; i++)
        x[i] += att[i];
    free(att);

    snprintf(name, sizeof(name),
             "layers.%d.blocks.%d.norm2", stage, block);

    normed = layer_norm(name, x, rows, c);

    snprintf(name, sizeof(name),
             "layers.%d.blocks.%d.mlp.fc1",
             stage, block);

    float *wide = linear(name, normed, rows, c, 4 * c);
    free(normed);

    for (size_t i = 0; i < rows * (size_t)(4 * c); i++) {
        float v = wide[i];
        wide[i] =
            0.5f * v *
            (1.0f + erff(v * 0.7071067811865475f));
    }

    snprintf(name, sizeof(name),
             "layers.%d.blocks.%d.mlp.fc2",
             stage, block);

    float *out = linear(name, wide, rows, 4 * c, c);
    free(wide);

    for (size_t i = 0; i < count; i++)
        x[i] += out[i];
    free(out);

    dump_block(stage, block, x, count);
    return x;
}

static void load_config(const char *filepath,
                        int *head_mode) {
    FILE *f = fopen(filepath, "r");
    if (!f) { perror(filepath); exit(2); }

    int count = 0;

    if (fscanf(f, "%d %d", &count, head_mode) != 2 ||
        count != QCOUNT)
        die("Invalid config header");

    for (int i = 0; i < QCOUNT; i++) {
        Quant *q = &qc[i];

        if (fscanf(f, "%127s %d %d %f %f",
                   q->name, &q->bits, &q->calibrated,
                   &q->sx, &q->zp) != 5)
            die("Invalid quantization config");
    }

    if (fclose(f)) die("Failed closing config");
}

int main(int argc, char **argv) {
    if (argc < 4 || argc > 5) {
        fprintf(stderr,
            "Usage: %s RUNTIME_DIR CONFIG.TXT OUTPUT.BIN [DUMP_DIR]\n",
            argv[0]);
        return 2;
    }

    if (strlen(argv[1]) >= sizeof(root))
        die("Runtime path too long");
    strcpy(root, argv[1]);

    if (argc == 5) {
        if (strlen(argv[4]) >= sizeof(dumpdir))
            die("Dump path too long");
        strcpy(dumpdir, argv[4]);
    }

    int head_mode = -1;
    load_config(argv[2], &head_mode);

    char img[1536];
    snprintf(img, sizeof(img), "%s/input.bin", root);

    float *input =
        allocate(3u * 224 * 224, sizeof(float));

    read_array(img, input, 3u * 224 * 224,
               sizeof(float));

    float *x = patch_embed(input);
    free(input);

    float *normed = layer_norm(
        "patch_embed.norm", x, 56u * 56, 96
    );
    free(x);
    x = normed;

    const int counts[] = {2, 2, 6, 2};
    int h = 56, w = 56, c = 96;

    for (int s = 0; s < 4; s++) {
        if (s) {
            float *next = downsample(x, h, w, c, s);
            free(x);
            x = next;
            h /= 2;
            w /= 2;
            c *= 2;
        }

        for (int b = 0; b < counts[s]; b++) {
            int shift =
                (b % 2 && h > 7 && w > 7) ? 3 : 0;

            x = block_forward(
                x, h, w, c, 3 * (1 << s),
                shift, s, b
            );

            fprintf(stderr,
                "Completed stage %d block %d (%dx%dx%d)\n",
                s, b, h, w, c);
        }
    }

    normed = layer_norm(
        "norm", x, (size_t)h * w, c
    );
    free(x);
    x = normed;

    float *pooled = allocate(c, sizeof(float));

    if (head_mode == 1) {
        normed = layer_norm(
            "head.0", x, (size_t)h * w, c
        );
        free(x);
        x = normed;
    }

    for (int i = 0; i < c; i++) {
        double acc = 0;

        for (int pos = 0; pos < h * w; pos++)
            acc += x[(size_t)pos * c + i];

        pooled[i] = (float)(acc / (h * w));
    }

    free(x);

    if (head_mode == 0) {
        normed = layer_norm(
            "head.0", pooled, 1, c
        );
        free(pooled);
        pooled = normed;
    }

    if (head_mode != 0 && head_mode != 1)
        die("Head order unknown");

    float *logits = linear(
        "head.1", pooled, 1, c, 100
    );
    free(pooled);

    save_bin(argv[3], logits, 100);

    int pred = 0;
    for (int i = 1; i < 100; i++)
        if (logits[i] > logits[pred]) pred = i;

    printf(
        "Full connected C forward finished; predicted_class=%d\n",
        pred
    );

    free(logits);
    return 0;
}

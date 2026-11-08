#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

#define NQ 62
#define NA 14
#define MAXPATH 2048

typedef struct {
    char name[192];
    int bits, calibrated;
    float sx, zp;
} Quant;

typedef struct {
    char name[192];
    int heads, kd, vd, nq, nk;
    float scale;
} Attn;

typedef struct {
    int resolution, stride, pool;
} Down;

static Quant quants[NQ];
static Attn attns[NA];
static Down downs[3];
static char root[1024], dumpdir[1024];

static void die(const char *why) {
    fprintf(stderr, "ERROR: %s\n", why);
    exit(2);
}

static void combine(
    char *out, size_t cap,
    const char *prefix, const char *suffix
) {
    int n = snprintf(out, cap, "%s%s", prefix, suffix);
    if (n < 0 || (size_t)n >= cap)
        die("Long module name");
}

static void *mem(size_t n, size_t sz) {
    if (!n || !sz || n > SIZE_MAX / sz)
        die("Invalid allocation");

    void *p = malloc(n * sz);
    if (!p) die("Out of memory");
    return p;
}

static void data_path(
    char *p, size_t cap,
    const char *name, const char *suffix
) {
    char clean[384];
    size_t j = 0;

    for (size_t i = 0; name[i]; i++) {
        if (j + 3 >= sizeof(clean))
            die("Long parameter name");

        if (name[i] == '.') {
            clean[j++] = '_';
            clean[j++] = '_';
        } else {
            clean[j++] = name[i];
        }
    }

    clean[j] = 0;

    if (snprintf(
        p, cap, "%s/%s_%s.bin",
        root, clean, suffix
    ) >= (int)cap)
        die("Long file path");
}

static void read_file(
    const char *p, void *dst,
    size_t n, size_t sz
) {
    FILE *f = fopen(p, "rb");
    if (!f) {
        perror(p);
        exit(2);
    }

    if (fread(dst, sz, n, f) != n ||
        fgetc(f) != EOF)
        die("Binary size mismatch");

    if (fclose(f))
        die("File close failed");
}

static void read_named(
    const char *name, const char *suffix,
    void *dst, size_t n, size_t sz
) {
    char p[MAXPATH];
    data_path(p, sizeof(p), name, suffix);
    read_file(p, dst, n, sz);
}

static void write_file(
    const char *p, const float *x, size_t n
) {
    FILE *f = fopen(p, "wb");
    if (!f) {
        perror(p);
        exit(2);
    }

    if (fwrite(x, sizeof(float), n, f) != n)
        die("Output write failed");

    if (fclose(f))
        die("Output close failed");
}

static void dump_tensor(
    const char *name, const float *x, size_t n
) {
    if (!dumpdir[0]) return;

    char clean[300], path[MAXPATH];
    size_t j = 0;

    for (size_t i = 0; name[i]; i++) {
        if (j + 2 >= sizeof(clean))
            die("Long dump name");

        clean[j++] = name[i] == '.' ? '_' : name[i];
    }

    clean[j] = 0;

    if (snprintf(
        path, sizeof(path),
        "%s/%s.bin", dumpdir, clean
    ) >= (int)sizeof(path))
        die("Long dump path");

    write_file(path, x, n);
}

static const Quant *quant(const char *name) {
    for (int i = 0; i < NQ; i++)
        if (!strcmp(name, quants[i].name))
            return &quants[i];

    fprintf(stderr, "Missing quantized layer: %s\n", name);
    exit(2);
}

static const Attn *attn_spec(const char *name) {
    for (int i = 0; i < NA; i++)
        if (!strcmp(name, attns[i].name))
            return &attns[i];

    fprintf(stderr, "Missing attention module: %s\n", name);
    exit(2);
}

static int32_t *quant_input(
    const float *x, size_t n,
    const Quant *q, float *scale
) {
    if (q->bits < 2 || q->bits > 16)
        die("Unsupported activation bits");

    float qmax = (float)((1u << q->bits) - 1u);
    float sx = q->sx, zp = q->zp;

    if (!q->calibrated) {
        float lo = x[0], hi = x[0];

        for (size_t i = 1; i < n; i++) {
            if (x[i] < lo) lo = x[i];
            if (x[i] > hi) hi = x[i];
        }

        sx = fmaxf((hi - lo) / qmax, 1e-8f);
        zp = fminf(
            qmax,
            fmaxf(0.0f, nearbyintf(-lo / sx))
        );
    }

    if (!isfinite(sx) || sx <= 0 || !isfinite(zp))
        die("Invalid activation quantization");

    int32_t *v = mem(n, sizeof(*v));

    for (size_t i = 0; i < n; i++) {
        float z = nearbyintf(x[i] / sx + zp);
        v[i] = (int32_t)(
            fminf(qmax, fmaxf(0.0f, z)) - zp
        );
    }

    *scale = sx;
    return v;
}

static float *linear(
    const char *name, const float *x,
    int rows, int ci, int co
) {
    const Quant *q = quant(name);
    float sx;

    int32_t *qi = quant_input(
        x, (size_t)rows * ci, q, &sx
    );

    int8_t *w = mem((size_t)ci * co, sizeof(*w));
    float *sw = mem(co, sizeof(*sw));
    float *bias = mem(co, sizeof(*bias));
    float *out = mem((size_t)rows * co, sizeof(*out));

    read_named(name, "weights", w,
               (size_t)ci * co, sizeof(*w));
    read_named(name, "scales", sw, co, sizeof(*sw));
    read_named(name, "bias", bias, co, sizeof(*bias));

    for (int r = 0; r < rows; r++)
    for (int o = 0; o < co; o++) {
        int64_t sum = 0;

        for (int k = 0; k < ci; k++)
            sum += (int64_t)qi[(size_t)r * ci + k]
                 * w[(size_t)o * ci + k];

        out[(size_t)r * co + o] =
            (float)sum * (sw[o] * sx) + bias[o];
    }

    dump_tensor(name, out, (size_t)rows * co);

    free(qi);
    free(w);
    free(sw);
    free(bias);
    return out;
}

static float *conv3(
    const char *name, const float *x,
    int h, int w, int ci, int co
) {
    const Quant *q = quant(name);
    float sx;
    size_t hw = (size_t)h * w;

    int32_t *qi = quant_input(
        x, (size_t)ci * hw, q, &sx
    );

    int8_t *wt = mem(
        (size_t)co * ci * 9, sizeof(*wt)
    );

    float *sw = mem(co, sizeof(*sw));
    float *bias = mem(co, sizeof(*bias));

    int oh = (h + 1) / 2;
    int ow = (w + 1) / 2;

    float *out = mem(
        (size_t)co * oh * ow, sizeof(*out)
    );

    read_named(name, "weights", wt,
               (size_t)co * ci * 9, sizeof(*wt));
    read_named(name, "scales", sw, co, sizeof(*sw));
    read_named(name, "bias", bias, co, sizeof(*bias));

    for (int o = 0; o < co; o++)
    for (int y = 0; y < oh; y++)
    for (int z = 0; z < ow; z++) {
        int64_t sum = 0;

        for (int ic = 0; ic < ci; ic++)
        for (int ky = 0; ky < 3; ky++)
        for (int kx = 0; kx < 3; kx++) {
            int yy = 2 * y + ky - 1;
            int xx = 2 * z + kx - 1;

            if (yy >= 0 && yy < h &&
                xx >= 0 && xx < w)
                sum +=
                    (int64_t)qi[
                        (size_t)ic * hw + yy * w + xx
                    ] * wt[
                        ((size_t)o * ci + ic) * 9
                        + ky * 3 + kx
                    ];
        }

        out[((size_t)o * oh + y) * ow + z] =
            (float)sum * (sw[o] * sx) + bias[o];
    }

    dump_tensor(name, out, (size_t)co * oh * ow);

    free(qi);
    free(wt);
    free(sw);
    free(bias);
    return out;
}

static void batchnorm(
    float *x, int rows, int c,
    const char *name, int nchw
) {
    float *gamma = mem(c, sizeof(float));
    float *beta = mem(c, sizeof(float));
    float *mean = mem(c, sizeof(float));
    float *var = mem(c, sizeof(float));

    read_named(name, "gamma", gamma, c, sizeof(float));
    read_named(name, "beta", beta, c, sizeof(float));
    read_named(name, "mean", mean, c, sizeof(float));
    read_named(name, "variance", var, c, sizeof(float));

    for (int k = 0; k < c; k++) {
        float inv = 1.f / sqrtf(var[k] + 1e-5f);

        for (int i = 0; i < rows; i++) {
            size_t pos = nchw
                ? (size_t)k * rows + i
                : (size_t)i * c + k;

            x[pos] =
                (x[pos] - mean[k]) * inv
                * gamma[k] + beta[k];
        }
    }

    free(gamma);
    free(beta);
    free(mean);
    free(var);
}

static void hardswish(float *x, size_t n) {
    for (size_t i = 0; i < n; i++) {
        float v = x[i];
        float z = fminf(6.f, fmaxf(0.f, v + 3.f));
        x[i] = v * z / 6.f;
    }
}

static float *linear_norm(
    const char *name, const float *x,
    int n, int ci, int co
) {
    char s[256];

    combine(s, sizeof(s), name, ".linear");
    float *y = linear(s, x, n, ci, co);

    combine(s, sizeof(s), name, ".bn");
    batchnorm(y, n, co, s, 0);

    return y;
}

static float *mlp(
    const char *prefix, const float *x,
    int n, int c, int hidden
) {
    char s[256];

    combine(s, sizeof(s), prefix, ".ln1");
    float *wide = linear_norm(s, x, n, c, hidden);

    hardswish(wide, (size_t)n * hidden);

    combine(s, sizeof(s), prefix, ".ln2");
    float *out = linear_norm(s, wide, n, hidden, c);

    free(wide);
    return out;
}

static float *attention_mix(
    const float *q, const float *kv,
    const float *bias, int nq, int nk,
    int heads, int kd, int vd,
    float scale, int same
) {
    int qstride = heads * (same ? 2 * kd + vd : kd);
    int kvstride = heads * (
        same ? 2 * kd + vd : kd + vd
    );

    float *out = mem(
        (size_t)nq * heads * vd, sizeof(float)
    );

    float *scores = mem(nk, sizeof(float));

    for (int h = 0; h < heads; h++)
    for (int i = 0; i < nq; i++) {
        float maxv = -INFINITY;

        for (int j = 0; j < nk; j++) {
            float dot = 0;

            const float *qq =
                q + (size_t)i * qstride
                + h * (same ? 2 * kd + vd : kd);

            const float *kk =
                kv + (size_t)j * kvstride
                + h * (same ? 2 * kd + vd : kd + vd)
                + (same ? kd : 0);

            for (int d = 0; d < kd; d++)
                dot += qq[d] * kk[d];

            float z =
                dot * scale
                + bias[((size_t)h * nq + i) * nk + j];

            scores[j] = z;
            if (z > maxv) maxv = z;
        }

        float den = 0;

        for (int j = 0; j < nk; j++) {
            scores[j] = expf(scores[j] - maxv);
            den += scores[j];
        }

        for (int d = 0; d < vd; d++) {
            float sum = 0;

            for (int j = 0; j < nk; j++) {
                const float *vv =
                    kv + (size_t)j * kvstride
                    + h * (same ? 2 * kd + vd : kd + vd)
                    + (same ? 2 * kd : kd);

                sum += (scores[j] / den) * vv[d];
            }

            out[((size_t)i * heads + h) * vd + d] = sum;
        }
    }

    free(scores);
    return out;
}

static float *attention(
    const char *prefix, const float *x,
    int n, int c
) {
    const Attn *a = attn_spec(prefix);

    if (a->nq != n || a->nk != n)
        die("Attention shape mismatch");

    char s[256];

    combine(s, sizeof(s), prefix, ".qkv");

    int width = a->heads * (2 * a->kd + a->vd);
    float *qkv = linear_norm(s, x, n, c, width);

    float *bias = mem(
        (size_t)a->heads * n * n, sizeof(float)
    );

    read_named(
        prefix, "attention_bias", bias,
        (size_t)a->heads * n * n, sizeof(float)
    );

    float *mix = attention_mix(
        qkv, qkv, bias,
        n, n, a->heads,
        a->kd, a->vd, a->scale, 1
    );

    free(qkv);
    free(bias);

    hardswish(mix, (size_t)n * a->heads * a->vd);

    combine(s, sizeof(s), prefix, ".proj.ln");

    float *proj = linear_norm(
        s, mix, n, a->heads * a->vd, c
    );

    free(mix);
    return proj;
}

static float *selected(
    const float *x, int res, int c,
    int stride, int pool
) {
    int newres = (res + stride - 1) / stride;

    float *out = mem(
        (size_t)newres * newres * c, sizeof(float)
    );

    for (int y = 0; y < newres; y++)
    for (int z = 0; z < newres; z++)
    for (int k = 0; k < c; k++) {
        size_t target =
            ((size_t)y * newres + z) * c + k;

        if (!pool) {
            out[target] = x[
                ((size_t)(y * stride) * res
                    + z * stride) * c + k
            ];
        } else {
            double sum = 0;
            int count = 0;

            for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int yy = y * stride + dy;
                int xx = z * stride + dx;

                if (yy >= 0 && yy < res &&
                    xx >= 0 && xx < res) {
                    sum += x[
                        ((size_t)yy * res + xx) * c + k
                    ];
                    count++;
                }
            }

            out[target] = (float)(sum / count);
        }
    }

    return out;
}

static float *attention_downsample(
    const char *prefix, const float *x,
    int inres, int ci, int co, int stage
) {
    const Attn *a = attn_spec(prefix);

    int ni = inres * inres;
    int no = a->nq;

    if (a->nk != ni || stage < 1 || stage > 2)
        die("Downsample attention shape mismatch");

    Down d = downs[stage];
    int newres = (inres + d.stride - 1) / d.stride;

    if (d.resolution != inres || no != newres * newres)
        die("Downsample resolution mismatch");

    char s[256];

    combine(s, sizeof(s), prefix, ".kv");

    float *kv = linear_norm(
        s, x, ni, ci,
        a->heads * (a->kd + a->vd)
    );

    float *sub = selected(
        x, inres, ci, d.stride, d.pool
    );

    combine(s, sizeof(s), prefix, ".q.ln");

    float *q = linear_norm(
        s, sub, no, ci, a->heads * a->kd
    );

    free(sub);

    float *bias = mem(
        (size_t)a->heads * no * ni, sizeof(float)
    );

    read_named(
        prefix, "attention_bias", bias,
        (size_t)a->heads * no * ni, sizeof(float)
    );

    float *mix = attention_mix(
        q, kv, bias,
        no, ni, a->heads,
        a->kd, a->vd, a->scale, 0
    );

    free(q);
    free(kv);
    free(bias);

    hardswish(mix, (size_t)no * a->heads * a->vd);

    combine(s, sizeof(s), prefix, ".proj.ln");

    float *out = linear_norm(
        s, mix, no, a->heads * a->vd, co
    );

    free(mix);
    return out;
}

static void add(
    float *a, const float *b, size_t n
) {
    for (size_t i = 0; i < n; i++)
        a[i] += b[i];
}

static void load_config(const char *p) {
    FILE *f = fopen(p, "r");
    if (!f) {
        perror(p);
        exit(2);
    }

    int nq, na, nd;

    if (fscanf(f, "%d %d %d", &nq, &na, &nd) != 3 ||
        nq != NQ || na != NA || nd != 2)
        die("Invalid config header");

    for (int i = 0; i < NQ; i++) {
        Quant *q = &quants[i];

        if (fscanf(
            f, "%191s %d %d %f %f",
            q->name, &q->bits, &q->calibrated,
            &q->sx, &q->zp
        ) != 5)
            die("Invalid quantization config");
    }

    for (int i = 0; i < NA; i++) {
        Attn *a = &attns[i];

        if (fscanf(
            f, "%191s %d %d %d %f %d %d",
            a->name, &a->heads,
            &a->kd, &a->vd, &a->scale,
            &a->nq, &a->nk
        ) != 7)
            die("Invalid attention config");
    }

    for (int i = 1; i < 3; i++) {
        int stage;

        if (fscanf(
            f, "%d %d %d %d",
            &stage, &downs[i].resolution,
            &downs[i].stride, &downs[i].pool
        ) != 4 || stage != i)
            die("Invalid downsample config");
    }

    if (fclose(f))
        die("Config close failed");
}

int main(int argc, char **argv) {
    if (argc < 4 || argc > 5) {
        fprintf(
            stderr,
            "Usage: %s RUNTIME CONFIG OUTPUT [DUMP_DIR]\n",
            argv[0]
        );
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

    load_config(argv[2]);

    char p[MAXPATH];
    snprintf(p, sizeof(p), "%s/input.bin", root);

    float *x = mem(3u * 224 * 224, sizeof(float));
    read_file(p, x, 3u * 224 * 224, sizeof(float));

    int ci = 3, h = 224;
    const int stem[] = {48, 96, 192, 384};

    for (int j = 0; j < 4; j++) {
        char name[200], bn[200];

        snprintf(
            name, sizeof(name),
            "stem.conv%d.linear", j + 1
        );

        float *out = conv3(
            name, x, h, h, ci, stem[j]
        );

        free(x);
        x = out;
        h /= 2;
        ci = stem[j];

        snprintf(
            bn, sizeof(bn),
            "stem.conv%d.bn", j + 1
        );

        batchnorm(x, h * h, ci, bn, 1);

        if (j < 3)
            hardswish(x, (size_t)ci * h * h);

        fprintf(
            stderr,
            "STEM %d complete (%dx%dx%d)\n",
            j + 1, h, h, ci
        );
    }

    /* NCHW -> token-major */
    int tokens = h * h;

    float *tok = mem(
        (size_t)tokens * ci, sizeof(float)
    );

    for (int i = 0; i < tokens; i++)
    for (int k = 0; k < ci; k++)
        tok[(size_t)i * ci + k] =
            x[(size_t)k * tokens + i];

    free(x);
    x = tok;

    const int stage_c[] = {384, 512, 768};
    const int stage_res[] = {14, 7, 4};

    for (int stage = 0; stage < 3; stage++) {
        if (stage) {
            char name[256], pre[256];

            snprintf(
                pre, sizeof(pre),
                "stages.%d.downsample", stage
            );

            combine(
                name, sizeof(name),
                pre, ".attn_downsample"
            );

            float *out = attention_downsample(
                name, x, h, ci, stage_c[stage], stage
            );

            free(x);
            x = out;
            h = stage_res[stage];
            ci = stage_c[stage];
            tokens = h * h;

            combine(
                name, sizeof(name),
                pre, ".mlp"
            );

            float *m = mlp(
                name, x, tokens, ci, ci * 2
            );

            add(x, m, (size_t)tokens * ci);
            free(m);

            fprintf(
                stderr,
                "DOWN %d complete (%d tokens %d channels)\n",
                stage, tokens, ci
            );
        }

        for (int b = 0; b < 4; b++) {
            char name[256], part[256];

            snprintf(
                name, sizeof(name),
                "stages.%d.blocks.%d", stage, b
            );

            combine(
                part, sizeof(part),
                name, ".attn"
            );

            float *a = attention(
                part, x, tokens, ci
            );

            add(x, a, (size_t)tokens * ci);
            free(a);

            combine(
                part, sizeof(part),
                name, ".mlp"
            );

            float *m = mlp(
                part, x, tokens, ci, ci * 2
            );

            add(x, m, (size_t)tokens * ci);
            free(m);

            dump_tensor(
                name, x, (size_t)tokens * ci
            );

            fprintf(
                stderr,
                "BLOCK %d.%d complete\n", stage, b
            );
        }
    }

    float *pool = mem(ci, sizeof(float));

    for (int k = 0; k < ci; k++) {
        double sum = 0;

        for (int i = 0; i < tokens; i++)
            sum += x[(size_t)i * ci + k];

        pool[k] = (float)(sum / tokens);
    }

    free(x);

    float *weight = mem(
        (size_t)10 * ci, sizeof(float)
    );
    float *bias = mem(10, sizeof(float));

    snprintf(
        p, sizeof(p),
        "%s/head_weight.bin", root
    );
    read_file(
        p, weight, (size_t)10 * ci, sizeof(float)
    );

    snprintf(
        p, sizeof(p),
        "%s/head_bias.bin", root
    );
    read_file(p, bias, 10, sizeof(float));

    float out[10];
    int pred = 0;

    for (int o = 0; o < 10; o++) {
        float sum = bias[o];

        for (int k = 0; k < ci; k++)
            sum += pool[k] *
                weight[(size_t)o * ci + k];

        out[o] = sum;

        if (o == 0 || out[o] > out[pred])
            pred = o;
    }

    write_file(argv[3], out, 10);

    printf(
        "LeViT384 full connected C inference completed; "
        "predicted_class=%d\n",
        pred
    );

    free(pool);
    free(weight);
    free(bias);
    return 0;
}

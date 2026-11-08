#!/usr/bin/env python3
"""Generate verified, self-contained first-layer convolution tiles."""

from pathlib import Path
import hashlib
import json
import struct

root = Path.cwd()
results = root / "results/model_execution"

models = {
    "vgg11": "vgg11_real_first_conv_riscv_qemu_20261028",
    "swint": "swint_real_first_conv_riscv_qemu_20261028",
    "levit384": "levit384_real_first_conv_riscv_qemu_20261028",
}

OC_TILE = 8
H_TILE = 4
W_TILE = 4
INPUT_WINDOW = 32

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def load_tensor(folder, manifest, filename, shape):
    path = folder / filename
    assert sha(path) == manifest["tensor_sha256"][filename]

    raw = path.read_bytes()
    assert len(raw) == 4 * __import__("math").prod(shape)

    return [
        value
        for (value,) in struct.iter_unpack("<i", raw)
    ]

def emit_array(name, values):
    rows = [
        "    " + ", ".join(str(v) for v in values[i:i+10])
        for i in range(0, len(values), 10)
    ]
    return (
        f"static const int32_t {name}[{len(values)}] = {{\n"
        + ",\n".join(rows)
        + "\n};\n"
    )

def hash_outputs(values):
    h = 1469598103934665603
    for value in values:
        h ^= value & 0xffffffff
        h = (h * 1099511628211) & 0xffffffffffffffff
    return f"{h:016x}"

def write_exact(path, data):
    content = data.encode() if isinstance(data, str) else data
    if path.exists():
        assert path.read_bytes() == content, (
            f"Existing artifact differs: {path}"
        )
    else:
        path.write_bytes(content)

for model, dirname in models.items():
    original = results / dirname
    manifest = json.loads(
        (original / "experiment_manifest.json").read_text()
    )

    assert manifest["model"] == model
    assert manifest["numerical_mismatches"] == 0
    assert manifest["native_result"] == manifest["qemu_result"]

    n, ci, height, width = manifest["input_shape"]
    oc, wci, kh, kw = manifest["weight_shape"]
    rn, roc, oh, ow = manifest["reference_shape"]

    assert n == rn == 1
    assert ci == wci == 3
    assert roc == oc
    assert oc >= OC_TILE
    assert height >= INPUT_WINDOW
    assert width >= INPUT_WINDOW

    sh, sw = manifest["stride"]
    ph, pw = manifest["padding"]
    dh, dw = manifest["dilation"]

    x = load_tensor(
        original, manifest,
        "input.i32.bin",
        manifest["input_shape"],
    )
    w = load_tensor(
        original, manifest,
        "weights.i32.bin",
        manifest["weight_shape"],
    )
    reference = load_tensor(
        original, manifest,
        "reference.i32.bin",
        manifest["reference_shape"],
    )

    # Preserve the original values in the top-left 32x32 window.
    # All required receptive fields for this tile must fit inside.
    input_tile = [
        x[(c * height + iy) * width + ix]
        for c in range(ci)
        for iy in range(INPUT_WINDOW)
        for ix in range(INPUT_WINDOW)
    ]

    weight_tile = w[:OC_TILE * ci * kh * kw]
    expected = []

    for o in range(OC_TILE):
        for y in range(H_TILE):
            for z in range(W_TILE):
                acc = 0

                for c in range(ci):
                    for ky in range(kh):
                        for kx in range(kw):
                            iy = y * sh + ky * dh - ph
                            ix = z * sw + kx * dw - pw

                            if (
                                iy < 0 or iy >= height
                                or ix < 0 or ix >= width
                            ):
                                continue

                            assert iy < INPUT_WINDOW
                            assert ix < INPUT_WINDOW

                            a = x[(c * height + iy) * width + ix]
                            b = w[
                                ((o * ci + c) * kh + ky) * kw + kx
                            ]
                            acc += a * b

                target = reference[o * oh * ow + y * ow + z]

                assert acc == target, (
                    f"{model} mismatch at {(o, y, z)}: "
                    f"{acc} != {target}"
                )

                expected.append(target)

    assert len(expected) == 128

    output_hash = hash_outputs(expected)

    header = f"""
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>

#define CI {ci}
#define IH {INPUT_WINDOW}
#define IW {INPUT_WINDOW}
#define OC {OC_TILE}
#define KH {kh}
#define KW {kw}
#define TH {H_TILE}
#define TW {W_TILE}
#define SH {sh}
#define SW {sw}
#define PH {ph}
#define PW {pw}
#define DH {dh}
#define DW {dw}

"""

    source = header
    source += emit_array("input_data", input_tile)
    source += emit_array("weight_data", weight_tile)
    source += emit_array("expected", expected)

    source += r"""
static int64_t output_data[OC * TH * TW];

static inline uint64_t read_cycles(void) {
#if defined(__riscv)
    uint64_t value;
    __asm__ volatile ("rdcycle %0" : "=r"(value));
    return value;
#else
    return 0;
#endif
}

__attribute__((noinline))
static void convolution(void) {
    for (int o = 0; o < OC; o++) {
        for (int y = 0; y < TH; y++) {
            for (int z = 0; z < TW; z++) {
                int64_t acc = 0;

                for (int c = 0; c < CI; c++) {
                    for (int ky = 0; ky < KH; ky++) {
                        for (int kx = 0; kx < KW; kx++) {
                            int iy = y * SH + ky * DH - PH;
                            int ix = z * SW + kx * DW - PW;

                            if (iy < 0 || iy >= IH ||
                                ix < 0 || ix >= IW) {
                                continue;
                            }

                            int32_t a = input_data[
                                (c * IH + iy) * IW + ix
                            ];

                            int32_t b = weight_data[
                                ((o * CI + c) * KH + ky) * KW + kx
                            ];

                            acc += (int64_t)a * b;
                        }
                    }
                }

                output_data[(o * TH + y) * TW + z] = acc;
            }
        }
    }
}

int main(void) {
    __asm__ volatile ("" ::: "memory");
    uint64_t start = read_cycles();

    convolution();

    __asm__ volatile ("" ::: "memory");
    uint64_t end = read_cycles();

    uint64_t hash = UINT64_C(1469598103934665603);

    for (int i = 0; i < OC * TH * TW; i++) {
        int64_t actual = output_data[i];

        if (actual != expected[i]) {
            printf(
                "FAIL MODEL=MODEL_TAG INDEX=%d "
                "ACTUAL=%" PRId64 " EXPECTED=%" PRId32 "\n",
                i, actual, expected[i]
            );
            return 1;
        }

        hash ^= (uint32_t)actual;
        hash *= UINT64_C(1099511628211);
    }

    printf(
        "PASS MODEL=MODEL_TAG ELEMENTS=128 "
        "HASH=%016" PRIx64 " "
        "KERNEL_CYCLES=%" PRIu64 "\n",
        hash, end - start
    );

    return 0;
}
"""

    source = source.replace("MODEL_TAG", model)

    out = results / f"{model}_conv1_rocket_tile_20261028"
    out.mkdir(parents=True, exist_ok=True)

    c_path = out / "conv_tile.c"
    write_exact(c_path, source)

    record = {
        "model": model,
        "original_layer": manifest["layer"],
        "original_experiment": dirname,
        "original_input_shape": manifest["input_shape"],
        "original_weight_shape": manifest["weight_shape"],
        "original_reference_shape": manifest["reference_shape"],
        "embedded_input_window": [1, ci, 32, 32],
        "tile_output_shape": [1, 8, 4, 4],
        "tile_origin": [0, 0],
        "stride": [sh, sw],
        "padding": [ph, pw],
        "dilation": [dh, dw],
        "expected_hash": output_hash,
        "elements_verified_by_python": 128,
        "numerical_mismatches": 0,
        "reference_semantics": manifest["reference_semantics"],
        "original_tensor_sha256": manifest["tensor_sha256"],
        "source_sha256": sha(c_path),
        "full_model_inference": False,
        "physical_fpga_execution": False,
    }

    write_exact(
        out / "tile_manifest.json",
        json.dumps(record, indent=2) + "\n",
    )

    write_exact(
        out / "README.md",
        f"# {model} checkpoint-derived RTL tile\n\n"
        f"Original first layer: `{manifest['layer']}`.\n\n"
        "Uses original integer tensors and a top-left 32x32 "
        "input window sufficient for the selected 128 outputs.\n\n"
        "The 8x4x4 tile matched the original PyTorch-derived "
        "integer reference exactly.\n\n"
        "This is a selected first-layer computation, not "
        "whole-model inference or physical FPGA execution.\n",
    )

    print(
        f"PASS {model}: 128/128 Python reference matches, "
        f"hash={output_hash}"
    )

print("PASS: All three RTL C sources generated")

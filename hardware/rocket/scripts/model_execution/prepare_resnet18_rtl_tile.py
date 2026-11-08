#!/usr/bin/env python3
"""Prepare a real checkpoint-derived ResNet18 convolution tile."""

from pathlib import Path
import hashlib
import json
import re
import subprocess

import numpy as np


repo = Path.cwd()

original = (
    repo / "results/model_execution/"
    "resnet18_real_conv1_riscv_qemu_20261028"
)

output = (
    repo / "results/model_execution/"
    "resnet18_conv1_rocket_tile_20261028"
)

assert original.is_dir()
assert not output.exists()

manifest = json.loads(
    (original / "experiment_manifest.json").read_text()
)

assert manifest["output_elements"] == 16384
assert manifest["native_result"] == manifest["qemu_result"]

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def load(name, shape):
    path = original / name
    assert path.is_file()
    assert sha256(path) == manifest["data_sha256"][name]

    array = np.frombuffer(
        path.read_bytes(),
        dtype="<i4",
    )

    assert array.size == int(np.prod(shape))
    return array.reshape(shape)

x = load("input.i32.bin", (1, 3, 32, 32))
w = load("weights.i32.bin", (64, 3, 7, 7))
reference = load("reference.i32.bin", (1, 64, 16, 16))

# Preserve actual model data. Only reduce the number
# of output channels and spatial positions computed.
channels = 8
tile_h = 4
tile_w = 4

weights = np.ascontiguousarray(w[:channels])
expected = np.ascontiguousarray(
    reference[0, :channels, :tile_h, :tile_w]
)

print("===== VALIDATE ORIGINAL RESNET18 TILE =====")

for oc in range(channels):
    for oy in range(tile_h):
        for ox in range(tile_w):
            acc = 0

            for ic in range(3):
                for ky in range(7):
                    for kx in range(7):
                        iy = oy * 2 + ky - 3
                        ix = ox * 2 + kx - 3

                        if 0 <= iy < 32 and 0 <= ix < 32:
                            acc += (
                                int(x[0, ic, iy, ix])
                                * int(weights[oc, ic, ky, kx])
                            )

            assert acc == int(expected[oc, oy, ox]), (
                f"Mismatch at {(oc, oy, ox)}: "
                f"calculated={acc}, "
                f"reference={expected[oc, oy, ox]}"
            )

print("PASS: 128/128 original reference elements matched")

def fnv_hash(values):
    h = 1469598103934665603

    for value in values:
        h ^= int(value) & 0xffffffff
        h = (h * 1099511628211) & 0xffffffffffffffff

    return h

expected_hash = fnv_hash(expected.flat)

def c_array(name, array):
    values = [
        str(int(value))
        for value in array.flat
    ]

    lines = [
        "    " + ", ".join(values[i:i+10])
        for i in range(0, len(values), 10)
    ]

    return (
        f"static const int32_t {name}[{len(values)}] = {{\n"
        + ",\n".join(lines)
        + "\n};\n"
    )

source = r'''
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>

#define CI 3
#define H 32
#define W 32
#define OC 8
#define KH 7
#define KW 7
#define TH 4
#define TW 4
#define STRIDE 2
#define PAD 3

'''

source += c_array("input_data", x)
source += c_array("weight_data", weights)
source += c_array("expected", expected)

source += r'''
static int32_t output_data[OC * TH * TW];

static inline uint64_t cycle_counter(void) {
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
    for (int oc = 0; oc < OC; oc++) {
        for (int oy = 0; oy < TH; oy++) {
            for (int ox = 0; ox < TW; ox++) {
                int64_t acc = 0;

                for (int ic = 0; ic < CI; ic++) {
                    for (int ky = 0; ky < KH; ky++) {
                        for (int kx = 0; kx < KW; kx++) {
                            int iy = oy * STRIDE + ky - PAD;
                            int ix = ox * STRIDE + kx - PAD;

                            if (iy < 0 || iy >= H ||
                                ix < 0 || ix >= W) {
                                continue;
                            }

                            int32_t a = input_data[
                                (ic * H + iy) * W + ix
                            ];

                            int32_t b = weight_data[
                                ((oc * CI + ic) * KH + ky) * KW + kx
                            ];

                            acc += (int64_t)a * b;
                        }
                    }
                }

                int index = (oc * TH + oy) * TW + ox;
                output_data[index] = (int32_t)acc;
            }
        }
    }
}

int main(void) {
    uint64_t start = cycle_counter();

    convolution();

    uint64_t end = cycle_counter();
    uint64_t cycles = end - start;

    uint64_t hash = UINT64_C(1469598103934665603);

    for (int i = 0; i < OC * TH * TW; i++) {
        int32_t actual = output_data[i];

        if (actual != expected[i]) {
            printf(
                "FAIL RESNET18 CONV1 TILE "
                "index=%d actual=%" PRId32
                " expected=%" PRId32 "\n",
                i, actual, expected[i]
            );
            return 1;
        }

        hash ^= (uint32_t)actual;
        hash *= UINT64_C(1099511628211);
    }

    printf(
        "PASS RESNET18 CONV1 TILE "
        "ELEMENTS=%d HASH=%016" PRIx64
        " KERNEL_CYCLES=%" PRIu64 "\n",
        OC * TH * TW,
        hash,
        cycles
    );

    return 0;
}
'''

output.mkdir(parents=True)

c_path = output / "conv1_tile.c"
c_path.write_text(source)

native = output / "native_conv1_tile"

subprocess.run(
    [
        "gcc",
        "-O2",
        "-std=c11",
        "-Wall",
        "-Wextra",
        "-Werror",
        str(c_path),
        "-o",
        str(native),
    ],
    check=True,
)

result = subprocess.run(
    [str(native)],
    capture_output=True,
    text=True,
    check=True,
)

print("===== NATIVE TEST RESULT =====")
print(result.stdout.strip())

pattern = (
    r"PASS RESNET18 CONV1 TILE "
    r"ELEMENTS=128 HASH=([0-9a-f]{16}) "
    r"KERNEL_CYCLES=(\d+)"
)

match = re.fullmatch(pattern, result.stdout.strip())

assert match, "Unexpected native output"
assert int(match.group(1), 16) == expected_hash
assert result.returncode == 0

(output / "native.log").write_text(
    result.stdout
)

record = {
    "experiment": "resnet18_checkpoint_derived_rocket_tile",
    "date": "2026-10-28",
    "model": "ResNet18 GTSRB",
    "layer": "conv1",
    "input_shape": list(x.shape),
    "selected_weight_shape": list(weights.shape),
    "tile_output_shape": list(expected.shape),
    "tile_origin_yx": [0, 0],
    "stride": [2, 2],
    "padding": [3, 3],
    "compared_elements": 128,
    "native_mismatches": 0,
    "expected_hash": f"{expected_hash:016x}",
    "source_sha256": sha256(c_path),
    "generator_sha256": sha256(Path(__file__)),
    "original_tensor_sha256": manifest["data_sha256"],
    "checkpoint_derived_weights": True,
    "input_is_synthetic": True,
    "native_execution": True,
    "riscv_qemu_execution": False,
    "rocket_rtl_execution": False,
    "full_model_inference": False,
    "physical_fpga_execution": False,
}

(output / "experiment_manifest.json").write_text(
    json.dumps(record, indent=2) + "\n"
)

(output / "README.md").write_text(
    "# ResNet18 real convolution tile for Rocket RTL\n\n"
    "Uses the original captured checkpoint-derived "
    "quantized input and weights for ResNet18 `conv1`.\n\n"
    "Validates the first eight output channels and a "
    "4x4 spatial tile (128 accumulator outputs).\n\n"
    "All 128 values matched the original PyTorch "
    "reference on native Linux.\n\n"
    "The original input was synthetic. "
    "Rocket RTL execution has not yet been performed.\n"
)

print("PASS: Native model-derived tile verified")
print("Expected hash:", f"{expected_hash:016x}")
print("C source:", c_path.relative_to(repo))
print("Result:", output.relative_to(repo))

#!/usr/bin/env python3
"""Validate a checkpoint-derived integer convolution on RISC-V QEMU."""

import argparse
import contextlib
import hashlib
import inspect
import io
import json
import os
import runpy
import shutil
import subprocess
import sys
import tempfile

from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F


parser = argparse.ArgumentParser()
parser.add_argument(
    "--model",
    required=True,
    choices=["vgg11", "swint", "levit384"],
)
args = parser.parse_args()

model = args.model
repo = Path.cwd()

workspace = (
    Path.home()
    / "health-slm-model-validation/mahdi-four-models"
)

folder = workspace / model
script = folder / "npu_inference_integer.py"

checkpoints = {
    "vgg11": "VGG11BN_CIFAR10_uniform8.pth",
    "swint": "SwinT_CIFAR100_uniform8.pth",
    "levit384": "LeViT384_CIFAR10_uniform8.pth",
}

checkpoint = folder / checkpoints[model]

profile_path = (
    repo / "benchmarks/quantized_networks/profiles"
    / f"{model}.json"
)

profile = json.loads(profile_path.read_text())

first_layer = profile["layers"][0]
target_shape = tuple(first_layer["shape"])
layer_name = first_layer["name"]

assert len(target_shape) == 4
assert script.is_file()
assert checkpoint.is_file()

output = (
    repo / "results/model_execution"
    / f"{model}_real_first_conv_riscv_qemu_20261028"
)

assert not output.exists(), (
    f"STOP: Output already exists: {output}"
)

torch.set_num_threads(2)
torch.manual_seed(12345)
np.random.seed(12345)

original_conv2d = F.conv2d
captured = {}


def pair(value):
    if isinstance(value, int):
        return (value, value)
    if isinstance(value, (tuple, list)) and len(value) == 2:
        return tuple(int(x) for x in value)
    raise AssertionError(f"Unsupported convolution argument: {value}")


def parameter(a, kw, index, name, default):
    if name in kw:
        return kw[name]
    if len(a) > index:
        return a[index]
    return default


def integer_array(tensor, name):
    t = tensor.detach().cpu().contiguous()

    assert torch.isfinite(t).all(), name
    assert torch.equal(t, t.round()), (
        f"{name} is not integer-valued"
    )

    values = t.to(torch.int64).numpy()

    assert values.min() >= -(2**31), name
    assert values.max() < 2**31, name

    return np.ascontiguousarray(
        values.astype("<i4")
    )


def inside_integer_conv():
    frame = inspect.currentframe().f_back

    while frame is not None:
        instance = frame.f_locals.get("self")

        if (
            instance is not None
            and type(instance).__name__ == "IntegerConv2d"
            and frame.f_code.co_name == "forward"
        ):
            return True

        frame = frame.f_back

    return False


def intercept(x, weight, *a, **kw):
    if (
        captured
        or tuple(weight.shape) != target_shape
        or not inside_integer_conv()
    ):
        return original_conv2d(x, weight, *a, **kw)

    result = original_conv2d(x, weight, *a, **kw)

    bias = parameter(a, kw, 0, "bias", None)

    assert bias is None, "Expected bias-free integer accumulator"
    assert x.ndim == 4
    assert weight.ndim == 4
    assert x.shape[0] == 1

    x_i32 = integer_array(x, "input")
    w_i32 = integer_array(weight, "weights")
    ref_i32 = integer_array(result, "reference")

    stride = pair(parameter(a, kw, 1, "stride", 1))
    padding = pair(parameter(a, kw, 2, "padding", 0))
    dilation = pair(parameter(a, kw, 3, "dilation", 1))
    groups = int(parameter(a, kw, 4, "groups", 1))

    assert groups == 1, "Grouped convolution unsupported"

    bound = (
        x.shape[1]
        * weight.shape[2]
        * weight.shape[3]
        * int(np.abs(x_i32.astype(np.int64)).max())
        * int(np.abs(w_i32.astype(np.int64)).max())
    )

    assert bound < 2**31, "Potential accumulator overflow"

    captured.update({
        "input": x_i32,
        "weights": w_i32,
        "reference": ref_i32,
        "stride": stride,
        "padding": padding,
        "dilation": dilation,
        "groups": groups,
    })

    return result


print(f"===== RUN ORIGINAL {model.upper()} MODEL =====", flush=True)

stdout = io.StringIO()
old_cwd = Path.cwd()
old_argv = sys.argv[:]
old_path = sys.path[:]

try:
    os.chdir(folder)
    sys.path.insert(0, str(folder))
    sys.argv = [str(script), "--mode", "mqf"]

    F.conv2d = intercept

    with contextlib.redirect_stdout(stdout):
        runpy.run_path(
            str(script),
            run_name="__main__",
        )

finally:
    F.conv2d = original_conv2d
    sys.argv = old_argv
    sys.path[:] = old_path
    os.chdir(old_cwd)

assert captured, (
    f"No matching integer convolution captured: {target_shape}"
)

# runpy.run_path returned normally and the integer convolution
# was captured. Model packages use different prediction labels,
# so do not require one particular display string.
model_stdout = stdout.getvalue()

assert model_stdout.strip(), (
    "Original model script produced no output"
)

print("PASS: Original model script returned normally")
print("===== ORIGINAL MODEL OUTPUT (LAST 18 LINES) =====")
print("\\n".join(model_stdout.splitlines()[-18:]))

x = captured["input"]
w = captured["weights"]
ref = captured["reference"]

N, CI, H, W = x.shape
OC, WCI, KH, KW = w.shape
RN, ROC, OH, OW = ref.shape

assert N == RN == 1
assert CI == WCI
assert ROC == OC

SH, SW = captured["stride"]
PH, PW = captured["padding"]
DH, DW = captured["dilation"]

assert SH > 0 and SW > 0
assert DH > 0 and DW > 0

assert OH == (H + 2*PH - DH*(KH-1) - 1)//SH + 1
assert OW == (W + 2*PW - DW*(KW-1) - 1)//SW + 1

print("PASS: Original model inference completed")
print("Captured layer:", layer_name)
print("Input:", list(x.shape))
print("Weights:", list(w.shape))
print("Reference:", list(ref.shape))
print("Stride:", captured["stride"])
print("Padding:", captured["padding"])
print("Reference elements:", ref.size)

defines = {
    "CI": CI, "H": H, "W": W,
    "OC": OC, "KH": KH, "KW": KW,
    "OH": OH, "OW": OW,
    "SH": SH, "SW": SW,
    "PH": PH, "PW": PW,
    "DH": DH, "DW": DW,
}

header = "\n".join(
    f"#define {key} {value}"
    for key, value in defines.items()
)

source = (
    "#include <stdint.h>\n"
    "#include <inttypes.h>\n"
    "#include <stdio.h>\n\n"
    + header + "\n\n"
    + r"""
static int32_t input[CI*H*W];
static int32_t weights[OC*CI*KH*KW];
static int32_t reference[OC*OH*OW];

static int read_array(
    const char *filename, int32_t *buffer, size_t count
) {
    FILE *file = fopen(filename, "rb");
    if (!file) {
        perror(filename);
        return 0;
    }

    int ok = fread(buffer, sizeof(int32_t), count, file) == count;
    if (fgetc(file) != EOF) ok = 0;

    fclose(file);
    return ok;
}

int main(int argc, char **argv) {
    if (argc != 4) return 2;

    if (!read_array(argv[1], input, CI*H*W) ||
        !read_array(argv[2], weights, OC*CI*KH*KW) ||
        !read_array(argv[3], reference, OC*OH*OW)) {
        return 2;
    }

    uint64_t hash = UINT64_C(1469598103934665603);
    size_t count = 0;

    for (int o = 0; o < OC; o++)
        for (int y = 0; y < OH; y++)
            for (int z = 0; z < OW; z++) {
                int64_t acc = 0;

                for (int c = 0; c < CI; c++)
                    for (int ky = 0; ky < KH; ky++)
                        for (int kx = 0; kx < KW; kx++) {
                            int iy = y*SH + ky*DH - PH;
                            int ix = z*SW + kx*DW - PW;

                            if (iy < 0 || iy >= H ||
                                ix < 0 || ix >= W)
                                continue;

                            int32_t a = input[(c*H+iy)*W+ix];

                            int32_t b = weights[
                                ((o*CI+c)*KH+ky)*KW+kx
                            ];

                            acc += (int64_t)a * b;
                        }

                size_t index = ((size_t)o*OH+y)*OW+z;

                if (acc != reference[index]) {
                    fprintf(
                        stderr,
                        "FAIL index=%zu got=%" PRId64
                        " expected=%" PRId32 "\n",
                        index, acc, reference[index]
                    );
                    return 1;
                }

                hash ^= (uint32_t)acc;
                hash *= UINT64_C(1099511628211);
                count++;
            }

    printf(
        "PASS MODEL CONV ELEMENTS=%zu HASH=%016"
        PRIx64 "\n",
        count, hash
    );

    return 0;
}
"""
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def execute(command, cwd, timeout=180):
    result = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        capture_output=True,
        timeout=timeout,
    )

    if result.returncode != 0:
        raise RuntimeError(
            f"Command failed: {command}\n"
            f"Exit: {result.returncode}\n"
            f"STDOUT: {result.stdout[-2000:]}\n"
            f"STDERR: {result.stderr[-2000:]}"
        )

    return result.stdout.strip()


assert sys.byteorder == "little"

with tempfile.TemporaryDirectory(
    prefix=f"{model}-riscv-conv-"
) as temp:

    stage = Path(temp)

    binary_data = {
        "input.i32.bin": x,
        "weights.i32.bin": w,
        "reference.i32.bin": ref,
    }

    for filename, array in binary_data.items():
        (stage / filename).write_bytes(array.tobytes())

    (stage / "first_conv.c").write_text(source)

    (stage / "original_model_stdout.log").write_text(
        stdout.getvalue()
    )

    native = stage / "native_conv"
    riscv = stage / "riscv_conv"

    print("===== COMPILE NATIVE AND RISC-V =====", flush=True)

    execute([
        "gcc",
        "-O2", "-std=c11",
        "-Wall", "-Wextra", "-Werror",
        str(stage / "first_conv.c"),
        "-o", str(native),
    ], stage)

    execute([
        "riscv64-linux-gnu-gcc",
        "-O2", "-std=c11",
        "-Wall", "-Wextra", "-Werror",
        "-static",
        "-march=rv64gc", "-mabi=lp64d",
        str(stage / "first_conv.c"),
        "-o", str(riscv),
    ], stage)

    elf = riscv.read_bytes()[:20]

    assert elf[:4] == b"\x7fELF"
    assert elf[4] == 2 and elf[5] == 1
    assert int.from_bytes(elf[18:20], "little") == 243

    paths = [
        "input.i32.bin",
        "weights.i32.bin",
        "reference.i32.bin",
    ]

    print("===== RUN NATIVE =====", flush=True)

    native_result = execute(
        [str(native), *paths], stage
    )

    print(native_result)

    print("===== RUN RISC-V QEMU =====", flush=True)

    riscv_result = execute(
        ["qemu-riscv64", str(riscv), *paths],
        stage,
    )

    print(riscv_result)

    assert native_result == riscv_result
    assert native_result.startswith(
        f"PASS MODEL CONV ELEMENTS={ref.size} "
    )

    (stage / "native.log").write_text(native_result + "\n")
    (stage / "qemu.log").write_text(riscv_result + "\n")

    metadata = {
        "experiment": "checkpoint_derived_first_conv_qemu",
        "model": model,
        "date": "2026-10-28",
        "layer": layer_name,
        "mode": "mqf",
        "input_shape": list(x.shape),
        "weight_shape": list(w.shape),
        "reference_shape": list(ref.shape),
        "stride": list(captured["stride"]),
        "padding": list(captured["padding"]),
        "dilation": list(captured["dilation"]),
        "output_elements": int(ref.size),
        "native_result": native_result,
        "qemu_result": riscv_result,
        "numerical_mismatches": 0,
        "operand_source": (
            "Original IntegerConv2d forward; "
            "checkpoint-derived weights"
        ),
        "reference_semantics": (
            "Integer-valued FP32 PyTorch convolution "
            "before output rescaling and bias"
        ),
        "checkpoint_sha256": sha256(checkpoint),
        "inference_script_sha256": sha256(script),
        "profile_sha256": sha256(profile_path),
        "tensor_sha256": {
            name: sha256(stage / name)
            for name in binary_data
        },
        "full_model_riscv_inference": False,
        "rtl_execution": False,
        "physical_fpga_execution": False,
    }

    (stage / "experiment_manifest.json").write_text(
        json.dumps(metadata, indent=2) + "\n"
    )

    (stage / "README.md").write_text(
        f"# {model}: checkpoint-derived convolution\n\n"
        f"Layer: `{layer_name}`.\n\n"
        f"Verified {ref.size} accumulator elements on "
        "native Linux and RISC-V QEMU against the "
        "original PyTorch computation.\n\n"
        "The model used its original inference script "
        "and checkpoint. This test does not validate "
        "requantization, full-model RISC-V inference, "
        "Rocket RTL execution or physical FPGA execution.\n"
    )

    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(stage, output)

print()
print("===== FINAL VALIDATION =====")
print("Model:", model)
print("Layer:", layer_name)
print("Compared elements:", ref.size)
print("Mismatches: 0")
print("PASS: Checkpoint-derived RISC-V convolution")
print("Results:", output.relative_to(repo))

"""Profile PyTorch matrix multiplication and attention operators."""

import argparse
import json
import os
import runpy
import sys
from pathlib import Path

import torch
from torch.nn.modules.module import (
    register_module_forward_hook,
    register_module_forward_pre_hook,
)
from torch.profiler import ProfilerActivity, profile

ROOT = Path(__file__).resolve().parents[2]
WORKSPACE = Path.home() / "quantized-network-benchmarks"

parser = argparse.ArgumentParser()
parser.add_argument(
    "--model",
    required=True,
    choices=["swint", "levit384"],
)
args = parser.parse_args()

folder = WORKSPACE / args.model
script = folder / "npu_inference_integer.py"

prof = profile(
    activities=[ProfilerActivity.CPU],
    record_shapes=True,
    with_flops=True,
)

state = {"active": False, "finished": False}


def before_forward(module, inputs):
    if (
        module.__class__.__name__ in (
            "IntegerModelWrapper",
            "IntegerVGGWrapper",
        )
        and not state["active"]
        and not state["finished"]
    ):
        prof.__enter__()
        state["active"] = True


def after_forward(module, inputs, output):
    if (
        module.__class__.__name__ in (
            "IntegerModelWrapper",
            "IntegerVGGWrapper",
        )
        and state["active"]
    ):
        prof.__exit__(None, None, None)
        state["active"] = False
        state["finished"] = True


pre_hook = register_module_forward_pre_hook(before_forward)
post_hook = register_module_forward_hook(after_forward)

original_cwd = Path.cwd()
original_argv = sys.argv[:]

try:
    os.chdir(folder)
    sys.path.insert(0, str(folder))
    sys.argv = [str(script), "--mode", "mqf"]
    torch.manual_seed(0)

    namespace = runpy.run_path(
        str(script),
        run_name="__attention_profiler__",
    )
    namespace["main"]()

finally:
    if state["active"]:
        prof.__exit__(None, None, None)
        state["active"] = False

    pre_hook.remove()
    post_hook.remove()
    os.chdir(original_cwd)
    sys.argv = original_argv

if not state["finished"]:
    raise RuntimeError("Integer model forward was not captured")

tracked = (
    "aten::bmm",
    "aten::mm",
    "aten::addmm",
    "aten::matmul",
    "aten::einsum",
)

records = []

for event in prof.key_averages(group_by_input_shape=True):
    name = event.key

    if (
        name in tracked
        or "scaled_dot_product" in name
    ):
        records.append({
            "operator": name,
            "calls": event.count,
            "flops": int(getattr(event, "flops", 0) or 0),
            "input_shapes": str(event.input_shapes),
        })

output = (
    ROOT / "results/optimizer/attention_execution_profiles"
)
output.mkdir(parents=True, exist_ok=True)

destination = output / f"{args.model}.json"

destination.write_text(
    json.dumps({
        "model": args.model,
        "status": "pytorch_operator_profile",
        "operators": records,
        "limitations": [
            "FLOP coverage depends on PyTorch profiler support.",
            "Fused attention may not expose its FLOP count.",
            "Operator FLOPs are not necessarily integer MACs.",
            "CPU execution is not FPGA execution.",
        ],
    }, indent=2) + "\n"
)

print(f"\n===== {args.model.upper()} ATTENTION ANALYSIS =====")

for record in records:
    print(
        record["operator"],
        "calls:", record["calls"],
        "reported GFLOPs:",
        round(record["flops"] / 1e9, 4),
    )

print("\nSaved:", destination)

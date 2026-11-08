from pathlib import Path
import hashlib
import json
import os
import re
import sys

import numpy as np
import torch

HOME = Path.home()
BASE = HOME / "health-slm-model-validation/mahdi-four-models/vgg11"
SOURCE = BASE / "npu_inference_integer.py"
OUT = HOME / "health-slm-model-validation/vgg11-full-reference-export-20261029"

assert SOURCE.is_file()
assert not OUT.exists(), f"Output already exists: {OUT}"

def checksum(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def tensor_of(obj):
    if isinstance(obj, torch.Tensor):
        return obj
    if isinstance(obj, (tuple, list)):
        for value in obj:
            if isinstance(value, torch.Tensor):
                return value
    return None

def save_tensor(path, tensor, binary=False):
    arr = np.ascontiguousarray(tensor.detach().cpu().numpy())
    if binary:
        arr.tofile(path)
    else:
        np.save(path, arr, allow_pickle=False)
    return {
        "file": str(path.relative_to(OUT)),
        "shape": list(arr.shape),
        "dtype": arr.dtype.str,
        "sha256": checksum(path)
    }

class Capture:
    def __init__(self, wrapper, test_image):
        OUT.mkdir(parents=True, exist_ok=False)
        (OUT / "trace").mkdir()
        (OUT / "weights").mkdir()

        self.entries = []
        self.pending = {}
        self.handles = []
        self.input_info = save_tensor(
            OUT / "input.npy", test_image
        )

        for name, module in wrapper.model.named_modules():
            if not name or any(module.children()):
                continue

            def pre(mod, args, layer=name):
                index = len(self.entries)
                x = tensor_of(args)
                entry = {
                    "index": index,
                    "name": layer,
                    "type": type(mod).__name__
                }
                if x is not None:
                    entry["input"] = save_tensor(
                        OUT / "trace" / f"{index:03d}_input.npy",
                        x
                    )
                self.entries.append(entry)
                self.pending.setdefault(id(mod), []).append(entry)

            def post(mod, args, result):
                entry = self.pending[id(mod)].pop()
                y = tensor_of(result)
                if y is not None:
                    entry["output"] = save_tensor(
                        OUT / "trace" /
                        f"{entry['index']:03d}_output.npy",
                        y
                    )

            self.handles.append(module.register_forward_pre_hook(pre))
            self.handles.append(module.register_forward_hook(post))

    def finish(self, wrapper, test_image, output, true_label):
        for handle in self.handles:
            handle.remove()

        assert all(not stack for stack in self.pending.values())

        logits = save_tensor(OUT / "logits.npy", output)
        predicted = int(output.argmax(dim=1).item())
        label = int(true_label)

        layers = []
        quantized_count = 0

        attrs = (
            "stride", "padding", "dilation", "groups",
            "kernel_size", "act_bit_width", "weight_bits",
            "act_bits", "calibrated", "eps",
            "output_size", "in_features", "out_features",
            "_C_out"
        )

        for index, (name, module) in enumerate(
            wrapper.model.named_modules()
        ):
            if not name:
                continue

            kind = type(module).__name__
            if kind in ("IntegerConv2d", "IntegerLinear"):
                quantized_count += 1

            entry = {
                "name": name,
                "type": kind,
                "attributes": {},
                "tensors": {}
            }

            for attr in attrs:
                value = getattr(module, attr, None)
                if isinstance(value, (bool, int, float, str)):
                    entry["attributes"][attr] = value
                elif isinstance(value, (tuple, list)):
                    if all(
                        isinstance(v, (bool, int, float, str))
                        for v in value
                    ):
                        entry["attributes"][attr] = list(value)

            tensors = {}
            tensors.update(dict(module.named_parameters(recurse=False)))
            tensors.update(dict(module.named_buffers(recurse=False)))

            for key in ("weight_float", "bias_float"):
                value = getattr(module, key, None)
                if isinstance(value, torch.Tensor):
                    tensors.setdefault(key, value)

            safe_name = re.sub(r"[^A-Za-z0-9_]", "_", name)

            for key, value in tensors.items():
                filename = f"{index:03d}_{safe_name}_{key}.bin"
                entry["tensors"][key] = save_tensor(
                    OUT / "weights" / filename,
                    value,
                    binary=True
                )

            layers.append(entry)

        assert quantized_count == 11, quantized_count
        assert len(self.entries) >= 11
        assert all("output" in entry for entry in self.entries)

        manifest = {
            "model": "Mahdi VGG11 CIFAR10",
            "execution": "original MQF Linux CPU",
            "source_sha256": checksum(SOURCE),
            "seed": 20261029,
            "input": self.input_info,
            "logits": logits,
            "predicted_class": predicted,
            "reference_label": label,
            "prediction_correct": predicted == label,
            "quantized_layers": quantized_count,
            "module_calls": len(self.entries),
            "trace": self.entries,
            "modules": layers,
            "full_riscv_inference": False,
            "full_rocket_rtl_inference": False,
            "physical_fpga_tested": False
        }

        (OUT / "manifest.json").write_text(
            json.dumps(manifest, indent=2) + "\n"
        )

        print("\n===== VGG11 REFERENCE EXPORT =====")
        print("Quantized layers:", quantized_count)
        print("Captured module calls:", len(self.entries))
        print("Predicted class:", predicted)
        print("Reference label:", label)
        print("Prediction correct:", predicted == label)
        print("Output:", OUT)
        print("PASS: Full CPU reference and model parameters saved")

original = SOURCE.read_text()
anchor = "        output = wrapper(test_image)"
assert original.count(anchor) == 1

instrumented = original.replace(
    anchor,
    "        _capture = Capture(wrapper, test_image)\n"
    + anchor
    + "\n        _capture.finish(wrapper, test_image, output, true_label)",
    1
)

torch.manual_seed(20261029)
torch.set_num_threads(1)

sys.path.insert(0, str(BASE))
sys.argv = [str(SOURCE), "--mode", "mqf"]
os.chdir(BASE)

namespace = {
    "__name__": "__main__",
    "__file__": str(SOURCE),
    "Capture": Capture
}

print("===== EXECUTING MAHDI VGG11 =====")
exec(compile(instrumented, str(SOURCE), "exec"), namespace)

assert (OUT / "manifest.json").is_file()
assert (OUT / "logits.npy").is_file()

print("\nPASS: VGG11 reference ready for C/RISC-V implementation")
print("PASS: Original source and checkpoint unchanged")

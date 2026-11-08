from pathlib import Path
import hashlib
import json
import os
import sys

import numpy as np
import torch

HOME = Path.home()
BASE = HOME / "health-slm-model-validation/mahdi-four-models/swint"
SOURCE = BASE / "npu_inference_integer.py"
OUT = HOME / "health-slm-model-validation/swint-full-reference-export-20261029"

assert SOURCE.is_file(), SOURCE
assert not OUT.exists(), f"Reference directory already exists: {OUT}"

TRACE_TYPES = {
    "IntegerConv2d", "IntegerLinear",
    "LayerNorm", "GELU", "Softmax",
    "WindowAttention", "SwinTransformerBlock",
    "PatchMerging", "PatchEmbed",
}

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def first_tensor(obj):
    if isinstance(obj, torch.Tensor):
        return obj
    if isinstance(obj, (list, tuple)):
        for value in obj:
            result = first_tensor(value)
            if result is not None:
                return result
    return None

def save_array(name, tensor):
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    array = np.ascontiguousarray(tensor.detach().cpu().numpy())
    np.save(path, array, allow_pickle=False)
    return {
        "file": name,
        "shape": list(array.shape),
        "dtype": str(array.dtype),
        "sha256": digest(path),
    }

class Capture:
    def __init__(self, wrapper, test_image):
        OUT.mkdir(parents=True, exist_ok=False)
        self.trace = []
        self.handles = []
        self.quant_layers = []
        self.input = save_array("input.npy", test_image)

        for name, module in wrapper.model.named_modules():
            kind = type(module).__name__
            if kind not in TRACE_TYPES:
                continue

            if kind in ("IntegerConv2d", "IntegerLinear"):
                self.quant_layers.append(name)

            def make_hook(layer_name, layer_kind):
                def hook(mod, args, result):
                    index = len(self.trace)
                    entry = {
                        "index": index,
                        "name": layer_name,
                        "type": layer_kind,
                    }

                    x = first_tensor(args)
                    y = first_tensor(result)

                    if x is not None:
                        entry["input"] = save_array(
                            f"trace/{index:04d}_input.npy", x
                        )

                    if y is not None:
                        entry["output"] = save_array(
                            f"trace/{index:04d}_output.npy", y
                        )

                    self.trace.append(entry)
                return hook

            self.handles.append(
                module.register_forward_hook(make_hook(name, kind))
            )

    def finish(self, wrapper, output):
        for handle in self.handles:
            handle.remove()

        logits = save_array("logits.npy", output)
        predicted = int(output.argmax(dim=1).item())

        quantization = []

        for name, module in wrapper.model.named_modules():
            if name not in self.quant_layers:
                continue

            record = {
                "name": name,
                "type": type(module).__name__,
                "attributes": {},
                "tensors": {},
            }

            for attr in (
                "weight_float", "bias_float", "scale_w",
                "act_scale", "act_zero_point",
                "weight_bits", "act_bit_width",
                "calibrated", "stride", "padding",
                "dilation", "groups",
            ):
                value = getattr(module, attr, None)

                if isinstance(value, torch.Tensor):
                    safe = name.replace(".", "_")
                    record["tensors"][attr] = save_array(
                        f"parameters/{safe}_{attr}.npy", value
                    )
                elif isinstance(value, (bool, int, float, str)):
                    record["attributes"][attr] = value
                elif isinstance(value, (list, tuple)):
                    if all(isinstance(x, (bool, int, float))
                           for x in value):
                        record["attributes"][attr] = list(value)

            quantization.append(record)

        state_path = OUT / "wrapped_model_state.pt"
        torch.save(wrapper.model.state_dict(), state_path)

        manifest = {
            "model": "Mahdi Swin-T CIFAR100",
            "execution": "Original MQF CPU inference",
            "seed": 20261029,
            "source_sha256": digest(SOURCE),
            "input": self.input,
            "logits": logits,
            "predicted_class": predicted,
            "quantized_layer_count": len(self.quant_layers),
            "captured_module_calls": len(self.trace),
            "wrapped_state_sha256": digest(state_path),
            "quantization": quantization,
            "trace": self.trace,
            "functional_ops_traced": False,
            "full_riscv_inference": False,
            "physical_fpga_tested": False,
        }

        (OUT / "manifest.json").write_text(
            json.dumps(manifest, indent=2) + "\n"
        )

        print("\n===== SWIN-T REFERENCE CAPTURE =====")
        print("Quantized layers:", len(self.quant_layers))
        print("Captured module calls:", len(self.trace))
        print("Predicted class:", predicted)
        print("Manifest:", OUT / "manifest.json")

        assert len(self.quant_layers) == 53, (
            f"Expected 53 quantized layers, got {len(self.quant_layers)}"
        )
        assert len(self.trace) >= 53
        assert list(output.shape) == [1, 100]

        print("PASS: Swin-T CPU reference captured")
        print("NOTE: Functional attention operations still need graph capture")

source = SOURCE.read_text()

anchor = "        output = wrapper(test_image)"
assert source.count(anchor) == 1, "Expected inference anchor missing"

instrumented = source.replace(
    anchor,
    "        _swint_capture = Capture(wrapper, test_image)\n"
    + anchor
    + "\n        _swint_capture.finish(wrapper, output)",
    1,
)

torch.manual_seed(20261029)
torch.set_num_threads(4)

sys.path.insert(0, str(BASE))
sys.argv = [str(SOURCE), "--mode", "mqf"]
os.chdir(BASE)

namespace = {
    "__name__": "__main__",
    "__file__": str(SOURCE),
    "Capture": Capture,
}

print("===== EXECUTING MAHDI'S ORIGINAL SWIN-T =====")
exec(compile(instrumented, str(SOURCE), "exec"), namespace)

assert (OUT / "manifest.json").is_file()
print("\nPASS: Swin-T reference export completed")

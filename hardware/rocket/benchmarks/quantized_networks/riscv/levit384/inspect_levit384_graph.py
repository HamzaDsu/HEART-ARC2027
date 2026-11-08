from pathlib import Path
from collections import Counter
import importlib
import inspect
import json

REF = (
    Path.home()
    / "health-slm-model-validation"
    / "levit384-full-reference-export-20261029"
)

m = json.loads((REF / "manifest.json").read_text())
trace = m["trace"]

print("===== EXECUTION INVENTORY =====")
print("Total module calls:", len(trace))
print("Quantized layers:", len(m["quantization"]))
print("Prediction:", m["predicted_class"])

print("\n===== OPERATOR TYPES =====")
for kind, count in Counter(
    x["type"] for x in trace
).most_common():
    print(f"{kind}: {count}")

print("\n===== FIRST 25 EXECUTED MODULES =====")
for x in trace[:25]:
    print(
        x["name"],
        x["type"],
        x["input_shape"],
        "=>",
        x["output_shape"],
    )

print("\n===== ATTENTION AND DOWNSAMPLING =====")
for x in trace:
    name = x["name"]
    kind = x["type"]
    if (
        "attn" in name.lower()
        and kind in ("Attention", "AttentionSubsample")
    ) or "subsample" in kind.lower():
        print(
            name, kind,
            x["input_shape"], "=>", x["output_shape"],
        )

print("\n===== QUANTIZED OPERATOR SHAPES =====")
for x in m["quantization"]:
    print(
        x["name"],
        x["type"],
        x["weight_shape"],
    )

print("\n===== TIMM LEVIT FORWARD IMPLEMENTATIONS =====")

module = importlib.import_module("timm.models.levit")
types = {x["type"] for x in trace}

for name, cls in inspect.getmembers(module, inspect.isclass):
    if cls.__module__ != module.__name__:
        continue

    if name not in types and "levit" not in name.lower():
        continue

    for method in ("forward_features", "forward"):
        fn = getattr(cls, method, None)
        if fn is None:
            continue

        if method == "forward" and name not in types:
            continue

        print(f"\n--- {name}.{method} ---")
        try:
            print(inspect.getsource(fn))
        except (OSError, TypeError):
            print("Source unavailable")

print("\n===== INSPECTION COMPLETE =====")

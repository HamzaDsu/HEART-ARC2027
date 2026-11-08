from pathlib import Path
import json
import subprocess

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
REF = Path.home() / "health-slm-model-validation/swint-full-reference-export-20261029"
RUNTIME = Path.home() / "health-slm-model-validation/swint-full-runtime-20261029"
WORK = Path.home() / "health-slm-model-validation/swint-connected-runs-20261029"
EVIDENCE = REPO / "results/model_execution/swint_full_c_riscv_qemu_20261029"

manifest = json.loads((REF / "manifest.json").read_text())
runtime = json.loads((EVIDENCE / "runtime_manifest.json").read_text())

trace = {x["name"]: x for x in manifest["trace"]}
quant = {x["name"]: x for x in manifest["quantization"]}
params = {x["name"]: x for x in runtime["quantized_layers"]}

act_name = "layers.0.blocks.0.mlp.act"
fc2_name = "layers.0.blocks.0.mlp.fc2"

def load(info):
    return np.load(REF / info["file"], allow_pickle=False).astype(np.float32)

ref_input = load(trace[act_name]["output"])
c_input = np.fromfile(
    WORK / "debug_dumps" / (act_name + ".bin"),
    dtype=np.float32,
).reshape(ref_input.shape)

expected = load(trace[fc2_name]["output"])

attrs = quant[fc2_name]["attributes"]
tensors = quant[fc2_name]["tensors"]
p = params[fc2_name]

bits = int(attrs["act_bit_width"])
calibrated = bool(attrs["calibrated"])
qmax = 2 ** bits - 1

def input_quantization(data):
    x = torch.from_numpy(np.ascontiguousarray(data))

    if calibrated:
        sx = torch.tensor(
            float(load(tensors["act_scale"]).reshape(-1)[0])
        )
        zp = torch.tensor(
            float(load(tensors["act_zero_point"]).reshape(-1)[0])
        )
    else:
        sx = ((x.max() - x.min()) / qmax).clamp(min=1e-8)
        zp = (-x.min() / sx).round().clamp(0, qmax)

    raw = x / sx + zp
    codes = raw.round().clamp(0, qmax).int()

    return codes.numpy(), float(sx), float(zp), raw.numpy()

q_ref, sx_ref, zp_ref, raw_ref = input_quantization(ref_input)
q_c, sx_c, zp_c, raw_c = input_quantization(c_input)

different = np.argwhere(q_ref != q_c)

print("===== FC2 ACTIVATION QUANTIZATION =====")
print("Activation bits:", bits)
print("Calibrated:", calibrated)
print("Reference scale:", sx_ref)
print("C-input scale:", sx_c)
print("Reference zero point:", zp_ref)
print("C-input zero point:", zp_c)
print("Maximum GELU float difference:",
      float(np.max(np.abs(ref_input - c_input))))
print("Different quantized activation codes:", len(different))

for pos in different[:8]:
    idx = tuple(int(v) for v in pos)
    print(
        "INDEX", idx,
        "reference_q=", int(q_ref[idx]),
        "c_input_q=", int(q_c[idx]),
        "reference_x=", float(ref_input[idx]),
        "c_x=", float(c_input[idx]),
        "reference_pre_round=", float(raw_ref[idx]),
        "c_pre_round=", float(raw_c[idx]),
    )

WORK.mkdir(parents=True, exist_ok=True)
binary = WORK / "fc2_diagnosis_native"

subprocess.run([
    "gcc", "-O2", "-std=c11", "-ffp-contract=off",
    str(HERE / "swint_integer_linear.c"),
    "-lm", "-o", str(binary),
], check=True)

weights = p["weights"]["shape"]
cout, cin = weights
rows = ref_input.size // cin

assert expected.size == rows * cout

records = []

for label, data in (
    ("pytorch_gelu_input", ref_input),
    ("connected_c_gelu_input", c_input),
):
    input_file = WORK / f"fc2_{label}_input.bin"
    output_file = WORK / f"fc2_{label}_output.bin"

    data.tofile(input_file)

    subprocess.run([
        str(binary),
        str(input_file),
        str(RUNTIME / p["weights"]["file"]),
        str(RUNTIME / p["scales"]["file"]),
        str(RUNTIME / p["bias"]["file"]),
        str(output_file),
        str(rows), str(cin), str(cout),
        str(bits), str(int(calibrated)),
        str(float(p["activation_scale"])),
        str(float(p["activation_zero_point"])),
    ], check=True)

    actual = np.fromfile(
        output_file, dtype=np.float32
    ).reshape(expected.shape)

    delta = np.abs(actual - expected)
    mismatches = int(np.count_nonzero(
        ~np.isclose(actual, expected, atol=2e-4, rtol=3e-4)
    ))

    print(
        label,
        "FC2 mismatches:", mismatches,
        "maximum error:", float(delta.max()),
    )

    records.append({
        "input": label,
        "fc2_mismatches": mismatches,
        "max_abs_error": float(delta.max()),
    })

summary = {
    "operator": fc2_name,
    "activation_bits": bits,
    "calibrated": calibrated,
    "reference_scale": sx_ref,
    "connected_scale": sx_c,
    "quantized_input_code_differences": len(different),
    "records": records,
}

destination = EVIDENCE / "fc2_quant_diagnosis.json"
destination.write_text(json.dumps(summary, indent=2) + "\n")

print("===== DIAGNOSIS COMPLETE =====")
print("Evidence:", destination)

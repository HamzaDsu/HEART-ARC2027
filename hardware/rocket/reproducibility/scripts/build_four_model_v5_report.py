from pathlib import Path
import csv
import json

ROOT = Path(__file__).resolve().parents[2]
OPT = ROOT / "results/optimizer"
FINAL = OPT / "final_evaluation"
V5 = OPT / "unified_search_nexys_bram_v5"

MODELS = ["resnet18", "vgg11", "swint", "levit384"]
LABELS = {
    "resnet18": "ResNet18",
    "vgg11": "VGG11",
    "swint": "Swin-T",
    "levit384": "LeViT384",
}

def load(path):
    return json.loads(path.read_text())

search_path = (
    FINAL /
    "v5_rtl_guided_real_search_comparison_20261029.json"
)
fpga_path = (
    FINAL /
    "v5_matched_fpga_cache_comparison_20261029.json"
)

search = load(search_path)
fpga = load(fpga_path)

assert search["board"] == "nexysvideo"
assert search["models_compared"] == 4
assert search["balanced_recommendations_changed"] == 3
assert search["other_objectives_unchanged"] is True
assert search["full_model_latency_validated"] is False
assert search["physical_fpga_validated"] is False

assert fpga["fpga_part"] == "xc7a200tsbg484-1"
assert fpga["physical_fpga_tested"] is False
assert fpga["full_model_inference_validated"] is False
assert fpga["comparison_type"] == "cache_only_configuration_change"

baseline = fpga["baseline"]
optimized = fpga["optimized"]

assert baseline["cache_kib"] == 32
assert optimized["cache_kib"] == 16

tolerance = float(search["tolerance_pct"])
rows = []
changed = 0

for model in MODELS:
    entry = next(
        x for x in search["results"]
        if x["model"] == model
    )

    original = entry["original_configuration"]
    selected = entry["experimental_configuration"]
    decision = entry["decision"]

    v5 = load(V5 / f"{model}_balanced.json")
    v5_original = (
        v5["recommended_exploration_candidate"]
        ["configuration"]
    )

    assert v5["board"] == "nexysvideo"
    assert v5["status"] == "exploratory_heuristic_search"
    assert v5_original == original
    assert v5["memory_assessment"]["status"] == (
        "runtime_memory_not_validated"
    )

    assert original["cores"] == selected["cores"] == 1
    assert original["icache_kib"] == 32
    assert original["dcache_kib"] == 32
    assert selected["icache_kib"] == selected["dcache_kib"]
    assert original["target_clock_mhz"] == 50
    assert selected["target_clock_mhz"] == 50
    assert original["scratchpad_kib"] == 0
    assert selected["scratchpad_kib"] == 0
    assert original["l2_enabled"] is False
    assert selected["l2_enabled"] is False

    cycles16 = int(decision["measured_cycles_16k"])
    cycles32 = int(decision["measured_cycles_32k"])

    slowdown = 100 * (cycles16 - cycles32) / cycles32

    assert abs(
        slowdown - decision["measured_slowdown_pct"]
    ) < 0.0001

    expected_cache = 16 if slowdown <= tolerance else 32

    assert selected["icache_kib"] == expected_cache, model

    if expected_cache != 32:
        changed += 1

    rows.append({
        "model": model,
        "original_cache_kib": 32,
        "rtl_guided_cache_kib": expected_cache,
        "cycles_16k": cycles16,
        "cycles_32k": cycles32,
        "tile_slowdown_percent": round(slowdown, 6),
        "original_resource_cost_estimate": entry[
            "original_estimated_resource_cost"
        ],
        "rtl_guided_resource_cost_estimate": entry[
            "experimental_estimated_resource_cost"
        ],
        "recommendation_changed": expected_cache != 32,
        "measurement_scope": (
            "128-output first-convolution RTL tile"
        ),
        "full_model_cache_latency_validated": False,
        "physical_fpga_tested": False,
    })

assert changed == 3

metrics = [
    ("luts", "LUTs"),
    ("flip_flops", "Flip-flops"),
    ("bram36_equivalent", "BRAM36 equivalents"),
    ("dsp", "DSPs"),
]

resources = []

for key, label in metrics:
    before = float(baseline["resources"][key])
    after = float(optimized["resources"][key])

    resources.append({
        "name": label,
        "baseline_32k": before,
        "optimized_16k": after,
        "reduction_percent": round(
            100 * (before - after) / before, 6
        ),
    })

bram = next(
    x for x in resources
    if x["name"] == "BRAM36 equivalents"
)

assert abs(bram["reduction_percent"] - 16.666667) < 0.001

report = {
    "title": "Mahdi four-model V5 optimizer comparison",
    "board": "Digilent Nexys Video",
    "fpga_part": fpga["fpga_part"],
    "tool": fpga["tool"],
    "tolerance_percent": tolerance,
    "original_vs_rtl_guided": rows,
    "recommendations_changed": changed,
    "vivado_matched_configuration_resources": resources,
    "vivado_32k_wns_ns": baseline["wns_ns"],
    "vivado_16k_wns_ns": optimized["timing"]["wns_ns"],
    "reference_sources": [
        str(search_path.relative_to(ROOT)),
        str(fpga_path.relative_to(ROOT)),
    ],
    "limitations": [
        "RTL cycles measure first-convolution tiles, not full-model latency.",
        "Vivado measurements apply to matched hardware configurations, not all optimizer search candidates.",
        "Runtime memory feasibility has not been validated.",
        "No physical FPGA execution has been performed.",
        "Strict PyTorch numerical validation remains unresolved for Swin-T and LeViT384.",
    ],
}

json_path = FINAL / "four_model_v5_integrated_comparison.json"
csv_path = FINAL / "four_model_v5_integrated_comparison.csv"
md_path = FINAL / "FOUR_MODEL_V5_INTEGRATED_COMPARISON.md"

json_path.write_text(json.dumps(report, indent=2) + "\n")

with csv_path.open("w", newline="") as f:
    writer = csv.DictWriter(f, fieldnames=list(rows[0]), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)

lines = [
    "# Four-Model V5 Optimizer Comparison",
    "",
    "Target: Nexys Video, `xc7a200tsbg484-1`.",
    "",
    "## RTL-guided cache selection",
    "",
    "| Model | Original I/D | Selected I/D | 16 KiB cycles | 32 KiB cycles | Slowdown |",
    "|---|---:|---:|---:|---:|---:|",
]

for r in rows:
    lines.append(
        f"| {LABELS[r['model']]} "
        f"| {r['original_cache_kib']} KiB "
        f"| {r['rtl_guided_cache_kib']} KiB "
        f"| {r['cycles_16k']:,} "
        f"| {r['cycles_32k']:,} "
        f"| {r['tile_slowdown_percent']:.6f}% |"
    )

lines += [
    "",
    f"Decision threshold: {tolerance}% measured slowdown.",
    "",
    "## Matched Vivado resource comparison",
    "",
    "| Resource | 32 KiB | 16 KiB | Reduction |",
    "|---|---:|---:|---:|",
]

for r in resources:
    lines.append(
        f"| {r['name']} | {r['baseline_32k']:g} "
        f"| {r['optimized_16k']:g} "
        f"| {r['reduction_percent']:.3f}% |"
    )

lines += [
    "",
    f"32 KiB WNS: +{baseline['wns_ns']} ns.",
    f"16 KiB WNS: +{optimized['timing']['wns_ns']} ns.",
    "",
    "## Evidence and limitations",
    "",
    "The optimizer decisions are experimental and use "
    "128-output first-convolution RTL tile measurements.",
    "",
    "Both compared FPGA configurations have Vivado post-route evidence, "
    "but physical hardware execution has not been performed.",
    "",
    "Full-model native Linux and RISC-V QEMU execution was completed "
    "separately for all four models. These runs do not establish "
    "full-model latency improvements from the selected cache sizes.",
    "",
    "Strict numerical reference validation remains unresolved "
    "for Swin-T and LeViT384.",
    "",
    "Source JSON files:",
    "",
]

for p in report["reference_sources"]:
    lines.append(f"- `{p}`")

md_path.write_text("\n".join(lines) + "\n")

print("===== FOUR-MODEL V5 RESULTS =====")
for row in rows:
    print(
        row["model"],
        "32 KiB ->",
        row["rtl_guided_cache_kib"],
        "KiB; slowdown",
        row["tile_slowdown_percent"],
        "%"
    )

print("Recommendations changed:", changed, "/4")
print("Matched Vivado BRAM reduction:",
      bram["reduction_percent"], "%")
print("Generated:")
print(json_path)
print(csv_path)
print(md_path)
print("PASS: Results match existing experimental evidence")

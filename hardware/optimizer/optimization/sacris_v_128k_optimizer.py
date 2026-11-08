from pathlib import Path
import csv
import json
import re

ROOT = Path(__file__).resolve().parents[2]

MEAS = ROOT / "results/optimizer/sacris_v_measurements"
RESOURCES = ROOT / "results/optimizer/sacris_v_synthesis/resource_matrix_128k.csv"
OUT = ROOT / "results/optimizer/sacris_v_selection_128k"

CONFIGS = {
    "IMF_SLM_128K": "imf_slm_128k",
    "DualIMFB_SLM_128K": "dualimfb_slm_128k",
    "IMB_SLM_128K": "imb_slm_128k",
    "IMF_TMR_SLM_128K": "imf_tmr_slm_128k",
}

CAPACITY = {
    "luts": 134600,
    "ffs": 269200,
    "bram36": 365,
    "dsps": 740,
}

WEIGHTS = {
    "luts": 0.50,
    "ffs": 0.20,
    "bram36": 0.20,
    "dsps": 0.10,
}

def extract(text, key):
    match = re.search(
        rf"^{re.escape(key)}\s*:\s*(\S+)",
        text,
        re.MULTILINE,
    )
    if not match:
        raise ValueError(f"Missing field: {key}")
    return match.group(1)

def benchmark(prefix, variant):
    path = MEAS / f"{prefix}_{variant}.log"
    text = path.read_text()

    assert "Simulation ended by END signal" in text
    assert extract(text, "Blocks") == "32"
    assert extract(text, "Repeats") == "10"

    return (
        int(extract(text, "Total cycles")),
        extract(text, "Checksum bits"),
    )

with RESOURCES.open(newline="") as file:
    resource_rows = list(csv.DictReader(file))

resource_map = {
    row["configuration"]: row
    for row in resource_rows
}

assert len(resource_rows) == 4
assert set(resource_map) == set(CONFIGS)

results = []

for config, prefix in CONFIGS.items():
    normal, check1 = benchmark(prefix, "small")
    dma, check2 = benchmark(prefix, "small_spad")

    assert check1 == check2
    assert normal > 0 and dma > 0

    resources = {
        key: int(resource_map[config][key])
        for key in CAPACITY
    }

    for key in CAPACITY:
        assert 0 < resources[key] <= CAPACITY[key]

    pressure = sum(
        WEIGHTS[key] * resources[key] / CAPACITY[key]
        for key in CAPACITY
    )

    results.append({
        "configuration": config,
        "small_cycles": normal,
        "small_dma_cycles": dma,
        "mean_cycles": (normal + dma) / 2,
        "checksum": check1,
        "resource_pressure": pressure,
        **resources,
    })

assert len({r["checksum"] for r in results}) == 1

best_cycles = min(r["mean_cycles"] for r in results)
best_resources = min(r["resource_pressure"] for r in results)

for row in results:
    row["performance_ratio"] = (
        row["mean_cycles"] / best_cycles
    )
    row["resource_ratio"] = (
        row["resource_pressure"] / best_resources
    )
    row["balanced_score"] = (
        0.5 * row["performance_ratio"]
        + 0.5 * row["resource_ratio"]
    )

selected = {
    "performance": min(
        results, key=lambda r: r["mean_cycles"]
    )["configuration"],
    "resources": min(
        results, key=lambda r: r["resource_pressure"]
    )["configuration"],
    "balanced": min(
        results, key=lambda r: r["balanced_score"]
    )["configuration"],
}

report = {
    "scope": "128 KiB memory-matched SaCriS-V configurations",
    "workloads": ["small", "small_spad"],
    "resource_weights": WEIGHTS,
    "balanced_performance_weight": 0.5,
    "balanced_resource_weight": 0.5,
    "selection": selected,
    "candidates": results,
    "limitations": [
        "Post-synthesis resource estimates",
        "50 MHz timing closure not established",
        "Streaming workloads not validated at 128 KiB",
        "No physical FPGA deployment",
    ],
}

lines = [
    "# SaCriS-V Optimizer Results",
    "",
    f"**Performance:** `{selected['performance']}`",
    f"**Resources:** `{selected['resources']}`",
    f"**Balanced:** `{selected['balanced']}`",
    "",
    "| Configuration | Mean cycles | LUTs | FFs | BRAM36 | DSPs | Balanced score |",
    "|---|---:|---:|---:|---:|---:|---:|",
]

for r in sorted(results, key=lambda x: x["balanced_score"]):
    lines.append(
        f"| {r['configuration']} "
        f"| {r['mean_cycles']:,.0f} "
        f"| {r['luts']} | {r['ffs']} "
        f"| {r['bram36']} | {r['dsps']} "
        f"| {r['balanced_score']:.4f} |"
    )

lines.extend([
    "",
    "## Selection methodology",
    "",
    "Performance uses mean cycles over small and small-spad.",
    "Resource pressure uses FPGA-capacity-normalized utilization:",
    "50% LUT, 20% FF, 20% BRAM, 10% DSP.",
    "Balanced uses 50% normalized performance and 50% normalized resource pressure.",
    "",
    "## Limitations",
    "",
    "Selections apply to the 128 KiB small-workload comparison only.",
    "They are based on RTL performance and post-synthesis resource estimates.",
    "50 MHz timing closure and physical deployment are not established.",
])

OUT.mkdir(parents=True, exist_ok=False)

(OUT / "selection.json").write_text(
    json.dumps(report, indent=2) + "\n"
)

(OUT / "selection.md").write_text(
    "\n".join(lines) + "\n"
)

with (OUT / "comparisons.csv").open("x", newline="") as file:
    writer = csv.DictWriter(
        file, fieldnames=list(results[0])
    )
    writer.writeheader()
    writer.writerows(results)

print("\n".join(lines))
print()
print("PASS: ALL FOUR CONFIGURATIONS EVALUATED")
print("SAVED:", OUT)

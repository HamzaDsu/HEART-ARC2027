from pathlib import Path
from collections import defaultdict
import json
import re

repo = Path(__file__).resolve().parents[2]
generated = Path.home() / "chipyard/fpga/generated-src"

configs = {
    "resource": "UnifiedResourceNexysVideoConfig",
    "performance": "UnifiedPerformance2Core32KNexysVideoConfig",
}

results = {}

for label, name in configs.items():
    path = (
        generated /
        f"chipyard.fpga.nexysvideo.NexysVideoHarness.{name}"
        / "obj/report/utilization.txt"
    )

    assert path.is_file(), f"Missing report: {path}"
    lines = path.read_text(errors="replace").splitlines()

    top = next(
        line for line in lines
        if re.match(
            r"^\|\s*NexysVideoHarness\s*\|\s*\(top\)\s*\|",
            line
        )
    )

    values = [x.strip() for x in top.strip("|").split("|")]
    expected18 = int(values[8])
    expected36 = int(values[7])

    instances = set()

    for line in lines:
        if not line.startswith("|"):
            continue

        cells = [x.strip() for x in line.strip("|").split("|")]

        if len(cells) >= 2 and cells[1] in (
            "RAMB18E1", "RAMB36E1"
        ):
            instances.add((cells[0], cells[1]))

    groups = defaultdict(lambda: [0, 0])

    for instance, primitive in instances:
        name_lower = instance.lower()

        if "icache" in name_lower:
            category = "instruction_cache"
        elif "dcache" in name_lower:
            category = "data_cache"
        else:
            category = "other"

        groups[category][0 if primitive == "RAMB18E1" else 1] += 1

    actual18 = sum(x[0] for x in groups.values())
    actual36 = sum(x[1] for x in groups.values())

    assert (actual18, actual36) == (
        expected18, expected36
    ), f"Primitive mismatch: {label}"

    breakdown = {
        category: {
            "ramb18": counts[0],
            "ramb36": counts[1],
            "bram36_equivalents": counts[1] + counts[0] / 2,
        }
        for category, counts in sorted(groups.items())
    }

    results[label] = {
        "configuration": configs[label],
        "total_ramb18": actual18,
        "total_ramb36": actual36,
        "total_bram36_equivalents": actual36 + actual18 / 2,
        "breakdown": breakdown,
    }

    print(f"\n{label.upper()}")
    print(json.dumps(results[label], indent=2))

output = (
    repo / "results/optimizer/unified_search_nexys"
    / "vivado_bram_breakdown.json"
)

content = json.dumps(results, indent=2) + "\n"

if output.exists() and output.read_text() != content:
    raise SystemExit(f"STOP: Existing data differs: {output}")

output.write_text(content)
print("\nPASS: BRAM data reconciled and saved")
print(output)

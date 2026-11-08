from pathlib import Path
import hashlib
import json
import re

repo = Path(__file__).resolve().parents[2]
generated = Path.home() / "chipyard/fpga/generated-src"

configs = {
    "resource": {
        "name": "UnifiedResourceNexysVideoConfig",
        "cores": 1,
        "cache_kib": 8,
        "predicted_luts": 37696,
        "predicted_bram36": 39,
    },
    "performance": {
        "name": "UnifiedPerformance2Core32KNexysVideoConfig",
        "cores": 2,
        "cache_kib": 32,
        "predicted_luts": 70679,
        "predicted_bram36": 50,
    },
}


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_drc(text):
    count = re.search(r"Violations found:\s*(\d+)", text)
    assert count, "DRC violation count missing"

    # Use the actual report sections, not the table of contents.
    starts = list(re.finditer(
        r"(?m)^\s*1\. REPORT SUMMARY\s*$", text
    ))
    ends = list(re.finditer(
        r"(?m)^\s*2\. REPORT DETAILS\s*$", text
    ))

    assert starts and ends, "DRC section headings missing"

    begin = starts[-1].end()
    end = ends[-1].start()

    assert begin < end, "Invalid DRC section order"

    rules = {}

    for line in text[begin:end].splitlines():
        line = line.strip()

        if not (line.startswith("|") and line.endswith("|")):
            continue

        cells = [x.strip() for x in line[1:-1].split("|")]

        if len(cells) != 4 or not cells[3].isdigit():
            continue

        rule, severity, description, number = cells

        rules[rule] = {
            "severity": severity,
            "description": description,
            "count": int(number),
        }

    actual_count = sum(x["count"] for x in rules.values())
    reported_count = int(count.group(1))

    assert actual_count == reported_count, (
        f"DRC mismatch: parsed {actual_count}, "
        f"reported {reported_count}"
    )

    return reported_count, rules


results = {}

for label, spec in configs.items():
    name = spec["name"]

    base = generated / (
        f"chipyard.fpga.nexysvideo.NexysVideoHarness.{name}"
    )

    report = base / "obj/report"

    paths = {
        "utilization": report / "utilization.txt",
        "timing": report / "timing.txt",
        "drc": report / "drc.txt",
        "bitstream": base / "obj/NexysVideoHarness.bit",
    }

    for path in paths.values():
        assert path.is_file(), f"Missing: {path}"

    util = paths["utilization"].read_text()
    timing = paths["timing"].read_text()
    drc = paths["drc"].read_text()

    rows = [
        line for line in util.splitlines()
        if re.match(
            r"^\|\s*NexysVideoHarness\s*\|\s*\(top\)\s*\|",
            line
        )
    ]

    assert len(rows) == 1, f"Invalid utilization: {name}"

    v = [
        x.strip()
        for x in rows[0].strip("|").split("|")
    ]

    luts = int(v[2])
    logic_luts = int(v[3])
    ff = int(v[6])
    bram36 = int(v[7]) + int(v[8]) / 2
    dsp = int(v[9])

    section = timing.split("Design Timing Summary", 1)[1]

    match = re.search(
        r"(?m)^\s*(-?\d+\.\d+)\s+"
        r"(-?\d+\.\d+)\s+\d+\s+\d+\s+"
        r"(-?\d+\.\d+)\s+(-?\d+\.\d+)",
        section
    )

    assert match, f"Timing summary missing: {name}"

    wns = float(match.group(1))
    tns = float(match.group(2))
    whs = float(match.group(3))
    ths = float(match.group(4))

    drc_count, drc_rules = parse_drc(drc)

    assert "Design State : Fully Routed" in drc, \
        f"Not confirmed fully routed: {name}"

    assert (
        "All user specified timing constraints are met." in timing
    ), f"Timing not confirmed: {name}"

    measured = {
        "total_luts": luts,
        "logic_luts": logic_luts,
        "flip_flops": ff,
        "bram36_equivalents": bram36,
        "dsp": dsp,
        "wns_ns": wns,
        "tns_ns": tns,
        "whs_ns": whs,
        "ths_ns": ths,
        "drc_violations": drc_count,
        "drc_rules": drc_rules,
    }

    prediction = {
        "luts": spec["predicted_luts"],
        "bram36_equivalents": spec["predicted_bram36"],
    }

    errors = {
        "luts": round(
            100 * (luts - prediction["luts"])
            / prediction["luts"], 2
        ),
        "bram36": round(
            100 * (bram36 - prediction["bram36_equivalents"])
            / prediction["bram36_equivalents"], 2
        ),
    }

    results[label] = {
        "configuration": name,
        "cores": spec["cores"],
        "cache_kib_per_core": spec["cache_kib"],
        "target_clock_mhz": 50,
        "measured": measured,
        "prediction": prediction,
        "error_percent": errors,
        "evidence": {
            key + "_sha256": digest(path)
            for key, path in paths.items()
        },
    }

    print(f"\n===== {label.upper()} =====")
    print("LUTs:", luts)
    print("FFs:", ff)
    print("BRAM36 equivalents:", bram36)
    print("DSP:", dsp)
    print("WNS:", wns, "ns")
    print("WHS:", whs, "ns")
    print("DRC warnings/violations:", drc_count)
    print("DRC rules:", drc_rules)
    print("LUT prediction error:", errors["luts"], "%")
    print("BRAM prediction error:", errors["bram36"], "%")

output = (
    repo / "results/optimizer/unified_search_nexys"
    / "vivado_routed_measurements.json"
)

data = {
    "target_fpga": "xc7a200tsbg484-1",
    "tool": "Vivado 2022.2",
    "measurement_stage": "fully_routed_implementation",
    "physical_fpga_tested": False,
    "full_model_inference_tested": False,
    "configurations": results,
}

content = json.dumps(data, indent=2) + "\n"

if output.exists() and output.read_text() != content:
    raise SystemExit(
        f"STOP: Existing measurement differs: {output}"
    )

output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(content)

print("\nPASS: Vivado measurements collected")
print("SAVED:", output)

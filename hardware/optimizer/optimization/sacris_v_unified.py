"""Measured SaCriS-V 128 KiB selection; small Q3_K x Q8_K only."""

import argparse
import csv
import io
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

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

OBJECTIVES = ("performance", "resources", "balanced")


def metric(text, name):
    found = re.search(
        rf"^{re.escape(name)}\s*:\s*(\S+)",
        text,
        re.MULTILINE,
    )
    if not found:
        raise ValueError(f"Missing benchmark metric {name}")
    return found.group(1)


def measure(prefix, variant):
    path = (
        ROOT
        / "results/optimizer/sacris_v_measurements"
        / f"{prefix}_{variant}.log"
    )

    text = path.read_text()

    if "Simulation ended by END signal" not in text:
        raise ValueError(f"Incomplete benchmark: {path}")

    blocks = int(metric(text, "Blocks"))
    repeats = int(metric(text, "Repeats"))

    if (blocks, repeats) != (32, 10):
        raise ValueError(f"Unexpected workload: {path}")

    cycles = int(metric(text, "Total cycles"))

    if cycles <= 0:
        raise ValueError(f"Invalid cycle count: {path}")

    return cycles, metric(text, "Checksum bits")


def rank():
    path = (
        ROOT
        / "results/optimizer/sacris_v_synthesis"
        / "resource_matrix_128k.csv"
    )

    with path.open(newline="") as handle:
        source_rows = list(csv.DictReader(handle))

    if len(source_rows) != len(CONFIGS):
        raise ValueError("Expected four synthesis measurements")

    resources = {
        row["configuration"]: row
        for row in source_rows
    }

    if set(resources) != set(CONFIGS):
        raise ValueError("Resource configurations mismatch")

    rows = []
    checksums = set()

    for config, prefix in CONFIGS.items():
        normal, check_a = measure(prefix, "small")
        dma, check_b = measure(prefix, "small_spad")

        if check_a != check_b:
            raise ValueError(f"Checksum mismatch: {config}")

        checksums.add(check_a)

        used = {
            key: int(resources[config][key])
            for key in CAPACITY
        }

        if any(
            not 0 < used[key] <= CAPACITY[key]
            for key in CAPACITY
        ):
            raise ValueError(f"Resource capacity exceeded: {config}")

        pressure = sum(
            WEIGHTS[key] * used[key] / CAPACITY[key]
            for key in CAPACITY
        )

        rows.append({
            "configuration": config,
            "small_cycles": normal,
            "small_dma_cycles": dma,
            "mean_cycles": (normal + dma) / 2,
            "checksum": check_a,
            "resource_pressure": pressure,
            **used,
        })

    if len(checksums) != 1:
        raise ValueError("Cross-configuration checksum mismatch")

    best_cycles = min(r["mean_cycles"] for r in rows)
    best_resources = min(r["resource_pressure"] for r in rows)

    for row in rows:
        row["balanced_score"] = (
            0.5 * row["mean_cycles"] / best_cycles
            + 0.5 * row["resource_pressure"] / best_resources
        )

    return rows


def save_report(path, content):
    if path.exists():
        if path.read_text() != content:
            raise FileExistsError(
                f"Existing report differs; preserving {path}"
            )
        return

    path.write_text(content)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)

    parser.add_argument(
        "--objective",
        choices=("all", *OBJECTIVES),
        default="all",
    )

    parser.add_argument(
        "--output-dir",
        type=Path,
        default=(
            ROOT / "results/optimizer/unified_sacris_v_128k"
        ),
    )

    args = parser.parse_args(argv)
    rows = rank()

    selected = {
        "performance": min(
            rows,
            key=lambda r: (
                r["mean_cycles"],
                r["resource_pressure"],
            ),
        )["configuration"],

        "resources": min(
            rows,
            key=lambda r: (
                r["resource_pressure"],
                r["mean_cycles"],
            ),
        )["configuration"],

        "balanced": min(
            rows,
            key=lambda r: (
                r["balanced_score"],
                r["resource_pressure"],
            ),
        )["configuration"],
    }

    report = {
        "processor": "sacris-v",
        "scope": "128KiB small and small-spad only",
        "evidence": (
            "Verilator RTL cycles plus "
            "Vivado post-synthesis resources"
        ),
        "timing_closed": False,
        "full_model_inference": False,
        "resource_weights": WEIGHTS,
        "balanced_weights": {
            "performance": 0.5,
            "resources": 0.5,
        },
        "selection": selected,
        "candidates": rows,
    }

    output = args.output_dir.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)

    save_report(
        output / "selection.json",
        json.dumps(report, indent=2) + "\n",
    )

    lines = [
        "# Unified SaCriS-V Selection",
        "",
    ]

    for objective, config in selected.items():
        lines.append(f"- {objective}: `{config}`")

    lines.extend([
        "",
        "| Configuration | Mean cycles | LUTs | FFs | BRAM36 | DSPs | Balanced score |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ])

    for r in sorted(rows, key=lambda x: x["balanced_score"]):
        lines.append(
            f"| {r['configuration']} "
            f"| {r['mean_cycles']:,.0f} "
            f"| {r['luts']} "
            f"| {r['ffs']} "
            f"| {r['bram36']} "
            f"| {r['dsps']} "
            f"| {r['balanced_score']:.4f} |"
        )

    lines.extend([
        "",
        "## Limitations",
        "",
        "Recommendations apply only to the 128 KiB small workloads.",
        "50 MHz timing closure, streaming, and full-model inference",
        "have not been established.",
        "",
    ])

    save_report(
        output / "selection.md",
        "\n".join(lines),
    )

    csv_buffer = io.StringIO(newline="")
    writer = csv.DictWriter(
        csv_buffer,
        fieldnames=list(rows[0]),
        lineterminator="\n",
    )
    writer.writeheader()
    writer.writerows(rows)

    save_report(
        output / "comparisons.csv",
        csv_buffer.getvalue(),
    )

    requested = (
        OBJECTIVES
        if args.objective == "all"
        else (args.objective,)
    )

    for objective in requested:
        print(f"{objective:11} -> {selected[objective]}")

    print(f"Saved: {output}")


if __name__ == "__main__":
    main()

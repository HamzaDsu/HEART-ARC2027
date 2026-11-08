"""SaCriS-V configuration feasibility screening.

Uses actual SaCriS-V Scala configuration declarations.
Does not claim measured performance or FPGA resources.
"""

import argparse
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

CONFIG_PATH = (
    ROOT / "sacris-v/src/main/scala/sacris_v/Configs.scala"
)
PARAMS_PATH = (
    ROOT / "sacris-v/src/main/scala/sacris_v/TopParams.scala"
)

CONFIGS = {
    "IMF_SLM": {
        "base": "IMF",
        "dual_issue": False,
        "fpu": True,
        "bitmanip": False,
        "tmr": False,
    },
    "DualIMFB_SLM": {
        "base": "DualIMFB",
        "dual_issue": True,
        "fpu": True,
        "bitmanip": True,
        "tmr": False,
    },
    "IMB_SLM": {
        "base": "IMB",
        "dual_issue": False,
        "fpu": False,
        "bitmanip": True,
        "tmr": False,
    },
    "IMF_SLM_128K": {
        "base": "IMF",
        "dual_issue": False,
        "fpu": True,
        "bitmanip": False,
        "tmr": False,
    },
    "IMF_TMR_SLM": {
        "base": "I_TMR_B3_F",
        "dual_issue": False,
        "fpu": True,
        "bitmanip": True,
        "tmr": True,
    },
}

VARIANTS = {
    "small": "dmem",
    "small-spad": "dma",
    "stream": "dmem",
    "stream-spad": "dma",
}


def default_words(source, field):
    match = re.search(
        rf"\b{field}\s*:\s*Int\s*=\s*(\d+)",
        source,
    )
    if not match:
        raise ValueError(f"Missing default: {field}")
    return int(match.group(1))


def read_config(name, metadata, source, defaults):
    pattern = (
        rf"\blazy val\s+{re.escape(name)}"
        rf"\s*:\s*TopParams\s*=\s*"
        rf"(\w+)\.copy\(([^)]*)\)"
    )
    match = re.search(pattern, source)

    if not match:
        raise ValueError(f"Config missing or changed: {name}")

    base, body = match.groups()

    if base != metadata["base"]:
        raise ValueError(f"Unexpected base for {name}: {base}")

    overrides = dict(
        (key, int(value))
        for key, value in re.findall(
            r"\b(dmemSize|dmaMemSize)\s*=\s*(\d+)",
            body,
        )
    )

    if not re.search(r"\bwithDmaMem\s*=\s*true", body):
        raise ValueError(f"DMA not explicitly enabled: {name}")

    dmem_words = overrides.get(
        "dmemSize", defaults["dmemSize"]
    )
    dma_words = overrides.get(
        "dmaMemSize", defaults["dmaMemSize"]
    )

    return {
        "name": name,
        "dmem_kib": dmem_words * 4 // 1024,
        "dma_kib": dma_words * 4 // 1024,
        **{
            key: value
            for key, value in metadata.items()
            if key != "base"
        },
    }


def screen(config, variant):
    memory = VARIANTS[variant]
    capacity = (
        config["dmem_kib"]
        if memory == "dmem"
        else config["dma_kib"]
    )

    # Benchmark README confirms stream variants
    # require the 1 MiB configurations.
    required = 1024 if variant.startswith("stream") else 128

    return {
        "configuration": config["name"],
        "variant": variant,
        "memory_type": memory,
        "available_kib": capacity,
        "required_class_kib": required,
        "feasible": capacity >= required,
        "fpu": config["fpu"],
        "dual_issue": config["dual_issue"],
        "bitmanip": config["bitmanip"],
        "tmr": config["tmr"],
        "validation": "source_and_readme_feasibility_only",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output-dir",
        default=str(
            ROOT / "results/optimizer/sacris_v_screening"
        ),
    )
    args = parser.parse_args()

    output = Path(args.output_dir)
    source = CONFIG_PATH.read_text()
    params = PARAMS_PATH.read_text()

    defaults = {
        key: default_words(params, key)
        for key in ("dmemSize", "dmaMemSize")
    }

    configs = [
        read_config(name, metadata, source, defaults)
        for name, metadata in CONFIGS.items()
    ]

    rows = [
        screen(config, variant)
        for config in configs
        for variant in VARIANTS
    ]

    feasible = sum(row["feasible"] for row in rows)

    output.mkdir(parents=True, exist_ok=True)

    report = {
        "processor": "SaCriS-V",
        "architecture": "RV32",
        "benchmark": "Q3_K_x_Q8_K",
        "status": "feasibility_screening_only",
        "configurations": configs,
        "candidates": rows,
        "feasible_pairs": feasible,
        "performance_ranking": None,
        "resource_ranking": None,
        "limitations": [
            "No SaCriS-V cycle measurements supplied.",
            "No SaCriS-V Vivado utilization measurements supplied.",
            "Memory feasibility does not prove successful execution.",
            "The IMB variant requires a compatible soft-float toolchain.",
            "No optimized hardware winner is claimed.",
        ],
    }

    (output / "screening.json").write_text(
        json.dumps(report, indent=2) + "\n"
    )

    with (output / "screening.csv").open(
        "w", newline=""
    ) as handle:
        writer = csv.DictWriter(
            handle, fieldnames=rows[0].keys()
        )
        writer.writeheader()
        writer.writerows(rows)

    print("===== SACRIS-V FEASIBILITY =====")

    for config in configs:
        valid = [
            row["variant"]
            for row in rows
            if row["configuration"] == config["name"]
            and row["feasible"]
        ]

        print(
            f"{config['name']:20s} "
            f"DMEM={config['dmem_kib']:4d} KiB "
            f"DMA={config['dma_kib']:4d} KiB "
            f"valid={','.join(valid)}"
        )

    print()
    print("Configurations:", len(configs))
    print("Candidate pairs:", len(rows))
    print("Feasible pairs:", feasible)

    assert len(configs) == 5
    assert len(rows) == 20
    assert feasible == 18

    print("PASS: SaCriS-V feasibility screening")
    print("Measurements required for optimization ranking")
    print("Saved:", output)


if __name__ == "__main__":
    main()

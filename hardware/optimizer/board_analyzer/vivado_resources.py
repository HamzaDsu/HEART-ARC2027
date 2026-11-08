import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path

from .auto_discovery import discover_boards, resolve_board


TCL = Path(__file__).with_name("vivado_resources.tcl")


def discover_resources(board_name, chipyard="~/chipyard"):
    board = resolve_board(
        board_name, discover_boards(chipyard)
    )

    if board["status"] != "discovered":
        return board

    part = board.get("fpga_part")
    if not part:
        raise ValueError("FPGA part number is unavailable")

    vivado = shutil.which("vivado")
    if not vivado:
        raise RuntimeError("Vivado not found in PATH")

    command = [
        vivado, "-mode", "batch",
        "-nolog", "-nojournal",
        "-source", str(TCL),
        "-tclargs", part,
    ]

    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=180,
        check=False,
    )

    fields = dict(re.findall(
        r"^(STATUS|FPGA_PART|TOTAL_SITES|SLICE_SITES|"
        r"RAMB36_SITES|RAMB18_SITES|DSP_SITES)=(.*)$",
        result.stdout,
        flags=re.MULTILINE,
    ))

    if fields.get("STATUS") == "DEVICE_NOT_INSTALLED":
        return {
            "status": "vivado_device_not_installed",
            "board": board,
            "fpga_part": part,
            "message": (
                "Board discovered in Chipyard, but this FPGA "
                "device is not installed in Vivado."
            ),
            "recommendation": (
                "Install device support or select another "
                "board supported by the local Vivado installation."
            ),
        }

    if result.returncode != 0 or fields.get("STATUS") != "DEVICE_FOUND":
        raise RuntimeError(
            "Vivado resource discovery failed:\n"
            + "\n".join(result.stdout.splitlines()[-15:])
        )

    slices = int(fields["SLICE_SITES"])
    part_lower = part.lower()

    if part_lower.startswith("xc7"):
        luts_per_slice, ffs_per_slice = 4, 8
    elif part_lower.startswith(("xcvu", "xcku", "xczu")):
        luts_per_slice, ffs_per_slice = 8, 16
    else:
        luts_per_slice, ffs_per_slice = None, None

    return {
        "status": "resources_discovered",
        "board": board,
        "resources": {
            "slice_sites": slices,
            "luts": slices * luts_per_slice
                if luts_per_slice else None,
            "flip_flops": slices * ffs_per_slice
                if ffs_per_slice else None,
            "bram36_sites": int(fields["RAMB36_SITES"]),
            "bram18_sites": int(fields["RAMB18_SITES"]),
            "dsp_sites": int(fields["DSP_SITES"]),
        },
        "source": "Vivado device database",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--board", required=True)
    parser.add_argument("--chipyard", default="~/chipyard")
    parser.add_argument("--output")
    args = parser.parse_args()

    profile = discover_resources(args.board, args.chipyard)
    output = json.dumps(profile, indent=2)

    print(output)

    if args.output:
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(output + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

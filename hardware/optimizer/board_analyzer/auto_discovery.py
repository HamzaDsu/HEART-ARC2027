import argparse
import json
import re
from pathlib import Path


def normalize(name):
    value = re.sub(r"[^a-z0-9]", "", name.lower())
    return re.sub(r"(?<=\d)t$", "", value)


def tcl_field(text, key):
    pattern = rf"(?m)^\s*set\s+{re.escape(key)}\s+\{{([^}}]+)\}}"
    match = re.search(pattern, text)
    return match.group(1) if match else None


def discover_boards(chipyard_path):
    root = Path(chipyard_path).expanduser().resolve()
    makefile = root / "fpga/Makefile"

    if not makefile.is_file():
        raise FileNotFoundError(f"Missing: {makefile}")

    entries = []
    current = None

    for line in makefile.read_text().splitlines():
        match = re.match(
            r"\s*ifeq\s*\(\s*\$\(SUB_PROJECT\)\s*,\s*([\w-]+)\s*\)",
            line,
        )

        if match:
            current = {"subproject": match.group(1)}
            continue

        if current is None:
            continue

        if line.strip() == "endif":
            entries.append(current)
            current = None
            continue

        setting = re.match(r"\s*([A-Z_]+)\s*\?=\s*([^#]+)", line)
        if setting:
            current[setting.group(1)] = setting.group(2).strip()

    boards = []

    for entry in entries:
        code = entry.get("BOARD")
        if not code:
            continue

        board_file = (
            root / "fpga/fpga-shells/xilinx"
            / code / "tcl/board.tcl"
        )

        text = board_file.read_text() if board_file.is_file() else ""

        boards.append({
            "subproject": entry["subproject"],
            "board_name": tcl_field(text, "name") or code,
            "fpga_part": tcl_field(text, "part_fpga"),
            "board_part": tcl_field(text, "part_board"),
            "default_config": entry.get("CONFIG"),
            "config_package": entry.get("CONFIG_PACKAGE"),
            "harness": entry.get("MODEL"),
            "board_file_found": board_file.is_file(),
        })

    return boards


def resolve_board(name, boards):
    requested = normalize(name)

    for board in boards:
        identifiers = [
            board["subproject"],
            board["board_name"],
        ]

        if requested in {normalize(x) for x in identifiers}:
            return {"status": "discovered", **board}

    return {
        "status": "not_registered_in_chipyard",
        "requested_board": name,
        "message": "External board discovery is required.",
    }


def main():
    parser = argparse.ArgumentParser(
        description="Discover Chipyard FPGA board configurations"
    )
    parser.add_argument("--chipyard", default="~/chipyard")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--board")
    group.add_argument("--list", action="store_true")

    args = parser.parse_args()
    boards = discover_boards(args.chipyard)

    result = (
        boards if args.list
        else resolve_board(args.board, boards)
    )

    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

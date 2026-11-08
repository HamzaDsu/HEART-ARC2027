#!/usr/bin/env python3
"""Read-only GGUF v2/v3 comparison: metadata, tensor layouts and tensor payloads.

Uses only Python's standard library.  SHA-256 comparisons ignore differing GGUF
header sizes and therefore distinguish metadata-only from quantized-weight changes.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import struct
from pathlib import Path

# GGUF metadata value types. See gguf.h.
FORMATS = {
    0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i",
    6: "<f", 7: "<?", 10: "<Q", 11: "<q", 12: "<d",
}
TYPE_NAMES = {
    0: "uint8", 1: "int8", 2: "uint16", 3: "int16", 4: "uint32",
    5: "int32", 6: "float32", 7: "bool", 8: "string", 9: "array",
    10: "uint64", 11: "int64", 12: "float64",
}
MAX_STRING = 50_000_000
MAX_ENTRIES = 2_000_000


def take(f, n):
    if n < 0 or n > MAX_STRING:
        raise ValueError(f"Unreasonable field size: {n}")
    data = f.read(n)
    if len(data) != n:
        raise ValueError("Truncated GGUF")
    return data


def number(f, fmt):
    return struct.unpack(fmt, take(f, struct.calcsize(fmt)))[0]


def string(f):
    n = number(f, "<Q")
    return take(f, n).decode("utf-8", errors="replace")


def value(f, kind):
    if kind in FORMATS:
        return number(f, FORMATS[kind])
    if kind == 8:
        return string(f)
    if kind == 9:
        item_kind = number(f, "<I")
        count = number(f, "<Q")
        if count > MAX_ENTRIES or item_kind == 9:
            raise ValueError(f"Unsupported array metadata: type {item_kind}, count {count}")
        start = f.tell()
        if item_kind in FORMATS:
            size = struct.calcsize(FORMATS[item_kind]) * count
            f.seek(size, os.SEEK_CUR)
        elif item_kind == 8:
            for _ in range(count):
                length = number(f, "<Q")
                if length > MAX_STRING:
                    raise ValueError("Overly large string in array")
                f.seek(length, os.SEEK_CUR)
        else:
            raise ValueError(f"Unsupported GGUF array item type: {item_kind}")
        stop = f.tell()
        f.seek(start)
        digest = hashlib.sha256()
        remaining = stop - start
        while remaining:
            part = take(f, min(1024 * 1024, remaining))
            digest.update(part)
            remaining -= len(part)
        return f"array<{TYPE_NAMES.get(item_kind, item_kind)}>({count}) sha256:{digest.hexdigest()}"
    raise ValueError(f"Unsupported GGUF metadata type: {kind}")


def parse(path):
    with path.open("rb") as f:
        if take(f, 4) != b"GGUF":
            raise ValueError(f"Not GGUF: {path}")
        version = number(f, "<I")
        if version not in (2, 3):
            raise ValueError(f"Expected GGUF v2 or v3; found v{version}")
        ntensors, nkeys = number(f, "<Q"), number(f, "<Q")
        if ntensors > MAX_ENTRIES or nkeys > MAX_ENTRIES:
            raise ValueError("Excessive tensor or metadata count")
        metadata = {}
        for _ in range(nkeys):
            key = string(f)
            kind = number(f, "<I")
            metadata[key] = (TYPE_NAMES.get(kind, str(kind)), value(f, kind))
        tensors = {}
        for _ in range(ntensors):
            name = string(f)
            ndims = number(f, "<I")
            if ndims > 8:
                raise ValueError("Unreasonable dimension count")
            dims = tuple(number(f, "<Q") for _ in range(ndims))
            ggml_type = number(f, "<I")
            offset = number(f, "<Q")
            tensors[name] = {"dims": dims, "type": ggml_type, "offset": offset}
        align = metadata.get("general.alignment", (None, 32))[1]
        if not isinstance(align, int) or align < 1 or align > 65536:
            raise ValueError(f"Unexpected alignment {align}")
        data_start = ((f.tell() + align - 1) // align) * align
        file_size = path.stat().st_size
        ordered = sorted(tensors.items(), key=lambda item: item[1]["offset"])
        for i, (name, desc) in enumerate(ordered):
            begin = data_start + desc["offset"]
            end = data_start + ordered[i + 1][1]["offset"] if i + 1 < len(ordered) else file_size
            if not (data_start <= begin <= end <= file_size):
                raise ValueError(f"Invalid tensor span: {name}: {begin}, {end}, {file_size}")
            desc["begin"], desc["length"] = begin, end - begin
        return {"path": path, "version": version, "size": file_size,
                "nkeys": nkeys, "ntensors": ntensors, "data_start": data_start,
                "metadata": metadata, "tensors": tensors}


def sha_part(f, start, length):
    f.seek(start)
    h = hashlib.sha256()
    while length:
        b = f.read(min(length, 4 * 1024 * 1024))
        if not b:
            raise ValueError("Unexpected end of tensor data")
        h.update(b)
        length -= len(b)
    return h.hexdigest()


def trunc(val, limit=180):
    s = repr(val)
    return s if len(s) <= limit else s[:limit] + "..."


def compare(a, b, max_diffs):
    print("=== GGUF files ===")
    for label, info in (("ORIGINAL", a), ("REGENERATED", b)):
        print(f"{label}: {info['path']}")
        print(f"  version={info['version']} bytes={info['size']} keys={info['nkeys']} "
              f"tensors={info['ntensors']} tensor_data_start={info['data_start']}")
    print("\n=== Metadata differences ===")
    meta_diffs = []
    for key in sorted(a["metadata"].keys() | b["metadata"].keys()):
        left, right = a["metadata"].get(key), b["metadata"].get(key)
        if left != right:
            meta_diffs.append((key, left, right))
    print(f"Changed metadata keys: {len(meta_diffs)}")
    for key, left, right in meta_diffs[:max_diffs]:
        print(f"  {key}\n    original:    {trunc(left)}\n    regenerated: {trunc(right)}")
    if len(meta_diffs) > max_diffs:
        print(f"  ... {len(meta_diffs) - max_diffs} more metadata changes")
    print("\n=== Tensor layout/payload comparison ===")
    a_names, b_names = set(a["tensors"]), set(b["tensors"])
    print(f"Only original tensors: {len(a_names - b_names)}; only regenerated: {len(b_names - a_names)}")
    if a_names - b_names:
        print("  Original-only examples:", sorted(a_names - b_names)[:max_diffs])
    if b_names - a_names:
        print("  Regenerated-only examples:", sorted(b_names - a_names)[:max_diffs])
    changed, layouts, compared, matched = [], [], 0, 0
    with a["path"].open("rb") as fa, b["path"].open("rb") as fb:
        for name in sorted(a_names & b_names):
            ta, tb = a["tensors"][name], b["tensors"][name]
            la = (ta["dims"], ta["type"], ta["length"])
            lb = (tb["dims"], tb["type"], tb["length"])
            if la != lb:
                layouts.append((name, la, lb))
                continue
            compared += 1
            ha = sha_part(fa, ta["begin"], ta["length"])
            hb = sha_part(fb, tb["begin"], tb["length"])
            if ha == hb:
                matched += 1
            else:
                changed.append((name, ha, hb))
    print(f"Same tensor name/layout compared: {compared}")
    print(f"Identical tensor bytes: {matched}; DIFFERENT tensor bytes: {len(changed)}")
    print(f"Changed tensor layouts: {len(layouts)}")
    for name, left, right in layouts[:max_diffs]:
        print(f"  LAYOUT {name}: {left} vs {right}")
    for name, ha, hb in changed[:max_diffs]:
        print(f"  DATA {name}: original={ha[:20]} regenerated={hb[:20]}")
    if len(changed) > max_diffs:
        print(f"  ... {len(changed)-max_diffs} more tensors differ")
    if not (a_names ^ b_names or layouts or changed):
        print("\nRESULT: TENSOR DATA IDENTICAL (hash mismatch is metadata/header/padding only).")
    else:
        print("\nRESULT: TENSOR DATA DIFFERS; quantization is NOT byte-for-byte reproduced.")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("original", type=Path)
    p.add_argument("regenerated", type=Path)
    p.add_argument("--max-diffs", type=int, default=15)
    args = p.parse_args()
    compare(parse(args.original), parse(args.regenerated), max(1, args.max_diffs))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Create a compact family matrix by vectors, labels, and checksum state."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
from pzu_common import parse_intel_hex, extract_ascii_runs, first_ljmps, classify_family


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("files", nargs="+", type=Path)
    p.add_argument("--out", type=Path, default=Path("docs/firmware_family_matrix.csv"))
    args = p.parse_args()

    rows = []
    for path in args.files:
        img = parse_intel_hex(path)
        strings = extract_ascii_runs(img.memory, min_len=5)
        vectors = first_ljmps(img.memory, count=8)
        row = {
            "file": path.name,
            "valid": img.valid,
            "family": classify_family(vectors, (s for _, s in strings[:50])),
            "addr_range": f"0x{img.addr_min:04X}-0x{img.addr_max:04X}" if img.addr_min is not None else "",
            "vectors": ",".join(f"{target:04X}" for _, target in vectors if target is not None),
            "labels": " | ".join(s for _, s in strings[:6]),
            "errors": "; ".join(img.errors[:3]),
        }
        rows.append(row)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]) if rows else [])
        if rows:
            writer.writeheader()
            writer.writerows(rows)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

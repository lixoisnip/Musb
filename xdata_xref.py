#!/usr/bin/env python3
"""Extract direct 8051 XDATA references from PPKP *.PZU firmware images.

The analysis is static and conservative. It scans for MOV DPTR,#imm16 (0x90)
and classifies nearby instructions such as MOVX A,@DPTR (0xE0), MOVX @DPTR,A
(0xF0), MOVC A,@A+DPTR (0x93), INC DPTR (0xA3), and LCALL (0x12).
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
from typing import Dict, List, Optional
from pzu_common import parse_intel_hex, memory_to_bytes


def op_name(op: int) -> str:
    return {
        0xE0: "MOVX A,@DPTR (read XDATA)",
        0xF0: "MOVX @DPTR,A (write XDATA)",
        0x93: "MOVC A,@A+DPTR (read CODE table)",
        0xA3: "INC DPTR",
        0x12: "LCALL",
        0x02: "LJMP",
        0x74: "MOV A,#imm",
        0x75: "MOV direct,#imm",
        0xE4: "CLR A",
        0x04: "INC A",
    }.get(op, f"OP 0x{op:02X}")


def classify_following(window: bytes) -> str:
    # Look only near DPTR load, because 8051 code often loads DPTR immediately
    # before MOVX/MOVC or before a helper call.
    if 0xE0 in window[:8]:
        return "xdata_read"
    if 0xF0 in window[:8]:
        return "xdata_write"
    if 0x93 in window[:8]:
        return "code_table_read"
    if 0x12 in window[:10]:
        return "dptr_passed_to_call"
    return "dptr_load_unknown"


def scan_file(path: Path, start: int = 0x4000, end: int = 0xC000) -> List[dict]:
    img = parse_intel_hex(path)
    mem = memory_to_bytes(img.memory, 0, 0x10000)
    rows: List[dict] = []
    for pc in range(start, min(end, 0xFFFD)):
        if mem[pc] != 0x90:  # MOV DPTR,#imm16
            continue
        target = (mem[pc + 1] << 8) | mem[pc + 2]
        window = bytes(mem[pc + 3 : pc + 15])
        following_ops = " ".join(f"{b:02X}" for b in window[:10])
        following_names = " | ".join(op_name(b) for b in window[:6])
        rows.append({
            "file": path.name,
            "pc": f"0x{pc:04X}",
            "dptr_target": f"0x{target:04X}",
            "kind": classify_following(window),
            "next_bytes": following_ops,
            "next_ops": following_names,
        })
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, default=Path("docs/xdata_xref.csv"))
    parser.add_argument("--start", type=lambda s: int(s, 0), default=0x4000)
    parser.add_argument("--end", type=lambda s: int(s, 0), default=0xC000)
    args = parser.parse_args()

    rows: List[dict] = []
    for path in args.files:
        rows.extend(scan_file(path, args.start, args.end))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    fields = ["file", "pc", "dptr_target", "kind", "next_bytes", "next_ops"]
    with args.out.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    print(f"wrote {args.out} with {len(rows)} DPTR references")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

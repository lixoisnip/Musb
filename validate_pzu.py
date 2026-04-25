#!/usr/bin/env python3
"""Validate PPKP *.PZU Intel HEX files and print a compact report."""

from __future__ import annotations

import argparse
from pathlib import Path
from pzu_common import parse_intel_hex, extract_ascii_runs, first_ljmps, classify_family


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--strings", type=int, default=8, help="number of ASCII strings to print")
    args = parser.parse_args()

    exit_code = 0
    for path in args.files:
        img = parse_intel_hex(path)
        if not img.valid:
            exit_code = 1
        strings = extract_ascii_runs(img.memory, min_len=5)
        vectors = first_ljmps(img.memory)
        print(f"\n== {path} ==")
        print(f"valid: {img.valid}")
        print(f"data_records: {img.data_records}")
        print(f"data_bytes: {img.data_bytes}")
        if img.addr_min is not None:
            print(f"addr_range: 0x{img.addr_min:04X}-0x{img.addr_max:04X}")
        print(f"eof_records: {img.eof_count}")
        print("family:", classify_family(vectors, (s for _, s in strings[:50])))
        print("vectors:")
        for addr, target in vectors:
            print(f"  0x{addr:04X}: {'LJMP 0x%04X' % target if target is not None else 'no LJMP'}")
        if img.errors:
            print("errors:")
            for err in img.errors[:20]:
                print("  -", err)
        print("strings:")
        for addr, text in strings[: args.strings]:
            print(f"  0x{addr:04X}: {text}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())

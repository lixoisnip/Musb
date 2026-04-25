#!/usr/bin/env python3
"""Generate docs/firmware_manifest.json and docs/firmware_inventory.csv for *.PZU."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from pzu_common import manifest_entry


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default=".", type=Path)
    parser.add_argument("--json-out", default="docs/firmware_manifest.json", type=Path)
    parser.add_argument("--csv-out", default="docs/firmware_inventory.csv", type=Path)
    args = parser.parse_args()

    paths = sorted(args.root.glob("*.PZU"))
    entries = [manifest_entry(path) for path in paths]

    args.json_out.parent.mkdir(parents=True, exist_ok=True)
    args.csv_out.parent.mkdir(parents=True, exist_ok=True)
    args.json_out.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    fields = [
        "filename", "path", "sha256", "valid", "data_records", "data_bytes",
        "addr_min", "addr_max", "eof_records", "family", "errors", "labels_preview"
    ]
    with args.csv_out.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for e in entries:
            row = {k: e.get(k) for k in fields}
            row["errors"] = "; ".join(e.get("errors", []))
            row["labels_preview"] = " | ".join(e.get("labels_preview", [])[:5])
            writer.writerow(row)

    print(f"wrote {args.json_out} and {args.csv_out} for {len(entries)} images")
    invalid = [e for e in entries if not e["valid"]]
    if invalid:
        print("invalid images:")
        for e in invalid:
            print(f"  - {e['filename']}: {e['errors'][:3]}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

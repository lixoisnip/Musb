#!/usr/bin/env python3
"""Common Intel HEX helpers for PPKP *.PZU reverse engineering.

This module intentionally performs read-only analysis. It does not patch or
rewrite firmware images.
"""

from __future__ import annotations

from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple
import hashlib
import json
import subprocess


@dataclass
class HexRecord:
    line_no: int
    count: int
    address: int
    rectype: int
    data: bytes
    checksum: int
    checksum_ok: bool
    raw: str


@dataclass
class HexImage:
    path: str
    records: List[HexRecord]
    memory: Dict[int, int]
    errors: List[str]

    @property
    def data_records(self) -> int:
        return sum(1 for r in self.records if r.rectype == 0x00)

    @property
    def data_bytes(self) -> int:
        return sum(r.count for r in self.records if r.rectype == 0x00)

    @property
    def eof_count(self) -> int:
        return sum(1 for r in self.records if r.rectype == 0x01)

    @property
    def addr_min(self) -> Optional[int]:
        return min(self.memory) if self.memory else None

    @property
    def addr_max(self) -> Optional[int]:
        return max(self.memory) if self.memory else None

    @property
    def valid(self) -> bool:
        return not self.errors


def parse_intel_hex(path: Path, *, stop_on_eof: bool = True) -> HexImage:
    records: List[HexRecord] = []
    memory: Dict[int, int] = {}
    errors: List[str] = []

    try:
        lines = path.read_text(encoding="ascii", errors="strict").splitlines()
    except UnicodeDecodeError as exc:
        return HexImage(str(path), [], {}, [f"{path}: ASCII decode error: {exc}"])

    seen_eof = False
    for line_no, raw in enumerate(lines, start=1):
        line = raw.strip()
        if not line:
            continue
        if seen_eof:
            errors.append(f"line {line_no}: data after EOF")
            continue
        if not line.startswith(":"):
            errors.append(f"line {line_no}: no ':' prefix")
            continue
        try:
            rec = bytes.fromhex(line[1:])
        except ValueError as exc:
            errors.append(f"line {line_no}: non-hex payload: {exc}")
            continue
        if len(rec) < 5:
            errors.append(f"line {line_no}: record too short")
            continue

        count = rec[0]
        expected_len = 5 + count
        if len(rec) != expected_len:
            errors.append(f"line {line_no}: length mismatch, expected {expected_len} bytes, got {len(rec)}")
            # Try to continue using available bytes.
        address = (rec[1] << 8) | rec[2]
        rectype = rec[3]
        data = rec[4:4 + count]
        checksum = rec[4 + count] if len(rec) > 4 + count else 0
        checksum_ok = (sum(rec) & 0xFF) == 0
        if not checksum_ok:
            errors.append(f"line {line_no}: bad checksum")

        record = HexRecord(line_no, count, address, rectype, data, checksum, checksum_ok, line)
        records.append(record)

        if rectype == 0x00:
            for offset, byte in enumerate(data):
                memory[address + offset] = byte
        elif rectype == 0x01:
            seen_eof = True
            if count != 0:
                errors.append(f"line {line_no}: EOF record has non-zero payload")
            if stop_on_eof:
                # Keep scanning only for non-empty trailing data via seen_eof logic.
                pass
        else:
            errors.append(f"line {line_no}: unsupported record type 0x{rectype:02X}")

    if not records:
        errors.append("empty or unreadable Intel HEX")
    if not seen_eof:
        errors.append("missing EOF record")

    return HexImage(str(path), records, memory, errors)


def memory_to_bytes(memory: Dict[int, int], start: int = 0x0000, end: int = 0x10000, fill: int = 0xFF) -> bytearray:
    out = bytearray([fill] * (end - start))
    for addr, value in memory.items():
        if start <= addr < end:
            out[addr - start] = value
    return out


def extract_ascii_runs(memory: Dict[int, int], min_len: int = 6) -> List[Tuple[int, str]]:
    runs: List[Tuple[int, str]] = []
    start: Optional[int] = None
    buf: List[str] = []

    last_addr: Optional[int] = None
    for addr in sorted(memory):
        byte = memory[addr]
        printable = 0x20 <= byte <= 0x7E
        contiguous = last_addr is None or addr == last_addr + 1
        if printable and contiguous:
            if start is None:
                start = addr
            buf.append(chr(byte))
        else:
            if start is not None and len(buf) >= min_len:
                runs.append((start, "".join(buf)))
            start = addr if printable else None
            buf = [chr(byte)] if printable else []
        last_addr = addr

    if start is not None and len(buf) >= min_len:
        runs.append((start, "".join(buf)))
    return runs


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def git_last_modified(path: Path) -> Optional[str]:
    try:
        result = subprocess.run(
            ["git", "log", "-1", "--follow", "--format=%cI", "--", str(path)],
            cwd=path.parent if path.parent != Path("") else Path("."),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    except Exception:
        return None
    value = result.stdout.strip()
    return value or None


def first_ljmps(memory: Dict[int, int], start: int = 0x4000, count: int = 8) -> List[Tuple[int, Optional[int]]]:
    """Return LJMP-style vectors near image start: (address, target_or_none)."""
    out: List[Tuple[int, Optional[int]]] = []
    addr = start
    for _ in range(count):
        op = memory.get(addr)
        if op == 0x02:
            target = (memory.get(addr + 1, 0) << 8) | memory.get(addr + 2, 0)
            out.append((addr, target))
        else:
            out.append((addr, None))
        addr += 8
    return out


def classify_family(vectors: List[Tuple[int, Optional[int]]], labels: Iterable[str]) -> str:
    targets = [target for _, target in vectors if target is not None]
    label_blob = " ".join(labels).lower()
    if any(t is not None and 0xB000 <= t <= 0xBFFF for t in targets) or "rtos" in label_blob:
        return "rtos/service branch"
    if {0x492E, 0x4954, 0x497A}.issubset(set(targets)):
        return "A03/A04-like branch"
    if {0x4933, 0x4959, 0x497F}.issubset(set(targets)):
        return "90CYE DKS/v2_1 shifted branch"
    if "90cye" in label_blob:
        return "90CYE branch, exact subfamily unknown"
    return "unknown"


def manifest_entry(path: Path) -> dict:
    img = parse_intel_hex(path)
    labels = [s for _, s in extract_ascii_runs(img.memory, min_len=5)[:50]]
    vectors = first_ljmps(img.memory)
    entry = {
        "path": str(path).replace("\\", "/"),
        "filename": path.name,
        "sha256": file_sha256(path),
        "data_records": img.data_records,
        "data_bytes": img.data_bytes,
        "addr_min": f"0x{img.addr_min:04X}" if img.addr_min is not None else None,
        "addr_max": f"0x{img.addr_max:04X}" if img.addr_max is not None else None,
        "eof_records": img.eof_count,
        "valid": img.valid,
        "errors": img.errors,
        "vectors": [
            {"address": f"0x{addr:04X}", "target": f"0x{target:04X}" if target is not None else None}
            for addr, target in vectors
        ],
        "family": classify_family(vectors, labels),
        "labels_preview": labels[:20],
    }
    return entry

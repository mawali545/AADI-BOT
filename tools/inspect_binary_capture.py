#!/usr/bin/env python3
"""Inspect captured Quotex Socket.IO binary attachments without inventing prices.

This is a forensic helper: it reports byte structure, printable strings, and
common compression/JSON signatures. It does not claim to decode unknown fields
as market prices.
"""
from __future__ import annotations

import gzip
import json
import sys
import zlib
from pathlib import Path


def inspect(path: Path) -> None:
    raw = path.read_bytes()
    print(f"FILE: {path}")
    print(f"SIZE: {len(raw)} bytes")
    print(f"FIRST_32_HEX: {raw[:32].hex()}")
    print(f"FIRST_128_HEX: {raw[:128].hex()}")
    printable = "".join(chr(b) if 32 <= b < 127 else "." for b in raw[:512])
    print(f"PRINTABLE_PREFIX: {printable!r}")

    strings = []
    current = bytearray()
    for byte in raw:
        if 32 <= byte < 127:
            current.append(byte)
        else:
            if len(current) >= 4:
                strings.append(current.decode("ascii", "replace"))
            current.clear()
    if len(current) >= 4:
        strings.append(current.decode("ascii", "replace"))
    print("ASCII_STRINGS:")
    for item in strings[:80]:
        print(f"  {item[:240]}")

    candidates = [("raw", raw)]
    # Socket.IO attachments can have a leading Engine.IO binary type byte.
    if raw[:1] in (b"\x04", b"\x00", b"\x01"):
        candidates.append(("without_first_byte", raw[1:]))

    for label, data in candidates:
        try:
            decoded = data.decode("utf-8")
            print(f"UTF8[{label}]: {decoded[:1000]!r}")
            try:
                parsed = json.loads(decoded)
                print(f"JSON[{label}]: valid {type(parsed).__name__}")
            except (ValueError, TypeError):
                pass
        except UnicodeDecodeError:
            pass

    for label, fn in (("gzip", gzip.decompress), ("zlib", zlib.decompress)):
        try:
            expanded = fn(raw)
            print(f"{label.upper()}_DECOMPRESSED: {len(expanded)} bytes")
            print(f"{label.upper()}_PREFIX_HEX: {expanded[:96].hex()}")
            try:
                txt = expanded.decode("utf-8")
                print(f"{label.upper()}_UTF8: {txt[:1000]!r}")
                try:
                    parsed = json.loads(txt)
                    print(f"{label.upper()}_JSON: valid {type(parsed).__name__}")
                except (ValueError, TypeError):
                    pass
            except UnicodeDecodeError:
                pass
        except (OSError, EOFError, zlib.error):
            pass


def main() -> int:
    paths = [Path(arg) for arg in sys.argv[1:]]
    if not paths:
        paths = sorted(Path(".").glob("aadi_binary_capture_*.bin"))
    if not paths:
        print("NO_CAPTURE_FILES: run tools/probe_curl_cffi_endpoint.py first")
        return 2
    for path in paths:
        if not path.is_file():
            print(f"MISSING_FILE: {path}")
            continue
        inspect(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

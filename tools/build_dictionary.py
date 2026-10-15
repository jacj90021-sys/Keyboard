#!/usr/bin/env python3
"""Build the PaperLime ``PLKD`` binary dictionary asset.

The Rust core memory-maps this file directly, so the layout is intentionally
simple and validated here before it is written:

    offset 0   : magic  b"PLKD"
    offset 4   : version u32 little-endian (currently 1)
    offset 8   : entry count u32 little-endian
    then, for each entry:
        key length   u16 little-endian (>= 1)
        value length u16 little-endian (>= 1)
        key bytes    (UTF-8, lowercase)
        value bytes  (ASCII decimal frequency)

Keys are stored in ascending UTF-8 byte order so the engine can binary-search
them. Duplicate words collapse to the single highest frequency.

Usage:
    python3 tools/build_dictionary.py
    python3 tools/build_dictionary.py --input tools/wordlist.txt --output app/src/main/assets/keyboard_dictionary.bin
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

MAGIC = b"PLKD"
VERSION = 1
DEFAULT_INPUT = Path(__file__).resolve().parent / "wordlist.txt"
DEFAULT_OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "app"
    / "src"
    / "main"
    / "assets"
    / "keyboard_dictionary.bin"
)


def parse_wordlist(path: Path) -> list[tuple[bytes, bytes]]:
    """Return sorted (key, value) byte pairs from a ``word frequency`` list."""
    best: dict[bytes, int] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2:
            raise SystemExit(f"{path}:{line_number}: expected '<word> <frequency>'")
        word, frequency_text = parts
        word = word.lower()
        if not word or not all(ch.isalpha() or ch == "'" for ch in word):
            raise SystemExit(f"{path}:{line_number}: word must be letters/apostrophes: {word!r}")
        if word.startswith("'") or word.endswith("'"):
            raise SystemExit(f"{path}:{line_number}: word must not start/end with apostrophe: {word!r}")
        try:
            frequency = int(frequency_text)
        except ValueError:
            raise SystemExit(f"{path}:{line_number}: bad frequency: {frequency_text!r}")
        if frequency < 0:
            raise SystemExit(f"{path}:{line_number}: frequency must be >= 0")
        key = word.encode("utf-8")
        best[key] = max(best.get(key, 0), frequency)

    entries = [(key, str(best[key]).encode("ascii")) for key in sorted(best)]
    return entries


def encode(entries: list[tuple[bytes, bytes]]) -> bytes:
    if len(entries) > 0xFFFFFFFF:
        raise SystemExit("too many entries")

    buffer = bytearray()
    buffer += MAGIC
    buffer += struct.pack("<I", VERSION)
    buffer += struct.pack("<I", len(entries))

    previous: bytes | None = None
    for key, value in entries:
        if not key:
            raise SystemExit("empty key is not allowed")
        if previous is not None and key <= previous:
            raise SystemExit(f"keys are not strictly ascending at {key!r}")
        if len(key) > 0xFFFF or len(value) > 0xFFFF:
            raise SystemExit(f"entry too large: {key!r}")
        previous = key
        buffer += struct.pack("<HH", len(key), len(value))
        buffer += key
        buffer += value

    return bytes(buffer)


def decode_and_validate(data: bytes, expected: list[tuple[bytes, bytes]]) -> None:
    """Independent read-back so a corrupt writer can never ship silently."""
    if data[:4] != MAGIC:
        raise SystemExit("validation failed: bad magic")
    version = struct.unpack_from("<I", data, 4)[0]
    if version != VERSION:
        raise SystemExit(f"validation failed: version {version}")
    count = struct.unpack_from("<I", data, 8)[0]
    if count != len(expected):
        raise SystemExit("validation failed: count mismatch")

    cursor = 12
    last_key = b""
    for key, value in expected:
        key_len, value_len = struct.unpack_from("<HH", data, cursor)
        cursor += 4
        if key_len != len(key) or value_len != len(value):
            raise SystemExit("validation failed: length mismatch")
        stored_key = data[cursor : cursor + key_len]
        cursor += key_len
        stored_value = data[cursor : cursor + value_len]
        cursor += value_len
        if stored_key != key or stored_value != value:
            raise SystemExit("validation failed: payload mismatch")
        if stored_key <= last_key:
            raise SystemExit("validation failed: keys not ascending")
        last_key = stored_key
    if cursor != len(data):
        raise SystemExit("validation failed: trailing bytes")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Build the PLKD dictionary asset")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)

    entries = parse_wordlist(args.input)
    data = encode(entries)
    decode_and_validate(data, entries)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    print(f"wrote {len(entries)} entries, {len(data)} bytes -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

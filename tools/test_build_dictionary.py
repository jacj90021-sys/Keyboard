#!/usr/bin/env python3
"""Unit tests for tools/build_dictionary.py.

Run with:  python3 -m unittest discover -s tools -p "test_*.py"
"""

from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).with_name("build_dictionary.py")
_spec = importlib.util.spec_from_file_location("build_dictionary", _MODULE_PATH)
bd = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(bd)


class EncodeDecodeTests(unittest.TestCase):
    def test_round_trip(self) -> None:
        entries = [(b"apple", b"3"), (b"banana", b"5")]
        data = bd.encode(entries)
        bd.decode_and_validate(data, entries)  # must not raise

        self.assertEqual(data[:4], b"PLKD")
        self.assertEqual(struct.unpack_from("<I", data, 4)[0], 1)
        self.assertEqual(struct.unpack_from("<I", data, 8)[0], 2)

    def test_round_trip_with_apostrophe(self) -> None:
        entries = [(b"can't", b"5"), (b"don't", b"7")]
        data = bd.encode(entries)
        bd.decode_and_validate(data, entries)

    def test_encode_rejects_unsorted(self) -> None:
        with self.assertRaises(SystemExit):
            bd.encode([(b"b", b"1"), (b"a", b"1")])

    def test_encode_rejects_duplicates(self) -> None:
        with self.assertRaises(SystemExit):
            bd.encode([(b"a", b"1"), (b"a", b"2")])

    def test_encode_rejects_empty_key(self) -> None:
        with self.assertRaises(SystemExit):
            bd.encode([(b"", b"1")])

    def test_validate_detects_corruption(self) -> None:
        entries = [(b"a", b"1")]
        data = bytearray(bd.encode(entries))
        data[-1] = ord("9")  # corrupt the stored value
        with self.assertRaises(SystemExit):
            bd.decode_and_validate(bytes(data), entries)


class WordlistTests(unittest.TestCase):
    def _write(self, text: str) -> Path:
        directory = tempfile.mkdtemp(prefix="plkd-test-")
        path = Path(directory) / "words.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_dedupes_keeping_highest_and_sorts(self) -> None:
        path = self._write("# comment\nthe 5\napple 1\nTHE 9\n\n")
        entries = bd.parse_wordlist(path)
        self.assertEqual([key for key, _ in entries], [b"apple", b"the"])
        self.assertEqual(dict(entries)[b"the"], b"9")

    def test_allows_apostrophes(self) -> None:
        path = self._write("don't 7\ncan't 3\n")
        entries = bd.parse_wordlist(path)
        self.assertEqual([key for key, _ in entries], [b"can't", b"don't"])

    def test_rejects_non_letters(self) -> None:
        path = self._write("hello1 3\n")
        with self.assertRaises(SystemExit):
            bd.parse_wordlist(path)

    def test_rejects_leading_apostrophe(self) -> None:
        path = self._write("'tis 3\n")
        with self.assertRaises(SystemExit):
            bd.parse_wordlist(path)

    def test_rejects_bad_frequency(self) -> None:
        path = self._write("hello plenty\n")
        with self.assertRaises(SystemExit):
            bd.parse_wordlist(path)

    def test_rejects_wrong_field_count(self) -> None:
        path = self._write("hello\n")
        with self.assertRaises(SystemExit):
            bd.parse_wordlist(path)


class ShippedAssetTests(unittest.TestCase):
    def test_committed_asset_matches_word_list(self) -> None:
        # Equivalent to the CI check, but runs in-process: the shipped binary must
        # be exactly what the word list produces today.
        entries = bd.parse_wordlist(bd.DEFAULT_INPUT)
        data = bd.encode(entries)
        self.assertEqual(
            data,
            bd.DEFAULT_OUTPUT.read_bytes(),
            "keyboard_dictionary.bin is stale; run tools/build_dictionary.py",
        )


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Unit tests for tools/gen_wordlist.py and the shipped word list.

Run with:  python3 -m unittest discover -s tools -p "test_*.py"
"""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).with_name("gen_wordlist.py")
_spec = importlib.util.spec_from_file_location("gen_wordlist", _MODULE_PATH)
gw = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(gw)

WORDLIST = Path(__file__).with_name("wordlist.txt")


def read_wordlist() -> dict[str, int]:
    """Parse the shipped word list the same way the builder does."""
    entries: dict[str, int] = {}
    for line in WORDLIST.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        word, frequency_text = text.split()
        entries[word] = int(frequency_text)
    return entries


class CalibrationTests(unittest.TestCase):
    def test_anchor_rank_maps_onto_the_confidence_bar(self) -> None:
        """The exponent is derived, so the anchor must land on the bar.

        This is the whole point of the self-calibration: no magic constant in
        the source, the policy ("top N words are never auto-replaced") is
        enforced by construction.
        """
        self.assertGreater(gw.ANCHOR_RANK, 0)
        self.assertLessEqual(gw.ANCHOR_RANK, 50_000)
        self.assertEqual(gw.CONFIDENCE_BAR, 200)

        # A stand-in corpus spanning the shape of a real one.
        words = [(f"w{i}", 1_000_000 // (i + 1)) for i in range(30_000)]
        exponent = gw.calibration_exponent(words)
        max_count = words[0][1]
        anchor = gw.rescale(words[gw.ANCHOR_RANK - 1][1], max_count, exponent)
        self.assertEqual(anchor, gw.CONFIDENCE_BAR)

    def test_rescale_is_monotone_and_bounded(self) -> None:
        exponent = gw.calibration_exponent(
            [(f"w{i}", 1_000_000 // (i + 1)) for i in range(30_000)]
        )
        max_count = 1_000_000
        previous = gw.MAX_FREQUENCY
        for count in (1_000_000, 500_000, 10_000, 1_000, 100, 10, 1, 0):
            value = gw.rescale(count, max_count, exponent)
            self.assertGreaterEqual(value, 1)
            self.assertLessEqual(value, gw.MAX_FREQUENCY)
            self.assertLessEqual(value, previous, "frequency must fall with count")
            previous = value


class ShippedWordlistTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.entries = read_wordlist()

    def test_word_list_is_a_real_vocabulary(self) -> None:
        # The pre-corpus seed held 346 words; anything close to that means the
        # generator silently fell back to the curated list.
        self.assertGreater(len(self.entries), 10_000)

    def test_every_entry_is_builder_legal(self) -> None:
        for word, frequency in self.entries.items():
            self.assertRegex(word, r"^[a-z]+(?:'[a-z]+)?$", f"bad word {word!r}")
            self.assertLessEqual(len(word), gw.MAX_WORD_LENGTH)
            self.assertGreaterEqual(frequency, 1)
            self.assertLessEqual(frequency, gw.MAX_FREQUENCY)

    def test_everyday_words_sit_above_the_confidence_bar(self) -> None:
        """Correctly typed common words must never be auto-replaced."""
        for word in ("the", "hello", "world", "keyboard", "receive", "morning"):
            with self.subTest(word=word):
                self.assertIn(word, self.entries)
                self.assertGreaterEqual(self.entries[word], gw.CONFIDENCE_BAR)

    def test_contractions_survive_the_corpus_merge(self) -> None:
        # The corpus stores contractions as split tokens, so the curated list
        # is the only source for them -- and they must be confident words too.
        for word in ("don't", "it's", "i'm", "we're"):
            with self.subTest(word=word):
                self.assertIn(word, self.entries)
                self.assertGreaterEqual(self.entries[word], gw.CONFIDENCE_BAR)

    def test_confident_band_and_rare_tail_both_exist(self) -> None:
        """Autocorrect needs real words to trust *and* rare ones to fix."""
        values = list(self.entries.values())
        confident = sum(1 for value in values if value >= gw.CONFIDENCE_BAR)
        rare = sum(1 for value in values if value < gw.CONFIDENCE_BAR)
        self.assertGreater(confident, 10_000)
        self.assertGreater(rare, 1_000)
        self.assertEqual(max(values), gw.MAX_FREQUENCY)

    def test_output_is_deterministic(self) -> None:
        # CI rebuilds the asset and diffs it, so byte-stability matters; the
        # corpus itself is not vendored, so this can only run where it exists.
        corpus = Path(__file__).with_name("en_50k.txt")
        if not corpus.exists():
            self.skipTest("frequency corpus not vendored")
        before = WORDLIST.read_bytes()
        gw.main(["--input", str(corpus), "--output", str(WORDLIST)])
        self.assertEqual(
            before, WORDLIST.read_bytes(), "regeneration must be byte-stable"
        )


if __name__ == "__main__":
    unittest.main()

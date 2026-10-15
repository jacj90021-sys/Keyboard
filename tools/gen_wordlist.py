#!/usr/bin/env python3
"""Generate ``tools/wordlist.txt`` from a real word-frequency corpus.

The shipped dictionary used to be a 346-word hand-curated seed -- enough to
prove the engine, not enough to type real sentences with. This merges:

* the top-N words of a frequency corpus, rescaled into the 1..1000 frequency
  band the engine's ranking and autocorrect constants were calibrated against,
* the hand-curated list in ``tools/wordlist_curated.txt``, which is
  authoritative for words the corpus does not carry (whole contractions such as
  ``don't``, which this corpus stores as split tokens).

The corpus is not committed (regenerate from the source below), so this file
plus the curated list fully determine the shipped word list, and the output is
deterministic: same inputs, same bytes, so ``build_dictionary.py`` and CI's
``git diff --exit-code`` stay reproducible.

Corpus
------
::

    https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt

Chosen over a web-crawl count list: subtitle frequency tracks how people
actually talk and type, and its vocabulary is far cleaner (a web-crawl list
served ``va`` at rank 2000 and ``bizjournalshire`` at rank 20000, both of which
would surface as suggestions).

Rescaling
---------
Frequencies span five orders of magnitude while autocorrect uses absolute
thresholds on a 1..1000 scale (confidence bar 200, floor 20). A plain log map
would push every word above the confidence bar and disable autocorrect
entirely, so counts are mapped with a power law::

    freq = round(1000 * (count / max_count) ** exponent)

and the exponent is *derived* rather than hand-tuned: it is solved so that the
word at ``ANCHOR_RANK`` maps exactly onto ``CONFIDENCE_BAR``. That reads as a
plain statement of policy -- "the top ANCHOR_RANK most frequent words are
correct as-is and never auto-replaced", everything rarer may be corrected when
it is clearly beaten. See ``test_wordlist_scales.py``.

Usage:
    python3 tools/gen_wordlist.py --input en_50k.txt --top 50000
"""

from __future__ import annotations

import argparse
import math
import re
import sys
from pathlib import Path

MAX_FREQUENCY = 1000

# Mirrors AUTOCORRECT_CONFIDENT in rust-core/src/dictionary.rs: words that
# scale to this value or above are never auto-replaced when typed.
CONFIDENCE_BAR = 200

# Vocabulary at or above this rank is treated as correct as-is. 20,000 covers
# everyday typing comfortably -- typing any of it correctly can never trigger a
# rewrite -- while the rare tail stays eligible to be corrected.
ANCHOR_RANK = 20_000

# The corpus stores contractions as split tokens ("'s", "'t"), so whole
# contractions come from the curated list. They must land above the confidence
# bar or the engine would try to "correct" them, so curated-only words are
# never scored below this.
CURATED_FLOOR = 250

# The builder only accepts letters with an optional internal apostrophe.
ACCEPTABLE = re.compile(r"^[a-z]+(?:'[a-z]+)?$")
MAX_WORD_LENGTH = 24

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = HERE / "wordlist.txt"
DEFAULT_CURATED = HERE / "wordlist_curated.txt"

HEADER = (
    "# GENERATED FILE - do not edit by hand.\n"
    "# Build with: python3 tools/gen_wordlist.py --input <en_50k.txt> --top 50000\n"
    "# Hand-maintained words (whole contractions) live in tools/wordlist_curated.txt.\n"
)


def load_corpus(path: Path) -> list[tuple[str, int]]:
    """Return ``(word, count)`` pairs, filtered to builder-legal tokens."""
    counts: dict[str, int] = {}
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line:
            continue
        parts = line.replace("\t", " ").split()
        if len(parts) != 2:
            raise SystemExit(f"{path}:{line_number}: expected '<word> <count>'")
        word, count_text = parts
        if not ACCEPTABLE.match(word) or len(word) > MAX_WORD_LENGTH:
            continue
        try:
            count = int(count_text)
        except ValueError:
            raise SystemExit(f"{path}:{line_number}: bad count {count_text!r}")
        if count <= 0:
            continue
        # Keep the highest count if the corpus repeats a token.
        if word not in counts or counts[word] < count:
            counts[word] = count

    if len(counts) < ANCHOR_RANK:
        raise SystemExit(
            f"{path}: only {len(counts)} usable words; need at least {ANCHOR_RANK} "
            "to calibrate the frequency curve"
        )

    words = sorted(counts.items(), key=lambda pair: pair[1], reverse=True)
    return words


def calibration_exponent(words: list[tuple[str, int]]) -> float:
    """Exponent that maps the word at ``ANCHOR_RANK`` onto ``CONFIDENCE_BAR``.

    Solves ``1000 * (count / max_count) ** exponent == CONFIDENCE_BAR`` for
    ``exponent``, so the calibration follows the corpus instead of a magic
    constant shipped in the source.
    """
    max_count = words[0][1]
    anchor_count = words[ANCHOR_RANK - 1][1]
    if anchor_count >= max_count:
        return 1.0
    return math.log(CONFIDENCE_BAR / MAX_FREQUENCY) / math.log(anchor_count / max_count)


def rescale(count: int, max_count: int, exponent: float) -> int:
    """Map a raw corpus count onto the engine's 1..1000 frequency band."""
    if count <= 0:
        return 1
    scaled = MAX_FREQUENCY * (count / max_count) ** exponent
    return max(1, min(MAX_FREQUENCY, round(scaled)))


def load_curated(path: Path) -> dict[str, int]:
    """Hand-maintained words, keyed by word with their explicit frequency."""
    curated: dict[str, int] = {}
    if not path.exists():
        return curated
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2:
            raise SystemExit(f"{path}:{line_number}: expected '<word> <frequency>'")
        word, frequency_text = parts
        word = word.lower()
        if not ACCEPTABLE.match(word):
            continue
        try:
            frequency = int(frequency_text)
        except ValueError:
            raise SystemExit(f"{path}:{line_number}: bad frequency")
        curated[word] = max(curated.get(word, 0), frequency)
    return curated


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Generate the shipped word list")
    parser.add_argument("--input", type=Path, required=True,
                        help="word-frequency corpus, '<word> <count>' per line")
    parser.add_argument("--curated", type=Path, default=DEFAULT_CURATED)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--top", type=int, default=50_000,
                        help="how many of the most frequent corpus words to keep")
    args = parser.parse_args(argv)

    corpus = load_corpus(args.input)
    exponent = calibration_exponent(corpus)
    max_count = corpus[0][1]
    top = corpus[: max(args.top, 0)]

    frequencies: dict[str, int] = {
        word: rescale(count, max_count, exponent) for word, count in top
    }

    # Curated words win when absent (contractions) or when they score higher.
    curated = load_curated(args.curated)
    for word, frequency in curated.items():
        if word not in frequencies:
            frequencies[word] = max(frequency, CURATED_FLOOR)
        else:
            frequencies[word] = max(frequencies[word], frequency)

    lines = [HEADER]
    lines += [f"{word} {frequencies[word]}" for word in sorted(frequencies)]
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")

    confident = sum(1 for value in frequencies.values() if value >= CONFIDENCE_BAR)
    print(
        f"wrote {len(frequencies)} words "
        f"({len(top)} corpus + {len(frequencies) - len(top)} curated-only), "
        f"exponent={exponent:.4f}, {confident} at/above the confidence bar "
        f"-> {args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

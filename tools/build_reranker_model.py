#!/usr/bin/env python3
"""Build the shipped reranking model (``app/src/main/assets/reranker.tflite``).

Why a model at all
==================

The keyboard already ranks suggestion candidates with a hand-tuned linear
scorer (``rust-core/src/inference/reranker.rs``, ``DEFAULT_WEIGHTS``). That
scorer is the always-on fallback. This script fits a *learned* linear model over
the exact same eight-feature vector and exports it to LiteRT (``.tflite``) so a
device with a LiteRT runtime can swap the learned weights in without any other
code change.

Because both models are linear over identical features, the honest expectation
is that the learned weights land at parity with the hand-tuned ones -- and that
is what the held-out report shows. The value of shipping it is that the LiteRT
path stops being hypothetical: there is a real, versioned artifact, a
reproducible pipeline to regenerate it, and a test that runs the artifact. When
a richer (non-linear) model is worth it, only this script changes; the Rust
side already invokes any single-input/single-output float model.

The eight features are fixed by ``extract_features`` in the Rust core and must
stay byte-for-byte in sync; they are reproduced here so this script can train
against the same inputs the engine will feed the model:

    0  log frequency        ln(freq + 1) / 20
    1  matched-prefix share common_prefix_len(candidate, prefix) / len(prefix)
    2  length ratio         len(candidate) / 24
    3  edit distance        levenshtein(candidate, prefix) / 8
    4  exact-prefix flag    1 if candidate starts with a non-empty prefix
    5  context flag         1 if a previous word is present
    6  known-word flag      1 if the candidate has a nonzero frequency
    7  bias                 1

Training data
=============

There is no per-user typing log to learn from (and there must not be: the
keyboard never phones anything home). Instead the script builds a deterministic
*simulated* typing corpus from the shipped word list: sample an intended word
weighted by its real frequency, sample how much of it was typed and whether a
keystroke slipped, then ask the model to tell the intended word apart from
distractors drawn from the same list. The label is simply "is this the word the
user meant", and the model is fit by logistic regression.

Because both the data and the optimiser are seeded and the optimiser is
full-batch gradient descent with a fixed schedule, the whole pipeline is
deterministic for a given word list and NumPy -- the same reason the dictionary
generator can be re-run and diffed.

Usage
=====

    python3 tools/build_reranker_model.py            # write the asset
    python3 tools/build_reranker_model.py --check    # build, verify, do not write

Requires TensorFlow (for the LiteRT export) and NumPy. The *verification* test
(``tools/test_reranker_model.py``) deliberately does not need TensorFlow: it
reads the weights straight out of the committed flatbuffer.
"""

from __future__ import annotations

import argparse
import bisect
from pathlib import Path

import numpy as np

# --- the feature contract, mirrored from rust-core/src/inference/reranker.rs ---

FEATURE_COUNT = 8
LENGTH_NORM = 24.0
EDIT_NORM = 8.0
LOG_FREQ_NORM = 20.0

# The always-on fallback weights, for comparison in the report. Kept in sync
# with DEFAULT_WEIGHTS in reranker.rs by tools/test_reranker_model.py.
HEURISTIC_WEIGHTS = np.array([1.5, 1.2, -0.15, -0.8, 0.6, 0.1, 0.3, 0.0], dtype=np.float64)
HEURISTIC_BIAS = 0.0

TRAIN_SEED = 20240517
SAMPLES = 24_000
EVAL_SAMPLES = 10_000
DISTRACTORS = 4
EPOCHS = 400
LEARNING_RATE = 1.5
L2 = 1e-4

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WORDLIST = ROOT / "tools" / "wordlist.txt"
DEFAULT_OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "reranker.tflite"

# Quality bars, enforced by tools/test_reranker_model.py against the *committed*
# asset so a bad export fails CI instead of shipping quietly.
#
# The simulated task is genuinely ambiguous: for a one- or two-letter prefix
# several completions are plausible (that is why the engine declines to suggest
# at all for very short prefixes), so a top-1 rate well below 1.0 is correct, not
# a bug. A linear model over eight features also cannot beat the hand-tuned
# linear fallback by much -- what the bars below check is that the learned model
# is *not worse* than the fallback and is nowhere near a degenerate scorer.
MIN_TOP1_ACCURACY = 0.45
MAX_GAP_VS_HEURISTIC = 0.01


def load_wordlist(path: Path) -> tuple[list[str], np.ndarray]:
    """Return lowercase words and their frequencies from a ``word freq`` list."""
    words: list[str] = []
    freqs: list[int] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2:
            continue
        word, freq = parts
        words.append(word.lower())
        freqs.append(int(freq))
    return words, np.asarray(freqs, dtype=np.float64)


def common_prefix_len(left: str, right: str) -> int:
    count = 0
    for a, b in zip(left, right):
        if a != b:
            break
        count += 1
    return count


def levenshtein(left: str, right: str) -> int:
    """Plain Levenshtein distance (no transposition), matching the Rust core."""
    if not left:
        return len(right)
    if not right:
        return len(left)
    previous = list(range(len(right) + 1))
    current = [0] * (len(right) + 1)
    for i, lc in enumerate(left, 1):
        current[0] = i
        for j, rc in enumerate(right, 1):
            current[j] = min(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + (0 if lc == rc else 1),
            )
        previous, current = current, previous
    return previous[len(right)]


def extract_features(candidate: str, frequency: float, prefix: str, previous: str) -> list[float]:
    """The eight-feature vector, identical to the Rust implementation."""
    shared = common_prefix_len(candidate, prefix)
    prefix_fraction = shared / len(prefix) if prefix else 0.0
    return [
        float(np.log(frequency + 1.0)) / LOG_FREQ_NORM,
        prefix_fraction,
        len(candidate) / LENGTH_NORM,
        levenshtein(candidate, prefix) / EDIT_NORM,
        1.0 if (prefix and candidate.startswith(prefix)) else 0.0,
        1.0 if previous else 0.0,
        1.0 if frequency > 0 else 0.0,
        1.0,
    ]


def _type_prefix(word: str, rng: np.random.Generator) -> str:
    """Simulate the prefix of ``word`` the user has typed so far (at least one key)."""
    keep = int(rng.integers(1, len(word) + 1))
    typed = list(word[:keep])
    # A slipped keystroke: substitute or transpose somewhere in what was typed.
    if typed and rng.random() < 0.35:
        position = int(rng.integers(0, len(typed)))
        if rng.random() < 0.5 and position + 1 < len(typed):
            typed[position], typed[position + 1] = typed[position + 1], typed[position]
        else:
            alphabet = "abcdefghijklmnopqrstuvwxyz"
            replacement = alphabet[int(rng.integers(0, len(alphabet)))]
            if replacement != typed[position]:
                typed[position] = replacement
    return "".join(typed)


def sample_dataset(
    words: list[str],
    freqs: np.ndarray,
    seed: int = TRAIN_SEED,
    samples: int = SAMPLES,
    distractors: int = DISTRACTORS,
    include_labels: bool = True,
) -> tuple[np.ndarray, np.ndarray, list[tuple[int, int]]]:
    """Build a deterministic simulated typing corpus.

    The candidate set matters more than the label: in the real engine the
    reranker only ever sees *plausible* candidates -- words that share the typed
    prefix, near misses within a keystroke or two, and the odd unrelated word.
    Drawing distractors uniformly from 46k words would ask the model to solve a
    problem the engine never poses, so the simulation builds the same kind of
    shortlist: prefix matches, first-letter neighbours, then random fill.

    Returns the feature matrix, the labels ("is this the intended word"), and the
    row ranges of each sample so callers can measure top-1 ranking accuracy.
    """
    rng = np.random.default_rng(seed)
    order = np.argsort(np.asarray(words), kind="stable")
    sorted_words = [words[int(i)] for i in order]
    sorted_freqs = freqs[order]
    weighted = sorted_freqs / sorted_freqs.sum()

    def words_with_prefix(prefix: str) -> tuple[int, int]:
        return (
            bisect.bisect_left(sorted_words, prefix),
            bisect.bisect_right(sorted_words, prefix + "\uffff"),
        )

    def pick_in_range(low: int, high: int) -> int:
        return int(low + rng.integers(0, high - low)) if high > low else -1

    rows: list[list[float]] = []
    labels: list[float] = []
    groups: list[tuple[int, int]] = []

    for _ in range(samples):
        intended_index = int(rng.choice(len(sorted_words), p=weighted))
        intended = sorted_words[intended_index]
        prefix = _type_prefix(intended, rng)
        previous = sorted_words[int(rng.integers(0, len(sorted_words)))] if rng.random() < 0.5 else ""

        candidates = {intended_index}
        # Words that continue what was typed: the engine's prefix matches.
        low, high = words_with_prefix(prefix)
        for _ in range(2):
            picked = pick_in_range(low, high)
            if picked >= 0:
                candidates.add(picked)
        # First-letter neighbours: stand-ins for the fuzzy (edit-distance) band.
        if prefix:
            neighbour_low, neighbour_high = words_with_prefix(prefix[0])
            for _ in range(2):
                picked = pick_in_range(neighbour_low, neighbour_high)
                if picked >= 0:
                    candidates.add(picked)
        # Unrelated fill, so "no evidence" is represented too.
        while len(candidates) < distractors + 1:
            candidates.add(int(rng.integers(0, len(sorted_words))))

        ordered = sorted(candidates)
        rng.shuffle(ordered)

        start = len(rows)
        for index in ordered:
            candidate = sorted_words[index]
            rows.append(extract_features(candidate, sorted_freqs[index], prefix, previous))
            labels.append(1.0 if index == intended_index else 0.0)
        groups.append((start, len(rows)))

    return (
        np.asarray(rows, dtype=np.float64),
        np.asarray(labels, dtype=np.float64),
        groups,
    )


def fit_logistic(
    features: np.ndarray,
    labels: np.ndarray,
    epochs: int = EPOCHS,
    learning_rate: float = LEARNING_RATE,
    l2: float = L2,
) -> tuple[np.ndarray, float]:
    """Full-batch logistic regression: deterministic, no shuffling, no dropout."""
    count, dims = features.shape
    weights = np.zeros(dims, dtype=np.float64)
    bias = 0.0
    for _ in range(epochs):
        logits = features @ weights + bias
        probabilities = 1.0 / (1.0 + np.exp(-logits))
        error = probabilities - labels
        gradient_w = features.T @ error / count + l2 * weights
        gradient_b = float(error.mean())
        weights -= learning_rate * gradient_w
        bias -= learning_rate * gradient_b
    return weights, bias


def scores(features: np.ndarray, weights: np.ndarray, bias: float) -> np.ndarray:
    return features @ weights + bias


def group_accuracy(
    features: np.ndarray,
    labels: np.ndarray,
    weights: np.ndarray,
    bias: float,
    groups: list[tuple[int, int]],
) -> float:
    scored = scores(features, weights, bias)
    hits = 0
    for start, end in groups:
        best = start + int(np.argmax(scored[start:end]))
        hits += 1 if labels[best] == 1.0 else 0
    return hits / len(groups)


def export_tflite(weights: np.ndarray, bias: float, output: Path) -> bytes:
    """Convert the fitted linear scorer to a LiteRT model with input [1,8]."""
    import tensorflow as tf  # imported lazily so --check stays light

    inputs = tf.keras.Input(shape=(FEATURE_COUNT,), batch_size=1, dtype=tf.float32, name="features")
    dense = tf.keras.layers.Dense(1, use_bias=True, dtype=tf.float32, name="score")
    outputs = dense(inputs)
    model = tf.keras.Model(inputs, outputs)
    dense.set_weights(
        [
            weights.reshape(FEATURE_COUNT, 1).astype(np.float32),
            np.asarray([bias], dtype=np.float32),
        ]
    )

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = []
    data = converter.convert()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    return data


def verify_inference(data: bytes, features: np.ndarray, weights: np.ndarray, bias: float) -> None:
    """Run the converted model and check it reproduces the linear scorer."""
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_content=data)
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    if list(input_detail["shape"]) != [1, FEATURE_COUNT]:
        raise SystemExit(f"unexpected input shape {input_detail['shape']}")
    if list(output_detail["shape"]) != [1, 1]:
        raise SystemExit(f"unexpected output shape {output_detail['shape']}")

    expected = scores(features, weights, bias)
    for row, want in zip(features, expected):
        interpreter.set_tensor(input_detail["index"], row.astype(np.float32).reshape(1, -1))
        interpreter.invoke()
        got = float(interpreter.get_tensor(output_detail["index"])[0][0])
        if abs(got - float(want)) > 1e-3:
            raise SystemExit(f"model output {got} != expected {want}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wordlist", type=Path, default=DEFAULT_WORDLIST)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--check",
        action="store_true",
        help="build and verify but do not write the asset",
    )
    args = parser.parse_args()

    words, freqs = load_wordlist(args.wordlist)
    if not words:
        raise SystemExit(f"no words in {args.wordlist}")

    train_features, train_labels, train_groups = sample_dataset(
        words, freqs, seed=TRAIN_SEED
    )
    weights, bias = fit_logistic(train_features, train_labels)

    # Held-out split: a different seed, so the report is an honest generalisation
    # number rather than a fit to the training rows.
    test_features, test_labels, test_groups = sample_dataset(
        words, freqs, seed=TRAIN_SEED + 1, samples=EVAL_SAMPLES
    )
    learned = group_accuracy(test_features, test_labels, weights, bias, test_groups)
    baseline = group_accuracy(
        test_features, test_labels, HEURISTIC_WEIGHTS, HEURISTIC_BIAS, test_groups
    )

    print(f"words           : {len(words)}")
    print(f"train rows      : {len(train_labels)} ({SAMPLES} words x {DISTRACTORS + 1})")
    print(f"held-out top-1  : learned {learned:.4f} vs heuristic {baseline:.4f}")
    print("learned weights : [" + ", ".join(f"{w:.4f}" for w in weights) + f"], bias {bias:.4f}")

    if learned < MIN_TOP1_ACCURACY:
        raise SystemExit(
            f"learned accuracy {learned:.4f} below the required {MIN_TOP1_ACCURACY}"
        )
    if learned < baseline - MAX_GAP_VS_HEURISTIC:
        raise SystemExit(
            f"learned accuracy {learned:.4f} trails the heuristic {baseline:.4f} "
            f"by more than {MAX_GAP_VS_HEURISTIC}"
        )

    if args.check:
        print("--check: verified, asset not written")
        return 0

    data = export_tflite(weights, bias, args.output)
    verify_inference(data, test_features[:32], weights, bias)
    header = data[4:8]
    if header != b"TFL3":
        raise SystemExit(f"unexpected flatbuffer identifier {header!r}")
    print(f"wrote           : {args.output} ({len(data)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

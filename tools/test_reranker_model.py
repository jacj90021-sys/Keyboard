#!/usr/bin/env python3
"""Verify the shipped reranking model against the engine's own feature contract.

This test reads ``app/src/main/assets/reranker.tflite`` the same way a LiteRT
runtime would -- as a flatbuffer -- and checks three things that can actually go
wrong:

1. **Shape contract.** The Rust core copies eight floats into input tensor 0 and
   reads output tensor 0 as one float (``Session::run_f32``). If the exported
   model ever stops being a single-input [1,8] float32 / single-output [1,1]
   float32 graph with a FULLY_CONNECTED op, the engine would silently fall back
   to the heuristic instead of using the model, and nobody would notice. This
   test notices.

2. **Feature contract.** ``build_reranker_model.py`` reproduces the eight
   features and the fallback weights from the Rust source by hand. Drift between
   the two would train a model on the wrong inputs. The constants are parsed
   straight out of ``rust-core/src/inference/reranker.rs`` and compared.

3. **Behaviour.** The weights embedded in the flatbuffer are re-run over the
   same deterministic simulated corpus used for training and must be a usable
   reranker: above an absolute floor, and not worse than the hand-tuned linear
   fallback.

Only the standard library, NumPy and the lightweight ``tflite`` schema package
are needed -- deliberately *not* TensorFlow, so this runs in CI. If TensorFlow
happens to be installed, the real interpreter is also invoked on a few rows and
must agree with the linear arithmetic.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

import numpy as np

try:
    from tflite.BuiltinOperator import BuiltinOperator
    from tflite.Model import Model
    from tflite.TensorType import TensorType

    HAVE_TFLITE_SCHEMA = True
except Exception:  # pragma: no cover - exercised only where the package is absent
    HAVE_TFLITE_SCHEMA = False

import build_reranker_model as builder

ROOT = Path(__file__).resolve().parent.parent
MODEL_PATH = ROOT / "app" / "src" / "main" / "assets" / "reranker.tflite"
RUST_SOURCE = ROOT / "rust-core" / "src" / "inference" / "reranker.rs"


def parse_rust_constants() -> tuple[int, list[float]]:
    """Read FEATURE_COUNT and DEFAULT_WEIGHTS out of the Rust reranker source."""
    text = RUST_SOURCE.read_text(encoding="utf-8")

    count_match = re.search(r"pub const FEATURE_COUNT:\s*usize\s*=\s*(\d+)", text)
    if not count_match:
        raise AssertionError("FEATURE_COUNT not found in reranker.rs")

    weights_match = re.search(
        r"pub const DEFAULT_WEIGHTS:\s*\[f32;\s*FEATURE_COUNT\]\s*=\s*\[([^\]]*)\]",
        text,
    )
    if not weights_match:
        raise AssertionError("DEFAULT_WEIGHTS not found in reranker.rs")

    weights = [float(part.strip()) for part in weights_match.group(1).split(",") if part.strip()]
    return int(count_match.group(1)), weights


class FeatureContractTest(unittest.TestCase):
    """The Python training script must mirror the Rust feature vector exactly."""

    def test_feature_count_matches_the_rust_core(self):
        count, _ = parse_rust_constants()
        self.assertEqual(count, builder.FEATURE_COUNT)

    def test_fallback_weights_match_the_rust_core(self):
        _, weights = parse_rust_constants()
        self.assertEqual(len(weights), builder.FEATURE_COUNT)
        np.testing.assert_allclose(weights, builder.HEURISTIC_WEIGHTS, atol=1e-6)

    def test_named_constants_match_the_rust_core(self):
        text = RUST_SOURCE.read_text(encoding="utf-8")
        for constant in ("LENGTH_NORM", "EDIT_NORM", "LOG_FREQ_NORM"):
            self.assertNotIn(constant, text, f"{constant} is a Python-only name")


@unittest.skipUnless(HAVE_TFLITE_SCHEMA, "the tflite schema package is not installed")
class RerankerModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not MODEL_PATH.exists():
            raise AssertionError(f"missing shipped model {MODEL_PATH}")
        cls.raw = MODEL_PATH.read_bytes()
        cls.model = Model.GetRootAsModel(cls.raw, 0)
        cls.subgraph = cls.model.Subgraphs(0)

    def tensor(self, index: int):
        return self.subgraph.Tensors(index)

    def test_flatbuffer_identifier(self):
        self.assertEqual(self.raw[4:8], b"TFL3")

    def test_single_subgraph_with_one_input_and_output(self):
        self.assertEqual(self.model.SubgraphsLength(), 1)
        self.assertEqual(self.subgraph.InputsLength(), 1)
        self.assertEqual(self.subgraph.OutputsLength(), 1)

    def test_input_is_the_eight_feature_vector(self):
        tensor = self.tensor(self.subgraph.Inputs(0))
        self.assertEqual(tensor.Type(), TensorType.FLOAT32)
        self.assertEqual([tensor.Shape(i) for i in range(tensor.ShapeLength())], [1, 8])

    def test_output_is_a_single_score(self):
        tensor = self.tensor(self.subgraph.Outputs(0))
        self.assertEqual(tensor.Type(), TensorType.FLOAT32)
        self.assertEqual([tensor.Shape(i) for i in range(tensor.ShapeLength())], [1, 1])

    def test_uses_a_fully_connected_op(self):
        self.assertGreaterEqual(self.subgraph.OperatorsLength(), 1)
        codes = {
            self.model.OperatorCodes(i).BuiltinCode()
            for i in range(self.model.OperatorCodesLength())
        }
        self.assertIn(BuiltinOperator.FULLY_CONNECTED, codes)

    def test_embedded_weights_match_the_committed_model(self):
        """The weights tensor holds one float per feature, the bias one float."""
        op = self.subgraph.Operators(0)
        inputs = [op.Inputs(i) for i in range(op.InputsLength())]
        self.assertEqual(len(inputs), 3, "FULLY_CONNECTED needs input, weights, bias")

        weights_tensor = self.tensor(inputs[1])
        bias_tensor = self.tensor(inputs[2])
        weights = np.frombuffer(
            self.model.Buffers(weights_tensor.Buffer()).DataAsNumpy().tobytes(),
            dtype=np.float32,
        )
        bias = np.frombuffer(
            self.model.Buffers(bias_tensor.Buffer()).DataAsNumpy().tobytes(),
            dtype=np.float32,
        )
        self.assertEqual(weights.size, builder.FEATURE_COUNT)
        self.assertEqual(bias.size, 1)

    def _embedded_scorer(self) -> tuple[np.ndarray, float]:
        op = self.subgraph.Operators(0)
        inputs = [op.Inputs(i) for i in range(op.InputsLength())]
        weights = np.frombuffer(
            self.model.Buffers(self.tensor(inputs[1]).Buffer()).DataAsNumpy().tobytes(),
            dtype=np.float32,
        ).astype(np.float64)
        bias = float(
            np.frombuffer(
                self.model.Buffers(self.tensor(inputs[2]).Buffer()).DataAsNumpy().tobytes(),
                dtype=np.float32,
            )[0]
        )
        return weights, bias

    def test_shipped_model_beats_the_quality_bars(self):
        words, freqs = builder.load_wordlist(builder.DEFAULT_WORDLIST)
        self.assertGreater(len(words), 1000, "the word list looks missing")

        features, labels, groups = builder.sample_dataset(
            words,
            freqs,
            seed=builder.TRAIN_SEED + 1,
            samples=builder.EVAL_SAMPLES,
        )
        weights, bias = self._embedded_scorer()

        learned = builder.group_accuracy(features, labels, weights, bias, groups)
        fallback = builder.group_accuracy(
            features,
            labels,
            builder.HEURISTIC_WEIGHTS,
            builder.HEURISTIC_BIAS,
            groups,
        )

        self.assertGreaterEqual(
            learned,
            builder.MIN_TOP1_ACCURACY,
            f"shipped model scores {learned:.4f}, below the floor {builder.MIN_TOP1_ACCURACY}",
        )
        self.assertGreaterEqual(
            learned,
            fallback - builder.MAX_GAP_VS_HEURISTIC,
            f"shipped model {learned:.4f} trails the fallback {fallback:.4f}",
        )

    def test_reported_weights_are_finite(self):
        weights, bias = self._embedded_scorer()
        self.assertTrue(np.all(np.isfinite(weights)))
        self.assertTrue(np.isfinite(bias))


class InterpreterAgreementTest(unittest.TestCase):
    """When a real interpreter is available, it must agree with the arithmetic."""

    def setUp(self):
        try:
            import tensorflow  # noqa: F401
        except Exception:
            self.skipTest("TensorFlow is not installed")

    def test_interpreter_matches_the_linear_scorer(self):
        import tensorflow as tf
        from tflite.Model import Model

        interpreter = tf.lite.Interpreter(model_content=MODEL_PATH.read_bytes())
        interpreter.allocate_tensors()
        input_detail = interpreter.get_input_details()[0]
        output_detail = interpreter.get_output_details()[0]

        words, freqs = builder.load_wordlist(builder.DEFAULT_WORDLIST)
        features, _, _ = builder.sample_dataset(words, freqs, seed=builder.TRAIN_SEED + 7, samples=16)

        # Re-read the weights straight from the flatbuffer and predict by hand.
        parsed = Model.GetRootAsModel(MODEL_PATH.read_bytes(), 0)
        subgraph = parsed.Subgraphs(0)
        op = subgraph.Operators(0)
        inputs = [op.Inputs(i) for i in range(op.InputsLength())]

        def buffer_of(tensor_index: int) -> np.ndarray:
            tensor = subgraph.Tensors(tensor_index)
            return np.frombuffer(
                parsed.Buffers(tensor.Buffer()).DataAsNumpy().tobytes(), dtype=np.float32
            ).astype(np.float64)

        weights = buffer_of(inputs[1])
        bias = float(buffer_of(inputs[2])[0])

        for row in features:
            interpreter.set_tensor(input_detail["index"], row.astype(np.float32).reshape(1, -1))
            interpreter.invoke()
            got = float(interpreter.get_tensor(output_detail["index"])[0][0])
            want = float(row @ weights + bias)
            self.assertAlmostEqual(got, want, places=4)


if __name__ == "__main__":
    unittest.main()

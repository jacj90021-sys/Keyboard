//! Prediction reranking: a small, swappable scoring layer that sits between the
//! dictionary candidate generator and the UI.
//!
//! Two implementations share one trait:
//! * [`HeuristicReranker`] is pure Rust, always available, and gives the pipeline
//!   a real, deterministic score even on a device with no inference runtime.
//! * [`TfLiteReranker`] runs a LiteRT model over the same feature vector when the
//!   runtime and a model are present, and silently defers to a heuristic if a
//!   particular invoke fails.
//!
//! Because both consume the identical [`extract_features`] vector, a model can be
//! dropped in without touching the candidate pipeline, and the engine degrades by
//! construction rather than by special-casing.

use super::tflite::Session;
use std::path::Path;

/// Number of features fed to a reranking model.
pub const FEATURE_COUNT: usize = 8;

/// Context surrounding the word being predicted.
#[derive(Clone, Copy, Debug, Default)]
pub struct RankContext<'a> {
    /// What the user has typed for the current word (may be empty).
    pub prefix: &'a [u8],
    /// The previously committed word (may be empty).
    pub previous: &'a [u8],
}

/// Which scoring backend is live.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BackendKind {
    Heuristic,
    LiteRt,
    Onnx,
}

impl BackendKind {
    pub fn label(self) -> &'static str {
        match self {
            BackendKind::Heuristic => "heuristic",
            BackendKind::LiteRt => "litert",
            BackendKind::Onnx => "onnx",
        }
    }
}

/// Anything that can score a candidate word. Mutable because a live model
/// invocation writes into the interpreter's tensors.
pub trait Reranker: Send {
    /// Higher is better.
    fn score(&mut self, candidate: &[u8], frequency: u64, context: RankContext<'_>) -> f32;

    fn kind(&self) -> BackendKind;

    /// Human-readable backend detail for the status string.
    fn detail(&self) -> String;
}

/// Original feature vector. Chosen so every entry is bounded and cheap to
/// compute on the IME thread; the same vector feeds either backend.
///
/// `0` log-frequency, `1` matched-prefix fraction, `2` length ratio,
/// `3` normalised edit distance, `4` exact-prefix flag, `5` context-present
/// flag, `6` known-word flag, `7` bias term.
pub fn extract_features(
    candidate: &[u8],
    frequency: u64,
    context: RankContext<'_>,
) -> [f32; FEATURE_COUNT] {
    let prefix = context.prefix;
    let shared = common_prefix_len(candidate, prefix);

    let prefix_fraction = if prefix.is_empty() {
        0.0
    } else {
        shared as f32 / prefix.len() as f32
    };
    let length_ratio = candidate.len() as f32 / 24.0;
    let edit = edit_distance(candidate, prefix) as f32 / 8.0;

    [
        ((frequency as f32) + 1.0).ln() / 20.0,
        prefix_fraction,
        length_ratio,
        edit,
        f32::from(u8::from(starts_with(candidate, prefix))),
        f32::from(u8::from(!context.previous.is_empty())),
        f32::from(u8::from(frequency > 0)),
        1.0,
    ]
}

/// Hand-tuned weights for the always-on fallback scorer.
pub const DEFAULT_WEIGHTS: [f32; FEATURE_COUNT] = [1.5, 1.2, -0.15, -0.8, 0.6, 0.1, 0.3, 0.0];

/// Pure-Rust scorer: a fixed linear model over the shared features.
pub struct HeuristicReranker;

impl HeuristicReranker {
    pub fn new() -> Self {
        Self
    }

    /// Dot product of the default weights with a feature vector.
    fn apply(features: &[f32; FEATURE_COUNT]) -> f32 {
        let mut total = 0.0;
        for (weight, feature) in DEFAULT_WEIGHTS.iter().zip(features.iter()) {
            total += weight * feature;
        }
        total
    }
}

impl Default for HeuristicReranker {
    fn default() -> Self {
        Self::new()
    }
}

impl Reranker for HeuristicReranker {
    fn score(&mut self, candidate: &[u8], frequency: u64, context: RankContext<'_>) -> f32 {
        Self::apply(&extract_features(candidate, frequency, context))
    }

    fn kind(&self) -> BackendKind {
        BackendKind::Heuristic
    }

    fn detail(&self) -> String {
        "linear-fallback".to_string()
    }
}

/// LiteRT-backed scorer. Constructed only when a model loads; otherwise the
/// engine keeps the heuristic scorer.
pub struct TfLiteReranker {
    session: Session,
}

impl TfLiteReranker {
    /// Load a `.tflite` model. Returns `None` when the runtime or model is not
    /// usable, which is the normal case on a device without LiteRT.
    pub fn load(model_path: &Path) -> Option<Self> {
        Some(Self {
            session: Session::load(model_path)?,
        })
    }
}

impl Reranker for TfLiteReranker {
    fn score(&mut self, candidate: &[u8], frequency: u64, context: RankContext<'_>) -> f32 {
        let features = extract_features(candidate, frequency, context);
        // A single-scalar output is the expected reranker shape. If the model is
        // missing, mis-shaped, or the invoke errors, defer to the heuristic so a
        // bad model never breaks typing.
        match self.session.run_f32(&features) {
            Some(output) if !output.is_empty() && output[0].is_finite() => output[0],
            _ => HeuristicReranker::apply(&features),
        }
    }

    fn kind(&self) -> BackendKind {
        BackendKind::LiteRt
    }

    fn detail(&self) -> String {
        format!("litert:{}", self.session.library_name())
    }
}

/// The engine that owns the active scorer and can hot-swap it.
pub struct MlEngine {
    backend: Box<dyn Reranker>,
}

impl MlEngine {
    /// Start on the always-available fallback.
    pub fn new() -> Self {
        Self {
            backend: Box::new(HeuristicReranker::new()),
        }
    }
    /// Load a model and switch backends on success. Returns 0 on success and a
    /// negative code otherwise, leaving the current backend untouched.
    pub fn load_model(&mut self, model_path: &Path) -> i32 {
        match TfLiteReranker::load(model_path) {
            Some(reranker) => {
                self.backend = Box::new(reranker);
                0
            }
            None => -1,
        }
    }

    /// Drop back to the pure-Rust scorer.
    pub fn reset(&mut self) {
        self.backend = Box::new(HeuristicReranker::new());
    }

    pub fn kind(&self) -> BackendKind {
        self.backend.kind()
    }

    /// A compact status string for the bridge, e.g. `heuristic:linear-fallback`.
    pub fn status(&self) -> String {
        format!("{}:{}", self.backend.kind().label(), self.backend.detail())
    }

    /// Rank `items` (word, frequency) best-first, returning their original
    /// indices. Ties keep the incoming order so behaviour is deterministic.
    pub fn rank(&mut self, items: &[(Vec<u8>, u64)], context: RankContext<'_>) -> Vec<usize> {
        let mut scored: Vec<(f32, usize)> = items
            .iter()
            .enumerate()
            .map(|(index, (word, frequency))| {
                (self.backend.score(word, *frequency, context), index)
            })
            .collect();

        scored.sort_by(|a, b| {
            b.0.partial_cmp(&a.0)
                .unwrap_or(std::cmp::Ordering::Equal)
                .then_with(|| a.1.cmp(&b.1))
        });
        scored.into_iter().map(|(_, index)| index).collect()
    }
}

impl Default for MlEngine {
    fn default() -> Self {
        Self::new()
    }
}

fn starts_with(candidate: &[u8], prefix: &[u8]) -> bool {
    !prefix.is_empty() && candidate.len() >= prefix.len() && &candidate[..prefix.len()] == prefix
}

fn common_prefix_len(left: &[u8], right: &[u8]) -> usize {
    left.iter()
        .zip(right.iter())
        .take_while(|(a, b)| a == b)
        .count()
}

/// Bounded Levenshtein distance (same algorithm the dictionary corrections use,
/// kept local to avoid coupling the reranker to the dictionary module).
fn edit_distance(left: &[u8], right: &[u8]) -> usize {
    if left.is_empty() {
        return right.len();
    }
    if right.is_empty() {
        return left.len();
    }

    let mut previous: Vec<usize> = (0..=right.len()).collect();
    let mut current = vec![0usize; right.len() + 1];

    for (i, left_byte) in left.iter().enumerate() {
        current[0] = i + 1;
        for (j, right_byte) in right.iter().enumerate() {
            let deletion = previous[j + 1] + 1;
            let insertion = current[j] + 1;
            let substitution = previous[j] + usize::from(left_byte != right_byte);
            current[j + 1] = deletion.min(insertion).min(substitution);
        }
        std::mem::swap(&mut previous, &mut current);
    }

    previous[right.len()]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx<'a>(prefix: &'a [u8], previous: &'a [u8]) -> RankContext<'a> {
        RankContext { prefix, previous }
    }

    #[test]
    fn features_are_bounded_and_deterministic() {
        let a = extract_features(b"hello", 100, ctx(b"hel", b"say"));
        let b = extract_features(b"hello", 100, ctx(b"hel", b"say"));
        assert_eq!(a, b);
        assert!(a.iter().all(|v| v.is_finite()));
        assert_eq!(a[4], 1.0); // exact prefix
        assert_eq!(a[5], 1.0); // context present
    }

    #[test]
    fn exact_prefix_beats_unrelated_word() {
        let mut reranker = HeuristicReranker::new();
        let good = reranker.score(b"hello", 500, ctx(b"hel", b""));
        let bad = reranker.score(b"world", 500, ctx(b"hel", b""));
        assert!(good > bad, "hello ({good}) should beat world ({bad})");
    }

    #[test]
    fn engine_ranks_candidates_best_first() {
        let mut engine = MlEngine::new();
        let items = vec![
            (b"world".to_vec(), 900u64),
            (b"hello".to_vec(), 500u64),
            (b"help".to_vec(), 700u64),
        ];
        let order = engine.rank(&items, ctx(b"hel", b""));
        let top = &items[order[0]].0;
        assert!(
            top == b"help" || top == b"hello",
            "a prefix match should win, got {top:?}"
        );
        assert_eq!(order.len(), 3);
    }

    #[test]
    fn ranking_is_stable_on_ties() {
        let mut engine = MlEngine::new();
        let items = vec![(b"worda".to_vec(), 0u64), (b"wordb".to_vec(), 0u64)];
        // Identical features => identical scores => original order preserved.
        let order = engine.rank(&items, ctx(b"", b""));
        assert_eq!(order, vec![0, 1]);
    }

    #[test]
    fn status_reports_the_fallback_backend() {
        let engine = MlEngine::new();
        assert_eq!(engine.kind(), BackendKind::Heuristic);
        assert!(engine.status().starts_with("heuristic:"));
    }

    #[test]
    fn loading_a_missing_model_keeps_the_fallback() {
        let mut engine = MlEngine::new();
        assert!(engine.load_model(Path::new("/nonexistent/model.tflite")) < 0);
        assert_eq!(engine.kind(), BackendKind::Heuristic);
    }
}

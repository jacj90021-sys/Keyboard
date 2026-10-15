//! Inference layer: LiteRT (TensorFlow Lite) and ONNX Runtime, loaded at
//! runtime, plus a pure-Rust reranker that keeps the keyboard fully functional
//! when neither runtime is present.
//!
//! Design rules for this module:
//! * No hard link dependency on any inference library — everything goes through
//!   `dlopen`, so the `.so` always loads on any device.
//! * Every failure is a graceful fallback, never a panic or an abort.

#[cfg(unix)]
pub mod dynlib;
pub mod onnx;
pub mod reranker;
pub mod tflite;

pub use onnx::OnnxInfo;
pub use reranker::{BackendKind, MlEngine, RankContext};

/// A snapshot of which runtimes this device exposes.
#[derive(Clone, Debug, Default)]
pub struct RuntimeReport {
    pub litert: Option<String>,
    pub onnx: Option<OnnxInfo>,
}

impl RuntimeReport {
    /// Detect every supported runtime. Never panics.
    pub fn detect() -> Self {
        Self {
            litert: tflite::probe(),
            onnx: onnx::probe(),
        }
    }

    /// A short, log-friendly summary.
    pub fn summary(&self) -> String {
        let litert = self.litert.as_deref().unwrap_or("none");
        let onnx = self
            .onnx
            .as_ref()
            .map(|info| format!("{}({})", info.library, info.version))
            .unwrap_or_else(|| "none".to_string());
        format!("litert={litert} onnx={onnx}")
    }
}

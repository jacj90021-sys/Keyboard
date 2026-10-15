//! Bindings for the ONNX Runtime Mobile C API.
//!
//! ONNX exposes a versioned function-pointer table rather than flat symbols.
//! This module resolves the stable `OrtApiBase` entry point, which is enough to
//! detect the runtime and report its version. Wiring a full `OrtSession` on top
//! requires the `OrtApi` vtable from `onnxruntime_c_api.h`; that table is
//! intentionally left out here so we never ship offsets we cannot verify
//! against the header. The LiteRT path is the primary inference backend.

use super::dynlib::{resolve, CChar, DynLib};

/// Candidate SONAMEs for the Android/mobile runtimes.
const SONAMES: &[&str] = &["libonnxruntime.so", "libonnxruntime4j_jni.so"];

/// The two leading entry points of `OrtApiBase`, in header order.
#[repr(C)]
struct OrtApiBase {
    get_version_string: unsafe extern "C" fn() -> *const CChar,
    get_api: unsafe extern "C" fn(u32) -> *const std::ffi::c_void,
}

type OrtGetApiBase = unsafe extern "C" fn() -> *const OrtApiBase;

/// Information about a detected ONNX Runtime.
#[derive(Clone, Debug)]
pub struct OnnxInfo {
    pub library: String,
    pub version: String,
}

/// Detect ONNX Runtime and read its self-reported version. Never panics.
pub fn probe() -> Option<OnnxInfo> {
    let lib = DynLib::open(SONAMES)?;
    let get_base: OrtGetApiBase = resolve(&lib, "OrtGetApiBase")?;

    // SAFETY: `OrtGetApiBase` is guaranteed by the runtime to return a valid
    // pointer to its static `OrtApiBase`, whose first field is a function
    // pointer to the version getter.
    let base = unsafe { get_base() };
    if base.is_null() {
        return None;
    }
    let version_ptr = unsafe { ((*base).get_version_string)() };
    let version = if version_ptr.is_null() {
        "unknown".to_string()
    } else {
        // SAFETY: the runtime returns a static NUL-terminated string.
        unsafe { std::ffi::CStr::from_ptr(version_ptr) }
            .to_string_lossy()
            .into_owned()
    };
    let _ = unsafe { ((*base).get_api)(1) };

    Some(OnnxInfo {
        library: lib.name().to_string(),
        version,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_is_graceful_without_a_runtime() {
        // On a host without ONNX Runtime this must return None, never panic.
        let _ = probe();
    }
}

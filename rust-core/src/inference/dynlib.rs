//! Runtime loader for the native inference libraries.
//!
//! The engine binds LiteRT and ONNX Runtime through `dlopen`/`dlsym` rather
//! than at link time. That single decision is what makes the core deployable
//! everywhere: on a device with neither runtime the library still loads and the
//! suggestion pipeline simply falls back, instead of failing to start.

use std::ffi::{c_char, c_void, CString};

/// An opened shared object plus its resolved symbol table.
pub struct DynLib {
    handle: *mut c_void,
    name: String,
}

// The OS dlopen handle is process-global and safe to use from any thread; the
// engine only ever touches it while holding its own mutex.
unsafe impl Send for DynLib {}

impl DynLib {
    /// Try each candidate SONAME in order and keep the first that opens.
    pub fn open(candidates: &[&str]) -> Option<Self> {
        for name in candidates {
            let Ok(cname) = CString::new(*name) else {
                continue;
            };
            // SAFETY: `cname` is a valid NUL-terminated C string and the flags
            // are valid dlopen flags.
            let handle = unsafe { libc::dlopen(cname.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL) };
            if !handle.is_null() {
                return Some(Self {
                    handle,
                    name: (*name).to_string(),
                });
            }
        }
        None
    }

    /// Resolve a single symbol, or `None` when it is not exported.
    pub fn symbol(&self, name: &str) -> Option<*mut c_void> {
        let cname = CString::new(name).ok()?;
        // SAFETY: the handle is live for as long as `self`, and `cname` is a
        // NUL-terminated C string.
        let pointer = unsafe { libc::dlsym(self.handle, cname.as_ptr()) };
        if pointer.is_null() {
            None
        } else {
            Some(pointer)
        }
    }

    /// The SONAME that actually loaded.
    pub fn name(&self) -> &str {
        &self.name
    }
}

impl Drop for DynLib {
    fn drop(&mut self) {
        // SAFETY: the handle was produced by dlopen and is closed exactly once.
        unsafe { libc::dlclose(self.handle) };
    }
}

/// Interpret a resolved symbol as a typed function pointer.
///
/// The caller guarantees the symbol has the signature `T`; this is only ever
/// used with the known C entry points of the inference runtimes.
pub(crate) unsafe fn cast_symbol<T: Copy>(symbol: *mut c_void) -> Option<T> {
    if symbol.is_null() || std::mem::size_of::<T>() != std::mem::size_of::<*mut c_void>() {
        return None;
    }
    // SAFETY: `T` is a function pointer of pointer size, checked above.
    Some(unsafe { std::mem::transmute_copy::<*mut c_void, T>(&symbol) })
}

/// Convenience: resolve `name` and cast it, or `None`.
pub(crate) fn resolve<T: Copy>(lib: &DynLib, name: &str) -> Option<T> {
    let raw = lib.symbol(name)?;
    // SAFETY: the name/signature pairs used by callers are fixed by the
    // runtime C headers.
    unsafe { cast_symbol::<T>(raw) }
}

/// Re-export so callers do not need to import `c_char` themselves.
pub(crate) type CChar = c_char;

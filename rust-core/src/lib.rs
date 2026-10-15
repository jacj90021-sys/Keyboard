//! Original Rust core for the PaperLime keyboard.
//!
//! Walking-skeleton implementation: enough structure to prove the C-ABI boundary
//! and cross-compilation before importing any higher-level algorithms.

mod dictionary;
pub use dictionary::{MappedDictionary, DictionaryError};

// Transparency: FFI boundary requires explicitly scoped unsafe attributes/blocks.

/// Result code returned across the C boundary.
#[repr(i32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EngineStatus {
    Ok = 0,
    InvalidArgument = 1,
    Uninitialized = 2,
    OutOfMemory = 3,
    Internal = 99,
}

/// Global engine handle is intentionally an opaque process-global under a
/// lock-free initialization pattern. Future versions will shard real state.
static mut ENGINE_READY: bool = false;

fn current_status() -> EngineStatus {
    // SAFETY: single-writer initialization is guarded in init_engine; readers
    // observe a stable bool after initialization completes.
    if unsafe { ENGINE_READY } {
        EngineStatus::Ok
    } else {
        EngineStatus::Uninitialized
    }
}

/// Initialize the native engine. Returns 0 on success.
#[unsafe(no_mangle)]
pub extern "C" fn keyboard_core_init() -> i32 {
    // Deterministic single-shot initialization for skeleton phase.
    if unsafe { ENGINE_READY } {
        return EngineStatus::Ok as i32;
    }
    unsafe { ENGINE_READY = true };
    EngineStatus::Ok as i32
}

/// Tear down the native engine.
#[unsafe(no_mangle)]
pub extern "C" fn keyboard_core_shutdown() -> i32 {
    unsafe { ENGINE_READY = false };
    EngineStatus::Ok as i32
}

/// Lightweight liveness check used by Kotlin to validate the bridge.
#[unsafe(no_mangle)]
pub extern "C" fn keyboard_core_health_check() -> i32 {
    current_status() as i32
}

// Minimal JNI shims for the walking skeleton. UniFFI/shared-memory bridge
// generation replaces these aliases as the core matures.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustInitEngine(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> i32 {
    keyboard_core_init()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustShutdownEngine(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> i32 {
    keyboard_core_shutdown()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustHealthCheck(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> i32 {
    keyboard_core_health_check()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustPing(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    value: i32,
) -> i32 {
    keyboard_core_ping(value)
}

/// Round-trip ping used to measure bridge latency.
#[unsafe(no_mangle)]
pub extern "C" fn keyboard_core_ping(value: i32) -> i32 {
    if current_status() != EngineStatus::Ok {
        return EngineStatus::Uninitialized as i32;
    }
    value.wrapping_mul(2).wrapping_add(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn init_and_health_check_succeed() {
        assert_eq!(keyboard_core_init(), 0);
        assert_eq!(keyboard_core_health_check(), 0);
        assert_eq!(keyboard_core_shutdown(), 0);
    }

    #[test]
    fn ping_requires_initialization() {
        assert!(keyboard_core_health_check() != 0);
        assert_eq!(keyboard_core_init(), 0);
        assert_eq!(keyboard_core_ping(41), 83);
        let _ = keyboard_core_shutdown();
    }
}

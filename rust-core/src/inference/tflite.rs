//! Bindings for the LiteRT (TensorFlow Lite) C API.
//!
//! Only the small, stable subset needed to run a single-input/single-output
//! float model is declared. Everything is resolved lazily from the shared
//! library, so a device without LiteRT reports "unavailable" instead of
//! crashing at load.

use super::dynlib::{resolve, CChar, DynLib};
use std::ffi::c_void;
use std::path::Path;

/// Candidate SONAMEs across LiteRT/TFLite packaging variants.
const SONAMES: &[&str] = &[
    "libtensorflowlite_c.so",
    "liblitert.so",
    "libtensorflowlite.so",
];

// Opaque runtime objects. They are only ever touched through the C entry points.
#[repr(C)]
pub struct TfLiteModel {
    _private: [u8; 0],
}
#[repr(C)]
pub struct TfLiteInterpreter {
    _private: [u8; 0],
}
#[repr(C)]
pub struct TfLiteInterpreterOptions {
    _private: [u8; 0],
}
#[repr(C)]
pub struct TfLiteTensor {
    _private: [u8; 0],
}

type ModelCreateFromFile = unsafe extern "C" fn(*const CChar) -> *mut TfLiteModel;
type ModelDelete = unsafe extern "C" fn(*mut TfLiteModel);
type OptionsCreate = unsafe extern "C" fn() -> *mut TfLiteInterpreterOptions;
type OptionsDelete = unsafe extern "C" fn(*mut TfLiteInterpreterOptions);
type OptionsSetNumThreads = unsafe extern "C" fn(*mut TfLiteInterpreterOptions, i32);
type InterpreterCreate = unsafe extern "C" fn(
    *const TfLiteModel,
    *const TfLiteInterpreterOptions,
) -> *mut TfLiteInterpreter;
type InterpreterDelete = unsafe extern "C" fn(*mut TfLiteInterpreter);
type InterpreterAllocate = unsafe extern "C" fn(*mut TfLiteInterpreter) -> i32;
type InterpreterInvoke = unsafe extern "C" fn(*mut TfLiteInterpreter) -> i32;
type InterpreterGetInput = unsafe extern "C" fn(*const TfLiteInterpreter, i32) -> *mut TfLiteTensor;
type InterpreterGetOutput =
    unsafe extern "C" fn(*const TfLiteInterpreter, i32) -> *mut TfLiteTensor;
type TensorByteSize = unsafe extern "C" fn(*const TfLiteTensor) -> usize;
type TensorCopyFrom = unsafe extern "C" fn(*mut TfLiteTensor, *const c_void, usize) -> i32;
type TensorCopyTo = unsafe extern "C" fn(*const TfLiteTensor, *mut c_void, usize) -> i32;

/// Every symbol the engine needs. A missing one makes the runtime unusable and
/// `load` returns `None` rather than half-initialising.
struct Api {
    model_create: ModelCreateFromFile,
    model_delete: ModelDelete,
    options_create: OptionsCreate,
    options_delete: OptionsDelete,
    options_set_threads: OptionsSetNumThreads,
    interpreter_create: InterpreterCreate,
    interpreter_delete: InterpreterDelete,
    interpreter_allocate: InterpreterAllocate,
    interpreter_invoke: InterpreterInvoke,
    interpreter_get_input: InterpreterGetInput,
    interpreter_get_output: InterpreterGetOutput,
    tensor_byte_size: TensorByteSize,
    tensor_copy_from: TensorCopyFrom,
    tensor_copy_to: TensorCopyTo,
}

impl Api {
    fn load(lib: &DynLib) -> Option<Self> {
        Some(Self {
            model_create: resolve(lib, "TfLiteModelCreateFromFile")?,
            model_delete: resolve(lib, "TfLiteModelDelete")?,
            options_create: resolve(lib, "TfLiteInterpreterOptionsCreate")?,
            options_delete: resolve(lib, "TfLiteInterpreterOptionsDelete")?,
            options_set_threads: resolve(lib, "TfLiteInterpreterOptionsSetNumThreads")?,
            interpreter_create: resolve(lib, "TfLiteInterpreterCreate")?,
            interpreter_delete: resolve(lib, "TfLiteInterpreterDelete")?,
            interpreter_allocate: resolve(lib, "TfLiteInterpreterAllocateTensors")?,
            interpreter_invoke: resolve(lib, "TfLiteInterpreterInvoke")?,
            interpreter_get_input: resolve(lib, "TfLiteInterpreterGetInputTensor")?,
            interpreter_get_output: resolve(lib, "TfLiteInterpreterGetOutputTensor")?,
            tensor_byte_size: resolve(lib, "TfLiteTensorByteSize")?,
            tensor_copy_from: resolve(lib, "TfLiteTensorCopyFromBuffer")?,
            tensor_copy_to: resolve(lib, "TfLiteTensorCopyToBuffer")?,
        })
    }
}

/// A loaded model with an allocated interpreter, ready to `run`.
pub struct Session {
    lib: DynLib,
    api: Api,
    interpreter: *mut TfLiteInterpreter,
    model: *mut TfLiteModel,
}

// The session is owned by one engine instance guarded by a mutex.
unsafe impl Send for Session {}

impl Session {
    /// Open the runtime and load `model_path`. Returns `None` (never panics) if
    /// the runtime or the model is not usable on this device.
    pub fn load(model_path: &Path) -> Option<Self> {
        let lib = DynLib::open(SONAMES)?;
        let api = Api::load(&lib)?;

        let path_c = std::ffi::CString::new(model_path.to_string_lossy().as_bytes()).ok()?;
        // SAFETY: `path_c` is NUL-terminated and the API table is fully resolved.
        let model = unsafe { (api.model_create)(path_c.as_ptr()) };
        if model.is_null() {
            return None;
        }

        // SAFETY: the model pointer is live.
        let options = unsafe { (api.options_create)() };
        if options.is_null() {
            unsafe { (api.model_delete)(model) };
            return None;
        }
        // Two worker threads is a good default for a foreground keyboard call.
        unsafe { (api.options_set_threads)(options, 2) };

        let interpreter = unsafe { (api.interpreter_create)(model, options) };
        unsafe { (api.options_delete)(options) };
        if interpreter.is_null() {
            unsafe { (api.model_delete)(model) };
            return None;
        }

        // A failure here means the model is malformed for this runtime.
        if unsafe { (api.interpreter_allocate)(interpreter) } != 0 {
            unsafe { (api.interpreter_delete)(interpreter) };
            unsafe { (api.model_delete)(model) };
            return None;
        }

        Some(Self {
            lib,
            api,
            interpreter,
            model,
        })
    }

    /// The SONAME the session resolved against.
    pub fn library_name(&self) -> &str {
        self.lib.name()
    }

    /// Copy `input` into input tensor 0, invoke, and read output tensor 0 as
    /// floats. Returns `None` on any size mismatch or runtime error.
    pub fn run_f32(&mut self, input: &[f32]) -> Option<Vec<f32>> {
        let float_size = std::mem::size_of::<f32>();
        // SAFETY: the interpreter is live for the whole call and every pointer
        // comes from the resolved API table.
        unsafe {
            let input_tensor = (self.api.interpreter_get_input)(self.interpreter, 0);
            if input_tensor.is_null() {
                return None;
            }
            if (self.api.tensor_copy_from)(
                input_tensor,
                input.as_ptr() as *const c_void,
                std::mem::size_of_val(input),
            ) != 0
            {
                return None;
            }
            if (self.api.interpreter_invoke)(self.interpreter) != 0 {
                return None;
            }
            let output_tensor = (self.api.interpreter_get_output)(self.interpreter, 0);
            if output_tensor.is_null() {
                return None;
            }
            let bytes = (self.api.tensor_byte_size)(output_tensor);
            if bytes == 0 || !bytes.is_multiple_of(float_size) {
                return None;
            }
            let mut out = vec![0.0f32; bytes / float_size];
            if (self.api.tensor_copy_to)(output_tensor, out.as_mut_ptr() as *mut c_void, bytes) != 0
            {
                return None;
            }
            Some(out)
        }
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        // SAFETY: both pointers were created through this session's API table and
        // are released exactly once, interpreter before model.
        unsafe {
            (self.api.interpreter_delete)(self.interpreter);
            (self.api.model_delete)(self.model);
        }
    }
}

/// Probe whether a LiteRT runtime is present, returning its SONAME.
pub fn probe() -> Option<String> {
    let lib = DynLib::open(SONAMES)?;
    // A runtime is only "usable" if its core entry points resolve.
    let _: ModelCreateFromFile = resolve(&lib, "TfLiteModelCreateFromFile")?;
    Some(lib.name().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_is_graceful_without_a_runtime() {
        // On a host without LiteRT this must return None, never panic.
        let _ = probe();
    }

    #[test]
    fn load_missing_model_returns_none() {
        assert!(Session::load(Path::new("/nonexistent/model.tflite")).is_none());
    }
}

//! Original Rust core for the PaperLime keyboard.
//!
//! Walking-skeleton implementation: enough structure to prove the C-ABI boundary
//! and cross-compilation before importing any higher-level algorithms.

mod automaton;
mod dictionary;
mod gesture;
mod inference;
mod touch;
pub use automaton::Automaton;
pub use dictionary::{DictionaryError, MappedDictionary, PromotionTable};
pub use gesture::{rank_stroke, trace_cost, Cell, Trace, TraceVerdict, TraceWeights};
pub use inference::{BackendKind, MlEngine, RankContext, RuntimeReport};
pub use touch::{estimate_pitch, key_gap, qwerty_position, slip_cost, spatial_distance};

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

use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::jint;
use jni::JNIEnv;
use std::path::Path;
use std::sync::Mutex;

static DICTIONARY: Mutex<Option<MappedDictionary>> = Mutex::new(None);

/// Increment applied to a word each time the user accepts or types it.
const PROMOTION_STEP: u64 = 100;

/// Runtime learning table. `PromotionTable` is not `const`-constructible
/// (it owns a `HashMap`), so it is initialised lazily.
static PROMOTIONS: std::sync::LazyLock<Mutex<PromotionTable>> =
    std::sync::LazyLock::new(|| Mutex::new(PromotionTable::new()));

/// Pack suggestion indices into one Unit-Separator-joined string.
fn pack_indices(
    env: &mut JNIEnv,
    dictionary: &MappedDictionary,
    indices: &[usize],
) -> jni::sys::jstring {
    let mut output = String::new();
    let mut first = true;
    for &index in indices {
        if let Some(key) = dictionary.key_at(index) {
            if !first {
                output.push('\u{001f}');
            }
            first = false;
            output.push_str(&String::from_utf8_lossy(key));
        }
    }
    match env.new_string(output) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Run a ranked query against the mapped dictionary plus the promotion table.
///
/// Lock order is always promotions first, then dictionary, so concurrent
/// callers can never deadlock.
fn engine_query(
    env: &mut JNIEnv,
    query: impl FnOnce(&MappedDictionary, &PromotionTable) -> Vec<usize>,
) -> jni::sys::jstring {
    let promotions = match PROMOTIONS.lock() {
        Ok(guard) => guard,
        Err(_) => return std::ptr::null_mut(),
    };
    let dictionary = match DICTIONARY.lock() {
        Ok(guard) => guard,
        Err(_) => return std::ptr::null_mut(),
    };
    let dictionary = match dictionary.as_ref() {
        Some(dictionary) => dictionary,
        None => return std::ptr::null_mut(),
    };
    let indices = query(dictionary, &promotions);
    pack_indices(env, dictionary, &indices)
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustLoadDictionary(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jint {
    let loaded_path: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(_) => return -1,
    };

    match MappedDictionary::load(Path::new(&loaded_path)) {
        Ok(dictionary) => {
            if let Ok(mut guard) = DICTIONARY.lock() {
                *guard = Some(dictionary);
                0
            } else {
                -3
            }
        }
        Err(_) => -2,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionarySize(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if let Ok(guard) = DICTIONARY.lock() {
        guard.as_ref().map(|d| d.entry_count() as i32).unwrap_or(0)
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryExactValue(
    mut env: JNIEnv,
    _class: JClass,
    key: JString,
) -> jni::sys::jstring {
    let requested: String = match env.get_string(&key) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let bytes = requested.as_bytes();
    if let Ok(guard) = DICTIONARY.lock() {
        if let Some(dictionary) = guard.as_ref() {
            if let Some(index) = dictionary.exact_match(bytes) {
                if let Some(value) = dictionary.value_at(index) {
                    if let Ok(result) = env.new_string(String::from_utf8_lossy(value)) {
                        return result.into_raw();
                    }
                }
            }
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryPrefixCount(
    mut env: JNIEnv,
    _class: JClass,
    prefix: JString,
) -> jint {
    let requested: String = match env.get_string(&prefix) {
        Ok(value) => value.into(),
        Err(_) => return 0,
    };

    if let Ok(guard) = DICTIONARY.lock() {
        if let Some(dictionary) = guard.as_ref() {
            let start = dictionary.prefix_start(requested.as_bytes());
            let end = dictionary.prefix_end(requested.as_bytes());
            return (end.saturating_sub(start)) as i32;
        }
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryPrefixKey(
    mut env: JNIEnv,
    _class: JClass,
    prefix: JString,
    index: jint,
) -> jni::sys::jstring {
    let requested: String = match env.get_string(&prefix) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    if index < 0 {
        return std::ptr::null_mut();
    }

    if let Ok(guard) = DICTIONARY.lock() {
        if let Some(dictionary) = guard.as_ref() {
            let start = dictionary.prefix_start(requested.as_bytes());
            let key = dictionary.key_at(start + index as usize);
            if let Some(key_bytes) = key {
                if let Ok(result) = env.new_string(String::from_utf8_lossy(key_bytes)) {
                    return result.into_raw();
                }
            }
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryTopPrefixes(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    prefix: jni::objects::JString,
    max_count: jni::sys::jint,
) -> jni::sys::jstring {
    let requested: String = match env.get_string(&prefix) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let max = if max_count <= 0 {
        4usize
    } else {
        max_count as usize
    };
    let mut output = String::new();

    if let Ok(guard) = DICTIONARY.lock() {
        if let Some(dictionary) = guard.as_ref() {
            let mut first = true;
            for index in dictionary.top_prefixes(requested.as_bytes(), max) {
                if let Some(key) = dictionary.key_at(index) {
                    let text = String::from_utf8_lossy(key);
                    if !first {
                        output.push('\u{001f}');
                    }
                    first = false;
                    output.push_str(&text);
                }
            }
        }
    }

    match env.new_string(output) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryCorrections(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    typo: jni::objects::JString,
    max_count: jni::sys::jint,
) -> jni::sys::jstring {
    let requested: String = match env.get_string(&typo) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let max = if max_count <= 0 {
        4usize
    } else {
        max_count as usize
    };
    let mut output = String::new();

    if let Ok(guard) = DICTIONARY.lock() {
        if let Some(dictionary) = guard.as_ref() {
            let mut first = true;
            for index in dictionary.nearest_words(requested.as_bytes(), max, 2) {
                if let Some(key) = dictionary.key_at(index) {
                    let text = String::from_utf8_lossy(key);
                    if !first {
                        output.push('\u{001f}');
                    }
                    first = false;
                    output.push_str(&text);
                }
            }
        }
    }

    match env.new_string(output) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryAutocorrect(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    word: jni::objects::JString,
) -> jni::sys::jstring {
    let requested: String = match env.get_string(&word) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    // `None` means "leave the word as typed"; a null return tells Kotlin the same.
    let corrected = {
        let promotions = match PROMOTIONS.lock() {
            Ok(guard) => guard,
            Err(_) => return std::ptr::null_mut(),
        };
        let dictionary = match DICTIONARY.lock() {
            Ok(guard) => guard,
            Err(_) => return std::ptr::null_mut(),
        };
        dictionary.as_ref().and_then(|dictionary| {
            dictionary
                .autocorrect_ranked(requested.as_bytes(), &promotions)
                .and_then(|index| dictionary.key_at(index))
                .map(|key| String::from_utf8_lossy(key).into_owned())
        })
    };

    match corrected {
        Some(word) => match env.new_string(word) {
            Ok(result) => result.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryTopPrefixesContextual(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    prefix: jni::objects::JString,
    previous: jni::objects::JString,
    max_count: jni::sys::jint,
) -> jni::sys::jstring {
    let requested: String = match env.get_string(&prefix) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let prior: String = match env.get_string(&previous) {
        Ok(value) => value.into(),
        Err(_) => String::new(),
    };

    let max = if max_count <= 0 {
        4usize
    } else {
        max_count as usize
    };
    let mut output = String::new();

    if let Ok(guard) = DICTIONARY.lock() {
        if let Some(dictionary) = guard.as_ref() {
            let mut first = true;
            for index in dictionary.top_prefixes_contextual(requested.as_bytes(), &prior, max) {
                if let Some(key) = dictionary.key_at(index) {
                    let text = String::from_utf8_lossy(key);
                    if !first {
                        output.push('\u{001f}');
                    }
                    first = false;
                    output.push_str(&text);
                }
            }
        }
    }

    match env.new_string(output) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryTopNextWords(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    previous: jni::objects::JString,
    max_count: jni::sys::jint,
) -> jni::sys::jstring {
    let prior: String = match env.get_string(&previous) {
        Ok(value) => value.into(),
        Err(_) => String::new(),
    };

    let max = if max_count <= 0 {
        4usize
    } else {
        max_count as usize
    };
    let mut output = String::new();

    if let Ok(guard) = DICTIONARY.lock() {
        if let Some(dictionary) = guard.as_ref() {
            let mut first = true;
            for index in dictionary.top_next_words(&prior, max) {
                if let Some(key) = dictionary.key_at(index) {
                    let text = String::from_utf8_lossy(key);
                    if !first {
                        output.push('\u{001f}');
                    }
                    first = false;
                    output.push_str(&text);
                }
            }
        }
    }

    match env.new_string(output) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionarySuggest(
    mut env: JNIEnv,
    _class: JClass,
    prefix: JString,
    previous: JString,
    max_count: jint,
) -> jni::sys::jstring {
    let requested: String = match env.get_string(&prefix) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let prior: String = match env.get_string(&previous) {
        Ok(value) => value.into(),
        Err(_) => String::new(),
    };

    let max = if max_count <= 0 {
        4usize
    } else {
        max_count as usize
    };
    engine_query(&mut env, |dictionary, promotions| {
        dictionary.suggest_ranked(requested.as_bytes(), &prior, max, promotions)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryPromote(
    mut env: JNIEnv,
    _class: JClass,
    word: JString,
) -> jint {
    let requested: String = match env.get_string(&word) {
        Ok(value) => value.into(),
        Err(_) => return -1,
    };
    match PROMOTIONS.lock() {
        Ok(mut guard) => {
            guard.promote(requested.as_bytes(), PROMOTION_STEP);
            0
        }
        Err(_) => -3,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryClearPromotions(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    match PROMOTIONS.lock() {
        Ok(mut guard) => {
            guard.clear();
            0
        }
        Err(_) => -3,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryPromotionCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    PROMOTIONS
        .lock()
        .map(|guard| guard.len() as i32)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustEngineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionaryLoadPromotions(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jint {
    let requested: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(_) => return -1,
    };
    match PROMOTIONS.lock() {
        Ok(mut guard) => match guard.load_from_file(std::path::Path::new(&requested)) {
            Ok(_) => 0,
            Err(_) => -2,
        },
        Err(_) => -3,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustDictionarySavePromotions(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jint {
    let requested: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(_) => return -1,
    };
    match PROMOTIONS.lock() {
        Ok(guard) => match guard.save_to_file(std::path::Path::new(&requested)) {
            Ok(()) => 0,
            Err(_) => -2,
        },
        Err(_) => -3,
    }
}

/// Swipe tuning for a concrete board.
///
/// Every gate in the decoder is a fraction of the *key pitch* (centre-to-centre
/// spacing of neighbouring keys), so the pitch has to come from the board that
/// was actually handed in rather than a constant: the same 36 dp key is ~108 px
/// on a 3x phone and ~72 px on a 2x one. [`touch::estimate_pitch`] recovers it
/// from the grid; if the grid has no horizontal neighbours to measure, the
/// default calibration stands and the decoder still behaves as before.
fn trace_weights_for(grid: &[Option<Cell>; 256]) -> gesture::TraceWeights {
    let mut weights = gesture::TraceWeights::default();
    if let Some(pitch) = touch::estimate_pitch(grid) {
        weights.key_pitch = pitch;
    }
    weights
}

/// Gesture decode boundary.
///
/// `stroke_xy` is a flat `[x0,y0,x1,y1,...]` buffer of finger samples and
/// `layout_xy` is a flat 256-entry-per-char `[cx,cy,hw,hh]` grid indexed by the
/// low byte of each dictionary key. Returns the top matches joined by Unit
/// Separator, mirroring the typing suggest surface so the Kotlin side reuses the
/// same split helper.
#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustGestureDecode(
    mut env: JNIEnv,
    _class: JClass,
    stroke_xy: JFloatArray,
    layout_xy: JFloatArray,
    max_count: jint,
) -> jni::sys::jstring {
    let stroke_len = env.get_array_length(&stroke_xy).unwrap_or(0) as usize;
    let layout_len = env.get_array_length(&layout_xy).unwrap_or(0) as usize;
    if stroke_len < 4 || layout_len < 4 || !layout_len.is_multiple_of(4) {
        return std::ptr::null_mut();
    }

    let mut stroke_buf = vec![0.0f32; stroke_len];
    let mut layout_buf = vec![0.0f32; layout_len];
    if env
        .get_float_array_region(&stroke_xy, 0, &mut stroke_buf)
        .is_err()
    {
        return std::ptr::null_mut();
    }
    if env
        .get_float_array_region(&layout_xy, 0, &mut layout_buf)
        .is_err()
    {
        return std::ptr::null_mut();
    }

    let stroke: Vec<Trace> = stroke_buf
        .as_chunks::<2>()
        .0
        .iter()
        .map(|c| Trace::new(c[0], c[1]))
        .collect();

    // Build the byte -> cell lookup from the flat grid. A zero half-extent means
    // "key absent" and is skipped.
    let mut grid: [Option<Cell>; 256] = [None; 256];
    for (slot, quad) in layout_buf.as_chunks::<4>().0.iter().enumerate().take(256) {
        let (cx, cy, hw, hh) = (quad[0], quad[1], quad[2], quad[3]);
        if hw > 0.0 && hh > 0.0 {
            grid[slot] = Some(Cell::new(cx, cy, hw, hh));
        }
    }

    let max = if max_count <= 0 {
        4usize
    } else {
        max_count as usize
    };
    let promotions = match PROMOTIONS.lock() {
        Ok(guard) => guard,
        Err(_) => return std::ptr::null_mut(),
    };
    let dictionary = match DICTIONARY.lock() {
        Ok(guard) => guard,
        Err(_) => return std::ptr::null_mut(),
    };
    let dictionary = match dictionary.as_ref() {
        Some(dictionary) => dictionary,
        None => return std::ptr::null_mut(),
    };

    let indices = gesture::rank_stroke(
        &stroke,
        dictionary,
        &promotions,
        |b| grid[b as usize],
        trace_weights_for(&grid),
        max,
    );

    pack_indices(&mut env, dictionary, &indices)
}

/// Active ML engine. Starts on the pure-Rust fallback and upgrades to LiteRT
/// only if a model loads, so the static is always usable.
static ML_ENGINE: std::sync::LazyLock<Mutex<MlEngine>> =
    std::sync::LazyLock::new(|| Mutex::new(MlEngine::new()));

/// Maximum candidates considered for ML reranking, to bound the work per call.
const ML_CANDIDATE_LIMIT: usize = 64;

/// Load a `.tflite` reranking model. Returns 0 on success, negative otherwise.
#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustMlLoadModel(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jint {
    let requested: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(_) => return -1,
    };
    match ML_ENGINE.lock() {
        Ok(mut engine) => engine.load_model(Path::new(&requested)),
        Err(_) => -3,
    }
}

/// Drop back to the heuristic reranker.
#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustMlReset(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    match ML_ENGINE.lock() {
        Ok(mut engine) => {
            engine.reset();
            0
        }
        Err(_) => -3,
    }
}

/// Status string such as `heuristic:linear-fallback` or `litert:liblitert.so`.
#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustMlStatus(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let status = match ML_ENGINE.lock() {
        Ok(engine) => engine.status(),
        Err(_) => "unavailable".to_string(),
    };
    match env.new_string(status) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Which inference runtimes this device exposes, for diagnostics.
#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustMlRuntimeReport(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    match env.new_string(RuntimeReport::detect().summary()) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Rerank the dictionary candidates for `prefix`/`previous` through the ML
/// layer and return the top `max` words. Falls back to dictionary order when no
/// model is loaded, since the heuristic scorer reproduces that ordering's
/// intent.
#[no_mangle]
pub extern "system" fn Java_com_example_keyboard_native_NativeEngineBridge_rustMlRank(
    mut env: JNIEnv,
    _class: JClass,
    prefix: JString,
    previous: JString,
    max_count: jint,
) -> jni::sys::jstring {
    let requested: String = match env.get_string(&prefix) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let prior: String = match env.get_string(&previous) {
        Ok(value) => value.into(),
        Err(_) => String::new(),
    };
    let max = if max_count <= 0 {
        4usize
    } else {
        max_count as usize
    };
    let prefix_bytes = requested.as_bytes();

    let promotions = match PROMOTIONS.lock() {
        Ok(guard) => guard,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut engine = match ML_ENGINE.lock() {
        Ok(guard) => guard,
        Err(_) => return std::ptr::null_mut(),
    };
    let dictionary = match DICTIONARY.lock() {
        Ok(guard) => guard,
        Err(_) => return std::ptr::null_mut(),
    };
    let dictionary = match dictionary.as_ref() {
        Some(dictionary) => dictionary,
        None => return std::ptr::null_mut(),
    };

    // Gather the prefix range as (word, effective frequency) pairs. The learned
    // promotions are folded into the frequency so the ML layer sees them too.
    let start = dictionary.prefix_start(prefix_bytes);
    let end = dictionary.prefix_end(prefix_bytes);
    let mut items: Vec<(Vec<u8>, u64)> = Vec::new();
    for index in start..end {
        if items.len() >= ML_CANDIDATE_LIMIT {
            break;
        }
        if let Some(key) = dictionary.key_at(index) {
            let frequency = dictionary.frequency_at(index) + promotions.boost(key);
            items.push((key.to_vec(), frequency));
        }
    }
    if items.is_empty() {
        // No prefix matches: fall back to the next-word candidates so the ML
        // layer still has something meaningful to rank.
        for index in dictionary.top_next_words(&prior, ML_CANDIDATE_LIMIT) {
            if let Some(key) = dictionary.key_at(index) {
                let frequency = dictionary.frequency_at(index) + promotions.boost(key);
                items.push((key.to_vec(), frequency));
            }
        }
    }

    let context = RankContext {
        prefix: prefix_bytes,
        previous: prior.as_bytes(),
    };
    let order = engine.rank(&items, context);

    let mut output = String::new();
    let mut first = true;
    for &position in order.iter().take(max) {
        if let Some((word, _)) = items.get(position) {
            if !first {
                output.push('\u{001f}');
            }
            first = false;
            output.push_str(&String::from_utf8_lossy(word));
        }
    }

    match env.new_string(output) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
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

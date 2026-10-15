# `keyboard_core` — native dictionary engine

Original, cleanroom Rust core for the PaperLime keyboard. It ships as a single
`cdylib`/`staticlib` consumed over a hand-written JNI boundary. There is no
FUTO code, naming, or file layout here — the format and algorithms below are
our own.

## What it does today

- Memory-maps a validated binary dictionary (`PLKD`).
- Binary-searches exact keys and prefix ranges in the mmap.
- Ranks prefix candidates by frequency, exactness, and a small context table.
- Suggests next words after a committed word.
- Falls back to spelling corrections using Levenshtein distance (<= 2),
  ranked by distance then frequency.

## Dictionary format (`PLKD`)

Little-endian throughout. The loader rejects anything malformed; it never
trusts a header value enough to allocate or read out of bounds.

| Offset | Size | Field |
|-------:|-----:|-------|
| 0 | 4 | magic `PLKD` |
| 4 | 4 | format version (`1`) |
| 8 | 4 | entry count |
| 12 | … | packed entries |

Each entry:

| Size | Field |
|-----:|-------|
| 2 | key length (>= 1) |
| 2 | value length (>= 0) |
| N | key bytes (UTF-8, lowercase) |
| M | value bytes (ASCII decimal frequency) |

Keys must be in **strictly ascending UTF-8 byte order** with no duplicates,
which is what enables the binary search. Entries are validated to fit inside
the mapped file, and the declared entry count is bounded by the file size
before any allocation happens.

Regenerate the shipped asset with:

```sh
python3 tools/build_dictionary.py
```

Edit `tools/wordlist.txt` (`<word> <frequency>` per line) to change content.
The builder de-duplicates, sorts, and re-reads its own output before writing.

## Build

Local cross-compile (Android arm64):

```sh
export ANDROID_NDK_HOME=/path/to/ndk
cd rust-core
cargo test
cargo ndk -t aarch64-linux-android build --release
cp target/aarch64-linux-android/release/libkeyboard_core.so \
   ../app/src/main/jniLibs/arm64-v8a/
```

`.github/workflows/native-build.yml` does the same on CI (manual trigger),
plus Kotlin unit tests and the APK.

## JNI surface

Declared in `app/src/main/java/com/example/keyboard/native/NativeEngineBridge.kt`;
the `rust`-prefixed names avoid overload clashes with Kotlin helpers.

| Kotlin (external) | Purpose |
|---|---|
| `rustInitEngine` / `rustShutdownEngine` | engine lifecycle |
| `rustHealthCheck` | `0` when ready |
| `rustPing` | round-trip latency probe |
| `rustLoadDictionary(path)` | map a `PLKD` file |
| `rustDictionarySize` | entry count |
| `rustDictionaryExactValue(key)` | stored frequency for a key |
| `rustDictionaryPrefixCount(prefix)` | prefix range size |
| `rustDictionaryPrefixKey(prefix, index)` | nth key in a prefix range |
| `rustDictionaryTopPrefixes(prefix, max)` | frequency-ranked prefix keys |
| `rustDictionaryTopPrefixesContextual(prefix, previous, max)` | + context boost |
| `rustDictionaryTopNextWords(previous, max)` | likely next words |
| `rustDictionaryCorrections(typo, max)` | edit-distance suggestions |
| `rustGestureDecode(strokeXy, layoutXy, max)` | swipe trajectory → ranked words |
| `rustMlLoadModel(path)` | load a LiteRT reranking model |
| `rustMlReset()` | drop back to the built-in scorer |
| `rustMlStatus()` | active backend, e.g. `heuristic:linear-fallback` |
| `rustMlRuntimeReport()` | which inference runtimes are present |
| `rustMlRank(prefix, previous, max)` | ML-reranked candidates |

List results are returned as a single string joined by the ASCII Unit
Separator (`U+001F`), which keeps the boundary allocation-free per item.

## Design notes

- `panic = "abort"` in release; the parser and queries are written to return
  errors instead of panicking on corrupt input.
- The mapped buffer is read-only (`PROT_READ`, `MAP_SHARED`) and unmapped in
  `Drop`.
- Ranking is deterministic: ties break by dictionary order.
- Tests cover header validation, bounds, ordering, ranking, and a pseudo-random
  no-panic loader sweep.

## Inference layer

`src/inference/` binds LiteRT (TensorFlow Lite) and ONNX Runtime through
`dlopen`, never at link time, so `libkeyboard_core.so` loads on any device:

- `tflite.rs` — resolves the LiteRT C API, loads a `.tflite` model and runs a
  single-input/single-output float graph. Missing runtime or bad model returns
  `None`; nothing panics.
- `onnx.rs` — detects ONNX Runtime and reports its version. A full `OrtSession`
  needs the `OrtApi` vtable from the header and is deliberately not faked.
- `reranker.rs` — one `Reranker` trait with two implementations over a shared
  eight-feature vector: a pure-Rust linear `HeuristicReranker` (always on) and a
  `TfLiteReranker` that takes over when a model loads and defers to the
  heuristic if any invoke fails.

`RuntimeReport::detect()` summarises what the device exposes.

## Not here yet (deliberately)

Voice input and a trained language model are still out of scope. Gesture/swipe
decoding lives in `src/gesture.rs`: an original trajectory scorer using
point-to-segment distance, cosine alignment, and a compound-cost model, wired to
Kotlin via `rustGestureDecode`.

# Native Core Roadmap (overnight work queue)

Goal: keep hardening the original Rust dictionary/typing core and its Kotlin
integration. No new frontend UI, no voice/ML/gesture yet — only things the
existing frontend already consumes.

Status legend: `[x]` done + verified, `[~]` in progress, `[ ]` queued.

## Queue

1. `[x]` **Fix committed manifest drift** — `jni` dependency + `Cargo.lock` were
   only in the working tree, so a clean checkout could not build the crate.
2. `[x]` **Frequency-aware corrections** — rank edit-distance matches by
   `(distance, frequency desc, index)` instead of `(distance, index)`.
3. `[x]` **Parser hardening** — reject absurd `entry_count` before allocating,
   reject unsorted/duplicate keys, reject truncated value bounds.
4. `[x]` **No-panic fuzz test** — feed pseudo-random byte buffers to the loader
   and assert it always returns `Ok`/`Err` without panicking or aborting.
5. `[x]` **Extract testable suggestion planner** — move the Kotlin candidate
   merge rules into a pure function with unit tests.
6. `[x]` **Dictionary build tool** — `tools/build_dictionary.py` + curated
   word list, regenerate the shipped `keyboard_dictionary.bin`.
7. `[x]` **Instrumentation smoke test** — native load/init/query on device.
8. `[x]` **Docs** — document the `PLKD` format and the JNI surface.

## Phase 2

9. `[x]` **Scale the context table** — many more shipped-word bigrams, sorted
   once at runtime, binary-searched instead of linear-scanned.
10. `[x]` **Large-dictionary test** — prove prefix/exact queries stay correct
    when the dictionary has tens of thousands of entries.
11. `[x]` **Bridge fallback tests** — assert the Kotlin wrappers degrade safely
    when the native library is not present.
12. `[x]` **Unified native suggest call** — one boundary call that merges
    prefix, context and correction results in Rust.
13. `[x]` **Case-preserving suggestions** — carry the user's capitalisation
    through to the suggestion chips.

## Phase 3

14. `[x]` **Session learning** — promote accepted suggestions and correctly
    typed words so ranking adapts during a session.
15. `[x]` **Persist learned words** across restarts (bounded, private storage).
16. `[~]` **Locale/language switch** hook for multiple dictionaries. Deferred:
    the existing frontend "language" action is a no-op and there is no second
    dictionary asset, so this would be inventing a feature. Revisit when a real
    language UI exists.
17. `[x]` **Rust integration test** exercising the public API from `tests/`.

## Phase 4 — hardening & tooling

18. `[x]` **Formatting + lints in CI** (`cargo fmt --check`, clippy `-D warnings`).
19. `[x]` **Pinned toolchain** (`rust-toolchain.toml`).
20. `[x]` **Multi-ABI builds** (arm64-v8a + x86_64) for emulator coverage.
21. `[x]` **Engine version query** exposed to Kotlin.
22. `[x]` **Contractions** in the word list (builder now allows apostrophes).
23. `[x]` **Builder unit tests** (`tools/test_build_dictionary.py`), run in CI.

## Phase 5 — gesture decoding

24. `[x]` **Original swipe decoder** — cleanroom `gesture.rs`: point-to-segment
    distance, directional (cosine) alignment, compound-cost normalization,
    bookend grace. Independent of any third-party structure/naming.
25. `[x]` **Gesture JNI surface** — `rustGestureDecode(strokeXy, layoutXy, max)`
    returns ranked words over the existing Unit-Separator channel.
26. `[x]` **Gesture unit tests** — direction ordering, exact segment math,
    bookend detection, empty-input rejection.
27. `[~]` **Live stroke capture** — `Modifier.swipeTyping` observes the Initial
    pointer pass and only consumes after a travel threshold, so taps are
    untouched. Wired into the QWERTY board, gated on the engine being loaded.
    Needs an on-device pass to confirm the threshold feels right.
28. `[~]` **Layout grid export** — `GestureLayoutGrid.qwerty` reproduces the
    Compose weight math analytically (with JVM unit tests). Needs a device
    cross-check against real key bounds.
29. `[x]` **End-to-end swipe test** — `tests/gesture_swipe.rs` swipes through the
    real shipped dictionary and recovers "the", "hello", "world".

## Phase 6 — inference layer

30. `[x]` **Runtime-loaded LiteRT binding** — `inference/tflite.rs` resolves the
    TFLite C API via `dlopen` and runs a float model; missing runtime/model
    degrades to `None`.
31. `[x]` **ONNX detection** — `inference/onnx.rs` resolves `OrtApiBase` and
    reads the version. Full session binding awaits the `OrtApi` vtable.
32. `[x]` **Pluggable reranker** — shared 8-feature vector with a pure-Rust
    linear fallback and a LiteRT implementation behind one trait.
33. `[x]` **ML JNI surface** — `rustMlLoadModel/Reset/Status/RuntimeReport/Rank`.
34. `[ ]` **Ship a counting model** — train/export a small `.tflite` reranker and
    add it to `app/src/main/assets` (needs a training pipeline).
35. `[ ]` **Feature parity test with the model** — golden outputs comparing the
    LiteRT path against the heuristic on a fixed sample set.

## Phase 7 — autocorrect

36. `[x]` **Autocorrect policy** — `MappedDictionary::autocorrect[_ranked]` swaps a
    clearly mistyped word on commit (transposition-aware radius, frequency floor,
    margin over real words, learned-boost respected).
37. `[x]` **Autocorrect JNI + Kotlin** — `rustDictionaryAutocorrect` /
    `dictionaryAutocorrect`; wired into the space-commit path in the keyboard view.
38. `[x]` **Autocorrect tests** — 5 unit tests plus a shipped-dictionary
    integration test and a device assertion.

## Phase 8 — suggestion quality

39. `[x]` **Correction plausibility gate** — suggestion fill-in no longer pads an
    empty result with unrelated short words (`zz` used to suggest
    `be/to/of/a/in`). Two-edit matches now need a shared first letter or a
    reasonably long word; one-edit matches always qualify.
40. `[x]` **Shared close-match ranking** — extracted `ranked_close_matches` so the
    corrections API and suggestion fill-in rank identically.
41. `[x]` **Dictionary content** — added ~36 missing everyday words (including
    `receive`, the target of the classic `recieve` typo); 310 → 346 entries.
42. `[x]` **Diagnostic probe** — `rust-core/examples/suggest_probe.rs` prints live
    suggestions/next-words/autocorrect for eyeballing.

## Phase 9 — real build verification (the 0% that mattered)

43. `[x]` **CI actually builds an APK.** Every previous CI run had failed in
    ~20s for an infrastructure reason, not code: an "Install NDK" step put a
    second SDK under `runner.tool_cache` and pointed `ANDROID_NDK_HOME` at a
    path that did not exist while `cargo-ndk` read `ANDROID_NDK_ROOT`; and the
    rust-toolchain input was `targets` (invalid) so the Android targets were
    never installed. Fixed by reusing the runner's preinstalled SDK/NDK and
    using `target:`. The `apk` job is green: **18 MB debug APK** containing
    `lib/arm64-v8a/libkeyboard_core.so` and `lib/x86_64/libkeyboard_core.so`
    (valid aarch64 ELF, all 27 JNI symbols exported) plus
    `assets/keyboard_dictionary.bin`.
44. `[x]` **Kotlin compiles.** `compileDebugKotlin` succeeds on CI, including the
    native bridge and the autocorrect space-commit path.
45. `[x]` **Robolectric SDK pin.** `testDebugUnitTest` was failing with
    `DefaultSdkProvider` `UnsupportedOperationException` because three tests
    asked for `sdk = [36]`, which Robolectric 4.16.1 cannot provision; repinned
    to 34 to match the rest of the suite.
46. `[~]` **Robolectric + Compose interaction tests.** `KeyboardInteractionTest`
    (13 tests) fails with Espresso `AppNotIdleException`: a Robolectric/Compose
    idling incompatibility, not an app bug (`ImeComposeTest` composes the same
    view and passes by driving the looper directly). Now isolated in a
    non-blocking `unit-tests` job so it cannot hold the APK hostage. TODO:
    migrate it off Espresso-driven idling.

## Constraints

- Rust core stays 100% original / cleanroom.
- Every step: `cargo test`, Android cross-build, Kotlin compile + unit tests,
  then commit and push to `full-project`.

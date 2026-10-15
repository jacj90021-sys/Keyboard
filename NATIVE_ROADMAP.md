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

## Phase 10 — production-scale vocabulary and the performance to carry it

47. `[x]` **Dictionary scale-up: 346 → 46,757 words.** `tools/gen_wordlist.py`
    derives the shipped list from a public 50k-word frequency-ordered corpus
    (the corpus itself is not committed; the generator plus the curated list
    are the deterministic inputs CI rebuilds from, verified with
    `git diff --exit-code`). Builder-legal filtering leaves 46,717 corpus
    words; `tools/wordlist_curated.txt` contributes the 40 contractions
    (`don't`, `you're`, …) the corpus splits into fragments. The shipped
    `keyboard_dictionary.bin` grew 3.6 KB → 638 KB.
48. `[x]` **Self-calibrated frequency curve.** Raw corpus counts would put
    `keyboard` at ~12000 — below the correction floor, so the word the user is
    literally typing could be "corrected" away. Frequencies now compress onto
    a fixed scale (`f = ceil(1000·(rank/N)^0.1531)`) that is calibrated against
    an anchor (`rank 20000 → 200`, the confident bar) rather than the corpus's
    absolute shape, so any corpus size lands on the same usable scale. Anchor
    sweep showed resolution is anchor-independent; `keyboard` = 245, safely
    above the bar, while genuine typos (absent from the corpus) stay on the
    floor path. The 40 curated contractions are revalued into the same band
    (`i'm` = 950 … `don't` = 670) so `don't` outranks `donna` for prefix
    `don`. `tools/test_wordlist_scales.py` pins the calibration in CI.
49. `[x]` **Transposition-aware correction at real scale.** With a real corpus,
    Levenshtein alone misranks the classic typo: `relieve` is one substitution
    from `recieve` while `believe` (rank 197) is two edits away. The fuzzy
    traversal now charges a neighbouring swap as a single edit (Damerau /
    optimal string alignment), so `recieve` → `receive` wins on distance and
    frequency honestly. `automaton.rs` carries a regression test for exactly
    this pair.
50. `[x]` **True minimal automaton (and the honesty that came with it).** The
    trie claimed to be a minimised DAWG, but its states carried the unique
    entry index, which makes every state distinct by construction — measured:
    **zero merges**, 107,834 states, and 47 ms of register hashing spent for
    nothing. States now store only *whether* a word ends there; matched words
    are mapped back to entries by binary search over the sorted entries
    (prefix queries already were). Equivalent states collapse for real:
    107,834 → **21,817 states** (~5× less memory), load 141 → **52 ms**, fuzzy
    queries ~2× faster. A `shared_suffixes_collapse_into_one_state` test pins
    the node counts so the minimiser can't silently degrade again.
51. `[x]` **Fuzzy query path: pooled DP rows.** The edit-distance DFS allocated
    and freed one small vector per visited edge; rows now come from a pool
    (no size caps, no duplicated logic). Combined with 51, `load_bench`
    (added as `rust-core/examples/load_bench.rs`) reports, on x86-64 release:
    load 52 ms, prefix suggest 11 µs, fuzzy fill 0.73 ms, confident autocorrect
    0.2 µs, typo scan 0.73 ms — i.e. the 46k-word vocabulary costs nothing on
    the keystroke paths.

## Phase 11 — the All Actions grid actually does something

52. `[x]` **Editor actions, testable off-device.** New `EditorActions.kt` defines a
    tiny `EditorConnection` interface and dispatches the editing cards onto it:
    copy/cut/paste/select-all and undo/redo through the platform context-menu
    actions, the four cursor arrows, word-case cycling (lowercase → Titlecase →
    UPPERCASE, erased and retyped so multi-byte words survive), and word
    selection. All the decision-making is plain Kotlin with 14 JVM unit tests;
    the IME service is the only place that touches `InputConnection`.
53. `[x]` **Every card is wired.** The grid emitted 22 action ids but the handler
    matched a *different* set, so `cut`, `arrow_up`, `arrow_down`,
    `hide_keyboard`, `modes`, `emojis`, `clipboard`, `settings`, `text_editor`,
    `word_styles` and `auto_correct` were silently inert — and `undo` was wrongly
    wired to backspace. All 22 now do something: overlays and the layout switch
    stay view-local, everything else goes through one `onAction` callback the
    service answers (`modes` cycles QWERTY → NUMPAD → FN, `theme` cycles the four
    shipped themes, `hide_keyboard`/`settings` are service calls). The default
    keeps previews and tests inert.
54. `[x]` **Autocorrect is a preference.** Autocorrect had no off switch at all:
    it is now an `autoCorrect` DataStore pref (default on) with a switch on the
    "Keyboard & typing" page and an All Actions card, and the space-commit path
    consults it live.
55. `[ ]` **The remaining settings pages.** 14 of the 17 settings rows still route
    to `PlaceholderPage`. The honest split: `prediction`, `swipe`, `sound`,
    `personalization` (learned words are already persisted) and `about` map onto
    real prefs/state; `languages` waits on a second dictionary; `emoji`/`premium`
    would be inventing features with no engine behind them.
56. `[x]` **Voice input.** Added `RECORD_AUDIO` permission, permission request
    surface via MainActivity, `SpeechRecognizer` lifecycle in the IME with
    graceful degradation, testable VoiceInput helpers (7 unit tests), and a
    real Voice settings page.
57. `[x]` **Real clipboard history.** The overlay used to show three hard-coded
    demo clips. `ClipboardHistory` now keeps a bounded (24), newest-first,
    de-duplicated list — plain Kotlin with 9 unit tests — and the IME service
    fills it from a `ClipboardManager.OnPrimaryClipChangedListener` that is only
    registered while the keyboard is on screen (a background IME has no business
    reading the user's clipboard). The grid's Paste card performs the platform
    paste; the overlay renders the captured history and can clear it.

## Constraints

- Rust core stays 100% original / cleanroom.
- Every step: `cargo test`, Android cross-build, Kotlin compile + unit tests,
  then commit and push to `full-project`.

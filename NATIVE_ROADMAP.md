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
16. `[ ]` **Locale/language switch** hook for multiple dictionaries.
17. `[x]` **Rust integration test** exercising the public API from `tests/`.

## Phase 4 — hardening & tooling

18. `[x]` **Formatting + lints in CI** (`cargo fmt --check`, clippy `-D warnings`).
19. `[x]` **Pinned toolchain** (`rust-toolchain.toml`).
20. `[x]` **Multi-ABI builds** (arm64-v8a + x86_64) for emulator coverage.
21. `[x]` **Engine version query** exposed to Kotlin.

## Constraints

- Rust core stays 100% original / cleanroom.
- Every step: `cargo test`, Android cross-build, Kotlin compile + unit tests,
  then commit and push to `full-project`.

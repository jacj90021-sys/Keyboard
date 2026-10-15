# PaperLime Rust Core (walking skeleton)

This crate is intentionally minimal. It exists to prove:

1. Cargo can build without blockers.
2. The C-ABI symbols compile.
3. Rust tests catch boundary regressions early.

## Build (host)

```bash
cargo test
```

## Android target (after NDK installation)

```bash
cargo install cargo-ndk
cargo ndk -t aarch64-linux-android build --release
```

## C-ABI contract

- `keyboard_core_init() -> i32`
- `keyboard_core_shutdown() -> i32`
- `keyboard_core_health_check() -> i32`
- `keyboard_core_ping(i32) -> i32`

No algorithms live here yet. Dictionary, scoring, gesture, and ML runtime integration are phased in later.

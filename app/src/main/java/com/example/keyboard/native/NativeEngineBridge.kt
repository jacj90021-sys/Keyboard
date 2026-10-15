package com.example.keyboard.native

/**
 * Contract for the Rust-backed keyboard core.
 *
 * The implementation is intentionally kept lazy so the walking skeleton can be
 * built before the native library is packaged. Once `libkeyboard_core.so` is
 * produced by Cargo/NDK, `System.loadLibrary("keyboard_core")` and the
 * `external` JNI declarations can be enabled.
 */
object NativeEngineBridge {
    @JvmStatic
    private external fun rustInitEngine(): Int

    @JvmStatic
    private external fun rustShutdownEngine(): Int

    @JvmStatic
    private external fun rustHealthCheck(): Int

    @JvmStatic
    private external fun rustPing(value: Int): Int

    private var loaded = false

    fun isLoaded(): Boolean = loaded

    fun tryLoad(): Boolean {
        return try {
            System.loadLibrary("keyboard_core")
            loaded = true
            true
        } catch (_: Throwable) {
            loaded = false
            false
        }
    }

    fun initEngine(): Int = if (loaded) safeCall { rustInitEngine() } else -1
    fun shutdownEngine(): Int = if (loaded) safeCall { rustShutdownEngine() } else -1
    fun healthCheck(): Int = if (loaded) safeCall { rustHealthCheck() } else -1
    fun ping(value: Int): Int = if (loaded) safeCall { rustPing(value) } else -1

    private inline fun safeCall(block: () -> Int): Int {
        return try {
            block()
        } catch (_: Throwable) {
            -1
        }
    }
}

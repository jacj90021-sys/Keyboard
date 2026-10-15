package com.example.keyboard.native

import android.content.Context
import java.io.File
import java.io.FileOutputStream

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

    @JvmStatic
    private external fun rustLoadDictionary(path: String): Int

    @JvmStatic
    private external fun rustDictionarySize(): Int

    @JvmStatic
    private external fun rustDictionaryExactValue(key: String): String?

    @JvmStatic
    private external fun rustDictionaryPrefixCount(prefix: String): Int

    @JvmStatic
    private external fun rustDictionaryPrefixKey(prefix: String, index: Int): String?

    @JvmStatic
    private external fun rustDictionaryTopPrefixes(prefix: String, maxCount: Int): String?

    @JvmStatic
    private external fun rustDictionaryTopPrefixesContextual(
        prefix: String,
        previous: String,
        maxCount: Int
    ): String?

    @JvmStatic
    private external fun rustDictionaryTopNextWords(
        previous: String,
        maxCount: Int
    ): String?

    @JvmStatic
    private external fun rustDictionarySuggest(
        prefix: String,
        previous: String,
        maxCount: Int
    ): String?

    @JvmStatic
    private external fun rustDictionaryPromote(word: String): Int

    @JvmStatic
    private external fun rustDictionaryClearPromotions(): Int

    @JvmStatic
    private external fun rustDictionaryPromotionCount(): Int

    @JvmStatic
    private external fun rustDictionaryLoadPromotions(path: String): Int

    @JvmStatic
    private external fun rustDictionarySavePromotions(path: String): Int

    @JvmStatic
    private external fun rustEngineVersion(): String?

    @JvmStatic
    private external fun rustDictionaryCorrections(typo: String, maxCount: Int): String?

    @JvmStatic
    private external fun rustGestureDecode(
        strokeXy: FloatArray,
        layoutXy: FloatArray,
        maxCount: Int
    ): String?

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

    fun loadDictionary(path: String): Int = if (loaded) safeCall { rustLoadDictionary(path) } else -1

    fun dictionarySize(): Int = if (loaded) safeCall { rustDictionarySize() } else 0

    fun dictionaryExactValue(key: String): String? =
        if (loaded) safeCallNullable { rustDictionaryExactValue(key) } else null

    fun dictionaryPrefixCount(prefix: String): Int =
        if (loaded) safeCall { rustDictionaryPrefixCount(prefix) } else 0

    fun dictionaryPrefixKey(prefix: String, index: Int): String? =
        if (loaded) safeCallNullable { rustDictionaryPrefixKey(prefix, index) } else null

    fun dictionaryTopPrefixes(prefix: String, maxCount: Int): List<String> {
        if (!loaded) return emptyList()
        val raw = safeCallNullable { rustDictionaryTopPrefixes(prefix, maxCount) }
        return raw?.split("\u001f")?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun dictionaryTopPrefixesContextual(
        prefix: String,
        previous: String,
        maxCount: Int
    ): List<String> {
        if (!loaded) return emptyList()
        val raw = safeCallNullable { rustDictionaryTopPrefixesContextual(prefix, previous, maxCount) }
        return raw?.split("\u001f")?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun dictionaryTopNextWords(previous: String, maxCount: Int): List<String> {
        if (!loaded) return emptyList()
        val raw = safeCallNullable { rustDictionaryTopNextWords(previous, maxCount) }
        return raw?.split("\u001f")?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun dictionaryCorrections(typo: String, maxCount: Int): List<String> {
        if (!loaded) return emptyList()
        val raw = safeCallNullable { rustDictionaryCorrections(typo, maxCount) }
        return raw?.split("\u001f")?.filter { it.isNotBlank() } ?: emptyList()
    }

    /**
     * Fused suggestion query. Ranking (prefix vs. correction vs. next-word) is
     * done inside the native engine; this is the single call the UI needs.
     */
    fun dictionarySuggest(prefix: String, previous: String, maxCount: Int): List<String> {
        if (!loaded) return emptyList()
        val raw = safeCallNullable { rustDictionarySuggest(prefix, previous, maxCount) }
        return raw?.split("\u001f")?.filter { it.isNotBlank() } ?: emptyList()
    }

    /**
     * Record that the user chose or typed [word], so the engine can rank it
     * higher next time. Learning is session-scoped and best-effort.
     */
    fun dictionaryPromote(word: String): Int =
        if (loaded && word.isNotBlank()) safeCall { rustDictionaryPromote(word) } else -1

    fun dictionaryClearPromotions(): Int =
        if (loaded) safeCall { rustDictionaryClearPromotions() } else -1

    fun dictionaryPromotionCount(): Int =
        if (loaded) safeCall { rustDictionaryPromotionCount() } else 0

    /** Load previously learned words. Returns 0 on success, negative otherwise. */
    fun dictionaryLoadPromotions(path: String): Int =
        if (loaded) safeCall { rustDictionaryLoadPromotions(path) } else -1

    /** Persist learned words. Returns 0 on success, negative otherwise. */
    fun dictionarySavePromotions(path: String): Int =
        if (loaded) safeCall { rustDictionarySavePromotions(path) } else -1

    /** Native engine version string, or null when the library is unavailable. */
    fun engineVersion(): String? =
        if (loaded) safeCallNullable { rustEngineVersion() } else null

    /**
     * Decode a swipe stroke into ranked words.
     *
     * [strokeXy] is a flat `[x0,y0,x1,y1,...]` buffer of finger samples and
     * [layoutXy] is a flat grid of `[cx,cy,halfWidth,halfHeight]` quads indexed
     * by the ASCII byte of each key. Returns ranked candidates, best first.
     */
    fun gestureDecode(strokeXy: FloatArray, layoutXy: FloatArray, maxCount: Int): List<String> {
        if (!loaded) return emptyList()
        val raw = safeCallNullable { rustGestureDecode(strokeXy, layoutXy, maxCount) }
        return raw?.split("\u001f")?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun loadSampleDictionary(context: Context): Int {
        if (!loaded) tryLoad()
        if (!loaded) return -1

        val target = File(context.filesDir, "keyboard_dictionary.bin")
        if (!target.exists()) {
            context.assets.open("keyboard_dictionary.bin").use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return loadDictionary(target.absolutePath)
    }

    private inline fun safeCallNullable(block: () -> String?): String? {
        return try {
            block()
        } catch (_: Throwable) {
            null
        }
    }

    private inline fun safeCall(block: () -> Int): Int {
        return try {
            block()
        } catch (_: Throwable) {
            -1
        }
    }
}

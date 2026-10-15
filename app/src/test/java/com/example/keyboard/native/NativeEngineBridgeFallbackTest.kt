package com.example.keyboard.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The keyboard must never crash just because the native library is missing
 * (wrong ABI, stripped build, host-side test). These tests pin the safe
 * fallback contract of every wrapper.
 *
 * On a JVM test run `libkeyboard_core.so` is not on the library path, so the
 * engine deliberately stays unloaded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeEngineBridgeFallbackTest {

    @Test
    fun `wrappers are inert without the native library`() {
        assertFalse("host JVM test should not load the Android .so", NativeEngineBridge.tryLoad())
        assertFalse(NativeEngineBridge.isLoaded())

        assertEquals(-1, NativeEngineBridge.initEngine())
        assertEquals(-1, NativeEngineBridge.shutdownEngine())
        assertEquals(-1, NativeEngineBridge.healthCheck())
        assertEquals(-1, NativeEngineBridge.ping(41))
        assertEquals(-1, NativeEngineBridge.loadDictionary("/does/not/exist"))

        assertEquals(0, NativeEngineBridge.dictionarySize())
        assertNull(NativeEngineBridge.dictionaryExactValue("the"))
        assertEquals(0, NativeEngineBridge.dictionaryPrefixCount("th"))
        assertNull(NativeEngineBridge.dictionaryPrefixKey("th", 0))

        assertTrue(NativeEngineBridge.dictionaryTopPrefixes("th", 4).isEmpty())
        assertTrue(NativeEngineBridge.dictionaryTopPrefixesContextual("th", "", 4).isEmpty())
        assertTrue(NativeEngineBridge.dictionaryTopNextWords("good", 4).isEmpty())
        assertTrue(NativeEngineBridge.dictionaryCorrections("teh", 4).isEmpty())
        assertTrue(NativeEngineBridge.dictionarySuggest("th", "", 4).isEmpty())
        assertTrue(NativeEngineBridge.gestureDecode(floatArrayOf(), floatArrayOf(), 4).isEmpty())
        assertEquals(-1, NativeEngineBridge.mlLoadModel("/does/not/exist.tflite"))
        assertEquals(-1, NativeEngineBridge.mlReset())
        assertNull(NativeEngineBridge.mlStatus())
        assertNull(NativeEngineBridge.mlRuntimeReport())
        assertTrue(NativeEngineBridge.mlRank("th", "", 4).isEmpty())
        assertEquals(-1, NativeEngineBridge.dictionaryPromote("hello"))
        assertEquals(-1, NativeEngineBridge.dictionaryClearPromotions())
        assertEquals(0, NativeEngineBridge.dictionaryPromotionCount())
        assertEquals(-1, NativeEngineBridge.dictionaryLoadPromotions("/tmp/learned.tsv"))
        assertEquals(-1, NativeEngineBridge.dictionarySavePromotions("/tmp/learned.tsv"))
        assertNull(NativeEngineBridge.engineVersion())
    }

    @Test
    fun `loadSampleDictionary reports failure without the native library`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(-1, NativeEngineBridge.loadSampleDictionary(context))
    }
}

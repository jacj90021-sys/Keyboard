package com.example.keyboard.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end smoke test for the Rust core on a real device/emulator.
 *
 * The native `.so` is produced by the CI workflow and placed in `jniLibs`, so
 * this runs against the same artifact that ships. If the library is absent (for
 * example a bare local checkout), the test is skipped instead of failing.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentedTest {

    @Test
    fun engineInitialisesAndServesDictionary() {
        assumeTrue(
            "libkeyboard_core.so is not packaged in this build",
            NativeEngineBridge.tryLoad()
        )

        assertEquals(0, NativeEngineBridge.initEngine())
        assertEquals(0, NativeEngineBridge.healthCheck())
        assertEquals(83, NativeEngineBridge.ping(41))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, NativeEngineBridge.loadSampleDictionary(context))
        assertTrue(NativeEngineBridge.dictionarySize() > 0)

        assertNotNull(NativeEngineBridge.dictionaryExactValue("the"))
        val prefixes = NativeEngineBridge.dictionaryTopPrefixes("th", 4)
        assertTrue("expected prefix candidates for 'th'", prefixes.isNotEmpty())

        val next = NativeEngineBridge.dictionaryTopNextWords("good", 4)
        assertTrue("expected next-word candidates after 'good'", next.isNotEmpty())

        val fused = NativeEngineBridge.dictionarySuggest("th", "", 4)
        assertTrue("expected fused suggestions for 'th'", fused.isNotEmpty())

        // Learning path: promoting a word must be accepted and counted.
        assertEquals(0, NativeEngineBridge.dictionaryPromote("hello"))
        assertEquals(1, NativeEngineBridge.dictionaryPromotionCount())
        assertEquals(0, NativeEngineBridge.dictionaryClearPromotions())
        assertEquals(0, NativeEngineBridge.dictionaryPromotionCount())
        assertNotNull(NativeEngineBridge.engineVersion())

        // Learning persists: save, clear, reload, and confirm the word survives.
        val learned = File(context.filesDir, "instrumented_learned_words.tsv")
        assertEquals(0, NativeEngineBridge.dictionaryPromote("keyboard"))
        assertEquals(0, NativeEngineBridge.dictionarySavePromotions(learned.absolutePath))
        assertEquals(0, NativeEngineBridge.dictionaryClearPromotions())
        assertEquals(0, NativeEngineBridge.dictionaryPromotionCount())
        assertEquals(0, NativeEngineBridge.dictionaryLoadPromotions(learned.absolutePath))
        assertEquals(1, NativeEngineBridge.dictionaryPromotionCount())
        learned.delete()

        // Autocorrect: a clear transposition is fixed, a real word is left alone.
        assertEquals("the", NativeEngineBridge.dictionaryAutocorrect("teh"))
        assertTrue(
            "a correctly spelled word must not be autocorrected",
            NativeEngineBridge.dictionaryAutocorrect("the") == null
        )
        assertEquals("receive", NativeEngineBridge.dictionaryAutocorrect("recieve"))

        assertEquals(0, NativeEngineBridge.shutdownEngine())
    }
}

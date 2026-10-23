package com.example

import android.text.TextUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auto-capitalization does not invent a rule for "the first word of each sentence"
 * — it asks Android (InputConnection.getCursorCapsMode) and acts on the answer.
 * These tests pin our reading of that answer against the platform function itself,
 * so the board and the system cannot disagree about where a sentence begins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoCapitalizationTest {

    /** Android's own answer for a caret sitting at the end of [text]. */
    private fun capsAtEndOf(text: String): Int =
        TextUtils.getCapsMode(text, text.length, TextUtils.CAP_MODE_SENTENCES)

    @Test
    fun `only Android's sentence bit counts, not any capital bit`() {
        assertFalse("no capital wanted at all", isSentenceStart(0))
        assertFalse(
            "the word bit alone is not a sentence start",
            isSentenceStart(TextUtils.CAP_MODE_WORDS)
        )
        assertTrue(
            "the sentence bit is exactly what we look for",
            isSentenceStart(TextUtils.CAP_MODE_SENTENCES)
        )
        assertTrue(
            "the bits can arrive together",
            isSentenceStart(TextUtils.CAP_MODE_WORDS or TextUtils.CAP_MODE_SENTENCES)
        )
    }

    @Test
    fun `after a full stop Android asks for a capital`() {
        assertTrue("'Hello. |' starts a sentence", isSentenceStart(capsAtEndOf("Hello. ")))
        assertTrue("'Hello! |' does too", isSentenceStart(capsAtEndOf("Hello! ")))
        assertTrue("'Hello? |' does too", isSentenceStart(capsAtEndOf("Hello? ")))
    }

    @Test
    fun `inside a word Android asks for none`() {
        assertFalse("'Hello|'", isSentenceStart(capsAtEndOf("Hello")))
        assertFalse("'Hello the|'", isSentenceStart(capsAtEndOf("Hello the")))
        assertFalse("'Hello. But|' is still inside the next word", isSentenceStart(capsAtEndOf("Hello. But")))
    }

    @Test
    fun `the board is told nothing before the service has looked`() {
        // The default hint must not raise Shift by itself; only a real answer does.
        assertFalse("nothing is wanted until the service has looked", AutoShiftHint().wanted)
    }
}

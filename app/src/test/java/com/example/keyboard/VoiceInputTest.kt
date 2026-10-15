package com.example.keyboard

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice card used to be inert. These tests pin the Android-free decisions
 * the IME service makes around [android.speech.SpeechRecognizer]: how errors are
 * named, which ones are worth surfacing, and which transcript gets committed.
 */
class VoiceInputTest {

    @Test
    fun errorCodesMapToStableNames() {
        assertEquals("audio", VoiceInput.errorName(SpeechRecognizer.ERROR_AUDIO))
        assertEquals("client", VoiceInput.errorName(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(
            "insufficient_permissions",
            VoiceInput.errorName(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        )
        assertEquals("network", VoiceInput.errorName(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(
            "network_timeout",
            VoiceInput.errorName(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
        )
        assertEquals("no_match", VoiceInput.errorName(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(
            "recognizer_busy",
            VoiceInput.errorName(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        )
        assertEquals("server", VoiceInput.errorName(SpeechRecognizer.ERROR_SERVER))
        assertEquals(
            "speech_timeout",
            VoiceInput.errorName(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        )
    }

    @Test
    fun unknownErrorCodeIsNamedUnknown() {
        assertEquals("unknown", VoiceInput.errorName(Int.MIN_VALUE))
    }

    @Test
    fun errorsTheUserCanActOnAreFlagged() {
        assertTrue(VoiceInput.isActionableError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertTrue(VoiceInput.isActionableError(SpeechRecognizer.ERROR_NETWORK))
        assertTrue(VoiceInput.isActionableError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertTrue(VoiceInput.isActionableError(SpeechRecognizer.ERROR_AUDIO))
        assertTrue(VoiceInput.isActionableError(SpeechRecognizer.ERROR_SERVER))
    }

    @Test
    fun transientErrorsAreNotFlagged() {
        // Engine state or "nothing heard" — retrying the same action is the fix,
        // so there is nothing to surface.
        assertFalse(VoiceInput.isActionableError(SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(VoiceInput.isActionableError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertFalse(VoiceInput.isActionableError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertFalse(VoiceInput.isActionableError(SpeechRecognizer.ERROR_CLIENT))
        assertFalse(VoiceInput.isActionableError(Int.MIN_VALUE))
    }

    @Test
    fun firstCandidateWins() {
        assertEquals(
            "hello world",
            VoiceInput.bestResult(listOf("hello world", "hello word"))
        )
    }

    @Test
    fun emptyOrMissingResultsProduceNothing() {
        assertNull(VoiceInput.bestResult(null))
        assertNull(VoiceInput.bestResult(emptyList()))
    }

    @Test
    fun blankResultsAreNotCommitted() {
        assertNull(VoiceInput.bestResult(listOf("")))
        assertNull(VoiceInput.bestResult(listOf("   ")))
        // A blank first candidate does not veto a real second one.
        assertEquals("hi", VoiceInput.bestResult(listOf("   ", "hi")))
    }

    @Test
    fun transcriptsAreTrimmed() {
        assertEquals("hi there", VoiceInput.bestResult(listOf("  hi there\n")))
    }
}

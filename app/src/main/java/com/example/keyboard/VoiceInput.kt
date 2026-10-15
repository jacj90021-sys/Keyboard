package com.example.keyboard

import android.speech.SpeechRecognizer

/**
 * Android-free decisions for the voice-input path.
 *
 * The [android.speech.SpeechRecognizer] lifecycle itself lives in the IME
 * service (it needs a real `Context` and the main thread), but the small
 * decisions — what an error code means, whether it is worth surfacing, and
 * which transcript to commit — are plain Kotlin so they stay unit tested.
 */
object VoiceInput {

    /** Human-readable name for a [SpeechRecognizer] error code. */
    fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient_permissions"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
        else -> "unknown"
    }

    /**
     * Whether an error is something the user can act on (retry, grant a
     * permission, fix the network) as opposed to a transient engine state such
     * as "busy" or "heard nothing".
     */
    fun isActionableError(code: Int): Boolean = when (code) {
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> true
        else -> false
    }

    /**
     * Pick the transcript to commit from a recognition result list: the first
     * non-blank candidate, trimmed, or null when the engine produced nothing
     * usable. (A blank/whitespace result must never be committed — it would look
     * like a silent no-op or, worse, an empty edit.)
     */
    fun bestResult(results: List<String>?): String? =
        results?.firstOrNull { it.isNotBlank() }?.trim()
}

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
/**
 * Which part of the voice-input lifecycle the keyboard is showing. The IME
 * service publishes this as a [VoiceInputState]; the Compose front end draws the
 * window from it and never touches `SpeechRecognizer` directly.
 */
enum class VoicePhase {
    /** Nothing asked for; the window is not on screen. */
    IDLE,

    /** The recognizer was asked to listen but has not confirmed the mic yet. */
    STARTING,

    /** The mic is live and audio is being transcribed. */
    LISTENING,

    /** A transcript was committed; the window may close itself. */
    DONE,

    /** The engine reported a failure the user can retry. */
    ERROR,

    /** No speech-recognition service exists on this device. */
    UNAVAILABLE,

    /** `RECORD_AUDIO` has not been granted yet. */
    PERMISSION
}

/**
 * Everything the voice window needs to draw itself. [partial] carries the live
 * caption while the user speaks, so the window shows words appearing instead of
 * a silent spinner.
 */
data class VoiceInputState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val partial: String = "",
    val message: String? = null
) {
    /** Whether the mic is open (waiting for the engine or actively recording). */
    val isActive: Boolean
        get() = phase == VoicePhase.STARTING || phase == VoicePhase.LISTENING

    /** Whether the window has anything to show. */
    val isVisible: Boolean
        get() = phase != VoicePhase.IDLE
}

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

    /**
     * The live caption for a partial result: the first non-blank candidate, or
     * [previous] when the engine sent an empty update (a caption must never
     * flicker back to nothing mid-sentence).
     */
    fun partialCaption(results: List<String>?, previous: String): String =
        results?.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() } ?: previous

    /** A short, user-facing sentence explaining a [SpeechRecognizer] error. */
    fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Allow microphone access to use voice input"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Voice input needs a network connection"
        SpeechRecognizer.ERROR_AUDIO ->
            "The microphone is unavailable"
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "Didn't catch that - try again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Voice input is busy - try again"
        SpeechRecognizer.ERROR_SERVER ->
            "The speech service failed - try again"
        else ->
            "Voice input failed - try again"
    }
}

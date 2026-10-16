package com.example.keyboard

import com.example.keyboard.native.NativeEngineBridge
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists the engine's learned-word table without blocking the UI thread.
 *
 * [NativeEngineBridge.dictionaryPromote] keeps the authoritative table in
 * memory (a hash-map insert, microseconds). Persisting it used to happen on
 * every accepted or committed word, which is a synchronous file write on the
 * frame — the classic "keyboard stutters when I press space" symptom.
 *
 * We now coalesce writes: a burst of learning produces at most one write, on a
 * background dispatcher, once typing pauses. [flush] forces a final write when
 * the keyboard leaves the screen so nothing is lost.
 */
object LearningPersistence {
    private const val DEBOUNCE_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pending: Job? = null

    /** Queue a debounced save of the promotion table to [file]. */
    fun scheduleSave(file: File) {
        // Trailing debounce: the timer restarts on each learned word, so the
        // write happens once the user stops typing rather than mid-burst.
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            write(file)
        }
    }

    /** Best-effort final save; safe to call from any thread. */
    fun flush(file: File) {
        pending?.cancel()
        pending = scope.launch { write(file) }
    }

    private suspend fun write(file: File) {
        lock.withLock {
            runCatching { NativeEngineBridge.dictionarySavePromotions(file.absolutePath) }
        }
    }
}

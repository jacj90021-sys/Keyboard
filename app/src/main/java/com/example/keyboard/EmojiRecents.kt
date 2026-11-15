package com.example.keyboard

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Recently used emoji, newest first.
 *
 * The list is persisted as one comma-separated string in the keyboard
 * preferences, so it survives the IME process being killed. Writes are
 * debounced and coalesced: a burst of taps produces a single write rather than
 * one per tap. A null [context] keeps the list in memory only, which is what
 * the unit tests use.
 */
class EmojiRecents(
    private val context: Context? = null,
    private val scope: () -> CoroutineScope = { CoroutineScope(Dispatchers.Unconfined) },
    private val capacity: Int = HISTORY_LIMIT
) {
    private val items = ArrayDeque<String>()
    private var pendingWrite: Job? = null

    init {
        require(capacity > 0) { "capacity must be positive" }
        val stored = context?.let {
            runBlocking { it.keyboardDataStore.data.first()[PrefKeys.emojiHistory].orEmpty() }
        }.orEmpty()
        stored.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(capacity)
            .forEach { items.addLast(it) }
    }

    /** Emoji newest-first, ready to render. */
    fun entries(): List<String> = items.toList()

    val size: Int get() = items.size

    /**
     * Records [emoji] as the most recent. Blanks are ignored and an emoji that
     * is already present moves to the front instead of being duplicated.
     * Returns whether the visible list changed.
     */
    fun add(emoji: String?): Boolean {
        val normalized = emoji?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val existing = items.indexOf(normalized)
        if (existing == 0) return false
        if (existing > 0) items.removeAt(existing)
        items.addFirst(normalized)
        while (items.size > capacity) items.removeLast()
        scheduleWrite()
        return true
    }

    /** Writes immediately; used when the editor goes away so a force-stop cannot lose the last tap. */
    fun flush() {
        pendingWrite?.cancel()
        pendingWrite = null
        write()
    }

    private fun scheduleWrite() {
        pendingWrite?.cancel()
        pendingWrite = scope().launch {
            delay(WRITE_DEBOUNCE_MS)
            write()
        }
    }

    private fun write() {
        val target = context ?: return
        val serialized = items.joinToString(SEPARATOR)
        scope().launch {
            target.keyboardDataStore.edit { it[PrefKeys.emojiHistory] = serialized }
        }
    }

    companion object {
        /** Enough to fill the first rows of the Recent tab without growing unbounded. */
        const val HISTORY_LIMIT = 28
        private const val SEPARATOR = ","
        private const val WRITE_DEBOUNCE_MS = 350L
    }
}

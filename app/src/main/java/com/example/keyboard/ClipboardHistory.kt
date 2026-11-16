package com.example.keyboard

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One remembered clipboard item. */
data class ClipItem(
    val text: String,
    val pinned: Boolean = false,
    val createdAt: Long = 0L
)

/**
 * A bounded, newest-first clipboard history.
 *
 * Android only exposes the current primary clip, so the IME service listens for
 * primary-clip changes and feeds them here. The list is persisted as a small
 * JSON file in the app's files dir, so it survives the process being killed;
 * writes are debounced so a burst of copies produces one write.
 *
 * A null [context] keeps the list in memory only, which is what the unit tests
 * use.
 */
class ClipboardHistory(
    private val context: Context? = null,
    private val scope: () -> CoroutineScope = { CoroutineScope(Dispatchers.Unconfined) },
    capacity: Int = DEFAULT_CAPACITY
) {
    private var limit = capacity
    private val items = ArrayDeque<ClipItem>()
    private var pendingWrite: Job? = null

    init {
        require(capacity > 0) { "capacity must be positive" }
        load()
    }

    /** Items newest-first, pinned state included. */
    fun items(): List<ClipItem> = items.toList()

    /** Text only, newest-first; kept for callers that just need the strings. */
    fun entries(): List<String> = items.map { it.text }

    val size: Int get() = items.size

    /**
     * Records [text] as the newest clip. Blank clips are ignored. A repeat of an
     * existing clip moves it back to the front (keeping its pin) instead of
     * duplicating it. Returns whether the visible list changed.
     */
    fun add(text: String?): Boolean {
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val existing = items.indexOfFirst { it.text == normalized }
        if (existing == 0) return false
        val wasPinned = existing > 0 && items[existing].pinned
        if (existing > 0) items.removeAt(existing)
        items.addFirst(ClipItem(normalized, wasPinned, System.currentTimeMillis()))
        trimToLimit()
        scheduleSave()
        return true
    }

    /** Flips the pin on the item with [text]. */
    fun togglePin(text: String): Boolean {
        val index = items.indexOfFirst { it.text == text }
        if (index < 0) return false
        items[index] = items[index].copy(pinned = !items[index].pinned)
        scheduleSave()
        return true
    }

    /** Removes the item with [text]. */
    fun remove(text: String): Boolean {
        val index = items.indexOfFirst { it.text == text }
        if (index < 0) return false
        items.removeAt(index)
        scheduleSave()
        return true
    }

    /** Drop every remembered clip; the system clipboard is left untouched. */
    fun clear() {
        items.clear()
        scheduleSave()
    }

    /** Changes the cap at runtime and trims the oldest unpinned clips to fit. */
    fun resize(newCapacity: Int) {
        if (newCapacity <= 0 || newCapacity == limit) return
        limit = newCapacity
        trimToLimit()
        scheduleSave()
    }

    /** Writes immediately; used when the editor goes away. */
    fun flush() {
        pendingWrite?.cancel()
        pendingWrite = null
        save()
    }

    private fun trimToLimit() {
        while (items.size > limit) {
            val unpinned = items.indexOfLast { !it.pinned }
            if (unpinned >= 0) items.removeAt(unpinned) else items.removeLast()
        }
    }

    private fun scheduleSave() {
        pendingWrite?.cancel()
        pendingWrite = scope().launch {
            delay(SAVE_DEBOUNCE_MS)
            save()
        }
    }

    private fun save() {
        val target = context ?: return
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("text", item.text)
                    .put("pinned", item.pinned)
                    .put("createdAt", item.createdAt)
            )
        }
        scope().launch {
            runCatching {
                val file = File(target.filesDir, FILE_NAME)
                file.writeText(array.toString())
            }
        }
    }

    private fun load() {
        val target = context ?: return
        runCatching {
            val file = File(target.filesDir, FILE_NAME)
            if (!file.exists()) return@runCatching
            val array = JSONArray(file.readText())
            val restored = ArrayDeque<ClipItem>()
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val text = entry.optString("text").trim()
                if (text.isNotEmpty()) {
                    restored.addLast(
                        ClipItem(
                            text = text,
                            pinned = entry.optBoolean("pinned", false),
                            createdAt = entry.optLong("createdAt", 0L)
                        )
                    )
                }
            }
            items.clear()
            items.addAll(restored)
            trimToLimit()
        }
    }

    companion object {
        /** Roughly a screenful of clips: enough to be useful, cheap to hold. */
        const val DEFAULT_CAPACITY = 25
        private const val FILE_NAME = "clipboard_history.json"
        private const val SAVE_DEBOUNCE_MS = 350L
    }
}
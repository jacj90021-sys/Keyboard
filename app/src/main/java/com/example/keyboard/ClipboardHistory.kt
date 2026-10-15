package com.example.keyboard

/**
 * A bounded, newest-first clipboard history.
 *
 * Android only exposes the current primary clip, so a keyboard that wants a
 * history has to accumulate one itself: the IME service listens for primary-clip
 * changes and feeds them here. Keeping the accumulation in a plain class (rather
 * than in the service) makes the de-duplication and capacity rules unit testable
 * off-device.
 *
 * The history is capped because it is held in memory for the lifetime of the IME
 * process; copying a long document should never cost unbounded memory.
 */
class ClipboardHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    // Newest first; ArrayDeque gives O(1) push-front and tail removal.
    private val items = ArrayDeque<String>()

    /** Items newest-first, ready to render. */
    fun entries(): List<String> = items.toList()

    val size: Int get() = items.size

    /**
     * Record [text] as the newest clip. Blank clips are ignored. A repeat of an
     * existing clip moves it back to the front instead of duplicating it.
     *
     * Returns whether the visible list changed, so callers can skip a state
     * update for a no-op.
     */
    fun add(text: String?): Boolean {
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val existingIndex = items.indexOf(normalized)
        if (existingIndex == 0) return false
        if (existingIndex > 0) {
            items.removeAt(existingIndex)
            items.addFirst(normalized)
            return true
        }
        items.addFirst(normalized)
        while (items.size > capacity) items.removeLast()
        return true
    }

    /** Drop every remembered clip; the system clipboard is left untouched. */
    fun clear() {
        items.clear()
    }

    companion object {
        /** Roughly a screenful of clips: enough to be useful, cheap to hold. */
        const val DEFAULT_CAPACITY = 24
    }
}

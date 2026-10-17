package com.example.keyboard

/**
 * A bounded, newest-first list of recently used emoji.
 *
 * FUTO's picker opens on a "Recent" tab, and Android gives us nothing of the
 * sort, so the IME service accumulates the list as emoji are typed. Keeping the
 * rules in a plain class (rather than in the service) makes the de-duplication
 * and capacity behaviour unit testable off-device — the same shape as
 * [ClipboardHistory].
 *
 * The list is capped because it is held for the lifetime of the IME process.
 */
class EmojiRecents(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    // Newest first; ArrayDeque gives O(1) push-front and tail removal.
    private val items = ArrayDeque<String>()

    /** Emoji newest-first, ready to render. */
    fun entries(): List<String> = items.toList()

    val size: Int get() = items.size

    /**
     * Record [emoji] as the most recently used. Blank values are ignored, and an
     * emoji that is already present moves back to the front instead of being
     * duplicated.
     *
     * Returns whether the visible list changed, so callers can skip a state
     * update for a no-op.
     */
    fun add(emoji: String?): Boolean {
        val normalized = emoji?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val existingIndex = items.indexOf(normalized)
        if (existingIndex == 0) return false
        if (existingIndex > 0) items.removeAt(existingIndex)
        items.addFirst(normalized)
        while (items.size > capacity) items.removeLast()
        return true
    }

    companion object {
        /** Enough to fill the Recent tab's first rows without growing unbounded. */
        const val DEFAULT_CAPACITY = 24
    }
}

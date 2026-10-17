package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's "Recent" tab is fed by [EmojiRecents]. These tests pin the rules
 * the IME service relies on (newest-first, de-duplicated, bounded).
 */
class EmojiRecentsTest {

    @Test
    fun mostRecentlyUsedComesFirst() {
        val recents = EmojiRecents()
        assertTrue(recents.add("😀"))
        assertTrue(recents.add("😂"))
        assertEquals(listOf("😂", "😀"), recents.entries())
    }

    @Test
    fun blanksAreIgnored() {
        val recents = EmojiRecents()
        assertFalse(recents.add(null))
        assertFalse(recents.add(""))
        assertFalse(recents.add("   "))
        assertTrue(recents.entries().isEmpty())
    }

    @Test
    fun repeatsMoveToTheFrontInsteadOfDuplicating() {
        val recents = EmojiRecents()
        recents.add("👍")
        recents.add("❤️")
        recents.add("🌿")
        assertTrue(recents.add("👍"))
        assertEquals(listOf("👍", "🌿", "❤️"), recents.entries())
    }

    @Test
    fun usingTheNewestEmojiAgainIsANoOp() {
        val recents = EmojiRecents()
        recents.add("😎")
        assertFalse(recents.add("😎"))
        assertEquals(listOf("😎"), recents.entries())
    }

    @Test
    fun capacityDropsTheOldestEmoji() {
        val recents = EmojiRecents(capacity = 3)
        recents.add("a")
        recents.add("b")
        recents.add("c")
        recents.add("d")
        assertEquals(listOf("d", "c", "b"), recents.entries())
        assertEquals(3, recents.size)
    }

    @Test
    fun revisitingAnEvictedEmojiKeepsTheCapacity() {
        val recents = EmojiRecents(capacity = 2)
        recents.add("a")
        recents.add("b")
        recents.add("c")
        recents.add("a") // "a" was evicted, so this is a fresh insert
        assertEquals(listOf("a", "c"), recents.entries())
    }

    @Test(expected = IllegalArgumentException::class)
    fun capacityMustBePositive() {
        EmojiRecents(capacity = 0)
    }
}

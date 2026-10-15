package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard overlay used to render three hard-coded demo strings. These
 * tests pin the rules of the real history the IME service accumulates.
 */
class ClipboardHistoryTest {

    @Test
    fun newestClipComesFirst() {
        val history = ClipboardHistory()
        assertTrue(history.add("first"))
        assertTrue(history.add("second"))
        assertEquals(listOf("second", "first"), history.entries())
    }

    @Test
    fun blankClipsAreIgnored() {
        val history = ClipboardHistory()
        assertFalse(history.add(null))
        assertFalse(history.add(""))
        assertFalse(history.add("   "))
        assertTrue(history.entries().isEmpty())
    }

    @Test
    fun clipsAreTrimmed() {
        val history = ClipboardHistory()
        history.add("  hello \n")
        assertEquals(listOf("hello"), history.entries())
    }

    @Test
    fun repeatsMoveToTheFrontInsteadOfDuplicating() {
        val history = ClipboardHistory()
        history.add("one")
        history.add("two")
        history.add("three")
        assertTrue(history.add("one"))
        assertEquals(listOf("one", "three", "two"), history.entries())
    }

    @Test
    fun copyingTheNewestClipAgainIsANoOp() {
        val history = ClipboardHistory()
        history.add("same")
        assertFalse(history.add("same"))
        assertEquals(listOf("same"), history.entries())
        // Trimming happens before the duplicate check, so stray whitespace does
        // not create a second copy of the same clip.
        assertFalse(history.add(" same "))
        assertEquals(listOf("same"), history.entries())
    }

    @Test
    fun capacityDropsTheOldestClips() {
        val history = ClipboardHistory(capacity = 3)
        history.add("a")
        history.add("b")
        history.add("c")
        history.add("d")
        assertEquals(listOf("d", "c", "b"), history.entries())
        assertEquals(3, history.size)
    }

    @Test
    fun revisitingAnEvictedClipKeepsTheCapacity() {
        val history = ClipboardHistory(capacity = 2)
        history.add("a")
        history.add("b")
        history.add("c")
        history.add("a") // "a" was evicted, so this is a fresh insert
        assertEquals(listOf("a", "c"), history.entries())
    }

    @Test
    fun clearEmptiesTheHistory() {
        val history = ClipboardHistory()
        history.add("x")
        history.clear()
        assertTrue(history.entries().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun capacityMustBePositive() {
        ClipboardHistory(capacity = 0)
    }
}

package com.example.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fn key draws its Esc / Tab / Ctrl / ... chips in the strip above the keys,
 * so that strip must not depend on the "Word suggestions" switch. This pins the
 * decoupling: with suggestions off the strip is still there whenever Fn is on,
 * so Fn keeps working on its own.
 */
class KeyboardPrefsTest {

    @Test
    fun stripDisappearsOnlyWhenBothSuggestionsAndFnAreOff() {
        assertFalse(KeyboardPrefs(suggestions = false, fn = false).showStrip)
    }

    @Test
    fun suggestionsAloneKeepTheStrip() {
        assertTrue(KeyboardPrefs(suggestions = true, fn = false).showStrip)
    }

    @Test
    fun fnAloneKeepsTheStripEvenWithSuggestionsOff() {
        assertTrue(KeyboardPrefs(suggestions = false, fn = true).showStrip)
    }

    @Test
    fun autoCapitalizationDefaultsOnLikeFuto() {
        // FUTO ships with auto-capitalization on; the board's Shift key is raised
        // for the user until they say otherwise.
        assertTrue(KeyboardPrefs().autoCap)
    }

    @Test
    fun chevronNeedsTheToolbarTheArrowAndAStrip() {
        // The normal case.
        assertTrue(KeyboardPrefs(toolbar = true, arrow = true, suggestions = true).showChevron)
        // Fn + toolbar, suggestions off: the ^ is still offered.
        assertTrue(
            KeyboardPrefs(toolbar = true, arrow = true, suggestions = false, fn = true).showChevron
        )
        // Each of the three ingredients removed in turn.
        assertFalse(
            KeyboardPrefs(toolbar = false, arrow = true, suggestions = true, fn = true).showChevron
        )
        assertFalse(
            KeyboardPrefs(toolbar = true, arrow = false, suggestions = true, fn = true).showChevron
        )
        assertFalse(
            KeyboardPrefs(toolbar = true, arrow = true, suggestions = false, fn = false).showChevron
        )
    }
}

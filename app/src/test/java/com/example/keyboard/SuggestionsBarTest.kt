package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggestion strip used to fall back to a hardcoded "Yes / Okay / Thanks /
 * Awesome" list whenever the engine had nothing to offer, so the keyboard showed
 * words the user never typed. These tests pin the real rule: only genuine
 * suggestions, or the Fn keys while Fn is active.
 */
class SuggestionsBarTest {

    @Test
    fun realSuggestionsWin() {
        assertEquals(
            listOf("hello", "help"),
            visibleSuggestions(listOf("hello", "help"), isFnMode = false)
        )
    }

    @Test
    fun realSuggestionsWinEvenInFnMode() {
        assertEquals(
            listOf("hello"),
            visibleSuggestions(listOf("hello"), isFnMode = true)
        )
    }

    @Test
    fun fnModeShowsTheFnKeys() {
        assertEquals(fnSuggestions, visibleSuggestions(emptyList(), isFnMode = true))
    }

    @Test
    fun nothingTypedShowsNothing() {
        assertTrue(visibleSuggestions(emptyList(), isFnMode = false).isEmpty())
    }

    @Test
    fun noPlaceholderWordsAreEverReturned() {
        val placeholders = setOf("Yes", "Okay", "Thanks", "Awesome")
        val shown = visibleSuggestions(emptyList(), isFnMode = false) +
            visibleSuggestions(emptyList(), isFnMode = true)
        assertTrue(
            "strip must not invent filler words, got $shown",
            shown.none { it in placeholders }
        )
    }
}

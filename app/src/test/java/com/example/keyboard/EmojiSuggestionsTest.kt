package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Emoji Suggestions is a shortcut list, not a picture search, so these tests pin
 * both halves of the promise: a word we know gets its emoji, and a word we do not
 * know gets nothing (no guessing, no partial matches in the middle of a word).
 */
class EmojiSuggestionsTest {

    @Test
    fun knownWordGetsItsEmoji() {
        assertEquals("\u2764\uFE0F", EmojiSuggestions.forTypedWord("love"))
        assertEquals("\uD83D\uDE02", EmojiSuggestions.forTypedWord("laugh"))
        assertEquals("\uD83C\uDF55", EmojiSuggestions.forTypedWord("pizza"))
    }

    @Test
    fun caseDoesNotMatter() {
        assertEquals(
            EmojiSuggestions.forTypedWord("love"),
            EmojiSuggestions.forTypedWord("Love")
        )
        assertEquals(
            EmojiSuggestions.forTypedWord("pizza"),
            EmojiSuggestions.forTypedWord("PIZZA")
        )
    }

    @Test
    fun shortWordsAreLeftAlone() {
        // Two-letter words are almost always meant literally: no emoji for "it",
        // "no" or "go", however tempting the flag would be.
        assertNull(EmojiSuggestions.forTypedWord("it"))
        assertNull(EmojiSuggestions.forTypedWord("no"))
        assertNull(EmojiSuggestions.forTypedWord(""))
    }

    @Test
    fun onlyAWholeWordMatches() {
        // Half-typed words must not flash an emoji, and a word that merely starts
        // with a known one is a different word.
        assertNull(EmojiSuggestions.forTypedWord("lov"))
        assertNull(EmojiSuggestions.forTypedWord("loves"))
        assertNull(EmojiSuggestions.forTypedWord("happybirthday"))
    }

    @Test
    fun theEmojiComesFirstAndTheListStaysCapped() {
        val words = listOf("first", "second", "third", "fourth")
        val offered = EmojiSuggestions.withSuggestion("love", words, max = 4)
        assertEquals("\u2764\uFE0F", offered.first())
        assertEquals(4, offered.size)
        // The lowest-ranked word is the one that gives up its slot.
        assertEquals(listOf("\u2764\uFE0F", "first", "second", "third"), offered)
    }

    @Test
    fun anUnchangedListIsHandedBackUntouched() {
        val words = listOf("alpha", "beta")
        assertSame(words, EmojiSuggestions.withSuggestion("zzz", words))
    }
}

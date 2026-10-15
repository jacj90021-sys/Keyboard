package com.example.keyboard.native

import java.util.Locale

/**
 * Restores the user's capitalisation onto a suggestion.
 *
 * The engine stores lowercase words, but if the user started the word with a
 * capital (auto-capitalisation or the shift key) the committed word should match
 * how they were typing. Kept pure and locale-independent so it is easy to test.
 */
object SuggestionCase {

    /**
     * @param typedPrefix the characters the user actually typed for the current
     *   word, preserving case.
     * @param word the lowercase dictionary suggestion.
     * @return [word] capitalised to follow the shape of [typedPrefix].
     */
    fun apply(typedPrefix: String, word: String): String {
        val letters = typedPrefix.filter { it.isLetter() }
        if (letters.isEmpty()) return word
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) {
            return word.uppercase(Locale.ROOT)
        }
        if (letters.first().isUpperCase()) {
            return word.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }
        return word
    }
}

package com.example.keyboard

import com.example.keyboard.native.SuggestionPlanner
import java.util.Locale

/**
 * "Emoji Suggestions": type a word we know an emoji for and that emoji is offered
 * in the strip above the keys, in front of the word suggestions — "love" offers
 * ❤️ before anything else.
 *
 * The list is deliberately small and hand-written, ours rather than a copy of any
 * emoji project's data. That is also the honest limit of the setting: it is a
 * shortcut list, not a picture search, so a word that is not in [words] simply
 * gets no emoji suggestion.
 */
object EmojiSuggestions {

    /**
     * Words shorter than this are left alone: two-letter words are almost always
     * meant literally, and hijacking them would put a flag in front of "it". FUTO
     * guards its emoji shortcuts with the same rule.
     */
    const val MIN_LENGTH = 3

    /** The words we know an emoji for. Keys are lowercase; all are ≥ [MIN_LENGTH]. */
    private val words: Map<String, String> = mapOf(
        "love" to "\u2764\uFE0F",
        "heart" to "\u2764\uFE0F",
        "happy" to "\uD83D\uDE0A",
        "smile" to "\uD83D\uDE0A",
        "laugh" to "\uD83D\uDE02",
        "lol" to "\uD83D\uDE02",
        "sad" to "\uD83D\uDE22",
        "cry" to "\uD83D\uDE2D",
        "wink" to "\uD83D\uDE09",
        "cool" to "\uD83D\uDE0E",
        "yes" to "\uD83D\uDC4D",
        "thanks" to "\uD83D\uDE4F",
        "please" to "\uD83D\uDE4F",
        "sorry" to "\uD83D\uDE14",
        "kiss" to "\uD83D\uDE18",
        "hug" to "\uD83E\uDD17",
        "party" to "\uD83C\uDF89",
        "cake" to "\uD83C\uDF82",
        "coffee" to "\u2615",
        "pizza" to "\uD83C\uDF55",
        "beer" to "\uD83C\uDF7A",
        "music" to "\uD83C\uDFB5",
        "fire" to "\uD83D\uDD25",
        "star" to "\u2B50",
        "sun" to "\u2600\uFE0F",
        "moon" to "\uD83C\uDF19",
        "rain" to "\uD83C\uDF27\uFE0F",
        "snow" to "\u2744\uFE0F",
        "cat" to "\uD83D\uDC31",
        "dog" to "\uD83D\uDC36",
        "flower" to "\uD83C\uDF38",
        "rose" to "\uD83C\uDF39",
        "tree" to "\uD83C\uDF33",
        "car" to "\uD83D\uDE97",
        "phone" to "\uD83D\uDCF1",
        "book" to "\uD83D\uDCDA",
        "money" to "\uD83D\uDCB0",
        "gift" to "\uD83C\uDF81",
        "sleep" to "\uD83D\uDE34",
        "tired" to "\uD83D\uDE34",
        "sick" to "\uD83E\uDD12",
        "angry" to "\uD83D\uDE20",
        "wow" to "\uD83D\uDE2E",
        "fireworks" to "\uD83C\uDF86"
    )

    /**
     * The emoji offered for [typed] — only when the whole word matches one we
     * know, so no emoji appears in the middle of a real word. Case is ignored:
     * "Love" at the start of a sentence still offers ❤️.
     */
    fun forTypedWord(typed: String): String? {
        val word = typed.lowercase(Locale.ROOT)
        if (word.length < MIN_LENGTH) return null
        return words[word]
    }

    /**
     * [candidates] with the current word's emoji in front, still capped at [max],
     * or [candidates] untouched when there is nothing to offer.
     */
    fun withSuggestion(
        typed: String,
        candidates: List<String>,
        max: Int = SuggestionPlanner.MAX_CANDIDATES
    ): List<String> {
        val emoji = forTypedWord(typed) ?: return candidates
        if (max <= 0) return emptyList()
        return (listOf(emoji) + candidates).distinct().take(max)
    }
}

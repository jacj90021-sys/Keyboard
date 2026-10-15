package com.example.keyboard.native

/**
 * Thin policy gate for the suggestion bar.
 *
 * Ranking itself lives in the Rust engine behind a single `suggest` call so the
 * policy is implemented once. This object only applies the boundary rules that
 * must hold regardless of the engine: a sane cap and de-duplication.
 */
object SuggestionPlanner {
    const val MAX_CANDIDATES = 4

    fun candidates(
        prefix: String,
        previousWord: String,
        max: Int = MAX_CANDIDATES,
        suggest: (String, String, Int) -> List<String>
    ): List<String> {
        if (max <= 0) return emptyList()
        return suggest(prefix, previousWord, max).distinct().take(max)
    }
}

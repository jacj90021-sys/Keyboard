package com.example.keyboard.native

/**
 * Pure candidate-selection rules for the suggestion bar.
 *
 * This intentionally avoids Android and JNI types so the ranking/merge policy
 * can be unit-tested on the JVM. [NativeEngineBridge] is injected as plain
 * lambdas, which keeps the real keyboard and the tests on the same code path.
 */
object SuggestionPlanner {
    const val MAX_CANDIDATES = 4

    /** Prefixes shorter than this are too ambiguous to query the dictionary. */
    const val MIN_PREFIX_LENGTH = 2

    /**
     * Choose the words shown in the suggestion bar.
     *
     * Priority:
     * 1. While typing a word of [MIN_PREFIX_LENGTH] or more characters, use
     *    contextual prefix matches; fall back to spelling corrections only when
     *    the prefix matches nothing.
     * 2. After a word has been committed, offer likely next words.
     * 3. Otherwise show nothing and let the bar use its default content.
     */
    fun candidates(
        prefix: String,
        previousWord: String,
        max: Int = MAX_CANDIDATES,
        topPrefixesContextual: (String, String, Int) -> List<String>,
        corrections: (String, Int) -> List<String>,
        nextWords: (String, Int) -> List<String>
    ): List<String> {
        if (max <= 0) return emptyList()
        return when {
            prefix.length >= MIN_PREFIX_LENGTH -> {
                val prefixes = topPrefixesContextual(prefix, previousWord, max)
                val merged = if (prefixes.isEmpty()) corrections(prefix, max) else prefixes
                merged.distinct().take(max)
            }

            previousWord.isNotBlank() -> nextWords(previousWord, max).distinct().take(max)

            else -> emptyList()
        }
    }
}

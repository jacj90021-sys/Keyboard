package com.example.keyboard.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the suggestion-bar policy without needing the native library: the
 * planner is pure and the bridge calls are injected as fakes.
 */
class SuggestionPlannerTest {

    private val noPrefix: (String, String, Int) -> List<String> = { _, _, _ -> emptyList() }
    private val noCorrections: (String, Int) -> List<String> = { _, _ -> emptyList() }
    private val noNext: (String, Int) -> List<String> = { _, _ -> emptyList() }

    private fun plan(
        prefix: String,
        previous: String = "",
        max: Int = SuggestionPlanner.MAX_CANDIDATES,
        prefixes: (String, String, Int) -> List<String> = noPrefix,
        corrections: (String, Int) -> List<String> = noCorrections,
        next: (String, Int) -> List<String> = noNext
    ) = SuggestionPlanner.candidates(prefix, previous, max, prefixes, corrections, next)

    @Test
    fun `single character prefix stays silent`() {
        val result = plan(prefix = "h", prefixes = { _, _, _ -> listOf("hello") })
        assertTrue(result.isEmpty())
    }

    @Test
    fun `two character prefix queries contextual matches`() {
        var seenPrefix = ""
        var seenPrevious = ""
        val result = plan(
            prefix = "he",
            previous = "say",
            prefixes = { p, prev, _ ->
                seenPrefix = p
                seenPrevious = prev
                listOf("hello", "help")
            }
        )
        assertEquals(listOf("hello", "help"), result)
        assertEquals("he", seenPrefix)
        assertEquals("say", seenPrevious)
    }

    @Test
    fun `empty prefix matches fall back to corrections`() {
        val result = plan(
            prefix = "helo",
            prefixes = { _, _, _ -> emptyList() },
            corrections = { _, _ -> listOf("hello", "help") }
        )
        assertEquals(listOf("hello", "help"), result)
    }

    @Test
    fun `corrections are ignored when prefix matches exist`() {
        var correctionsCalled = false
        val result = plan(
            prefix = "he",
            prefixes = { _, _, _ -> listOf("hello") },
            corrections = { _, _ ->
                correctionsCalled = true
                listOf("jello")
            }
        )
        assertEquals(listOf("hello"), result)
        assertTrue("corrections must not run when prefixes matched", !correctionsCalled)
    }

    @Test
    fun `results are deduplicated and capped`() {
        val result = plan(
            prefix = "he",
            max = 2,
            prefixes = { _, _, _ -> listOf("hello", "hello", "help", "helm") }
        )
        assertEquals(listOf("hello", "help"), result)
    }

    @Test
    fun `committed word offers next words`() {
        var seenPrevious = ""
        val result = plan(
            prefix = "",
            previous = "good",
            next = { prev, _ ->
                seenPrevious = prev
                listOf("morning", "night")
            }
        )
        assertEquals(listOf("morning", "night"), result)
        assertEquals("good", seenPrevious)
    }

    @Test
    fun `no state means no candidates`() {
        assertTrue(plan(prefix = "", previous = "").isEmpty())
    }

    @Test
    fun `non positive max disables suggestions`() {
        assertTrue(
            plan(prefix = "he", max = 0, prefixes = { _, _, _ -> listOf("hello") }).isEmpty()
        )
    }
}

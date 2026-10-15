package com.example.keyboard.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner is a thin gate over the engine's fused `suggest` call. Ranking is
 * covered by the Rust tests; here we only pin the boundary rules.
 */
class SuggestionPlannerTest {

    private fun plan(
        prefix: String,
        previous: String = "",
        max: Int = SuggestionPlanner.MAX_CANDIDATES,
        suggest: (String, String, Int) -> List<String> = { _, _, _ -> emptyList() }
    ) = SuggestionPlanner.candidates(prefix, previous, max, suggest)

    @Test
    fun `prefix and previous word are forwarded to the engine`() {
        var seenPrefix = ""
        var seenPrevious = ""
        var seenMax = -1
        val result = plan(
            prefix = "he",
            previous = "say",
            suggest = { p, prev, m ->
                seenPrefix = p
                seenPrevious = prev
                seenMax = m
                listOf("hello", "help")
            }
        )
        assertEquals(listOf("hello", "help"), result)
        assertEquals("he", seenPrefix)
        assertEquals("say", seenPrevious)
        assertEquals(SuggestionPlanner.MAX_CANDIDATES, seenMax)
    }

    @Test
    fun `engine results are deduplicated and capped`() {
        val result = plan(
            prefix = "he",
            max = 2,
            suggest = { _, _, _ -> listOf("hello", "hello", "help", "helm") }
        )
        assertEquals(listOf("hello", "help"), result)
    }

    @Test
    fun `empty engine result stays empty`() {
        assertTrue(plan(prefix = "he").isEmpty())
        assertTrue(plan(prefix = "", previous = "good").isEmpty())
    }

    @Test
    fun `non positive max disables suggestions`() {
        var called = false
        val result = plan(
            prefix = "he",
            max = 0,
            suggest = { _, _, _ ->
                called = true
                listOf("hello")
            }
        )
        assertTrue(result.isEmpty())
        assertTrue("engine must not be called when disabled", !called)
    }
}

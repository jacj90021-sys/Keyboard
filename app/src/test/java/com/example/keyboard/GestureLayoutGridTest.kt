package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analytic grid must agree with how [QwertyLayoutView] actually lays keys
 * out, otherwise swipe decoding points at the wrong geometry. These tests pin
 * the invariants that matter: ordering, centring and row separation.
 */
class GestureLayoutGridTest {

    private val width = 1080f
    private val density = 3f
    private val keyHeight = 44

    private fun grid(withNumberRow: Boolean = false) =
        GestureLayoutGrid.qwerty(width, density, keyHeight, withNumberRow)

    private fun centre(g: FloatArray, ch: Char): Float {
        val base = ch.code * 4
        return g[base]
    }

    private fun centreY(g: FloatArray, ch: Char): Float {
        val base = ch.code * 4
        return g[base + 1]
    }

    private fun halfWidth(g: FloatArray, ch: Char): Float = g[ch.code * 4 + 2]

    @Test
    fun `top row is evenly spaced and centred`() {
        val g = grid()
        // 'q' sits half a key in from the left edge.
        assertEquals(width / 20f, centre(g, 'q'), 0.5f)
        // 't' and 'y' straddle the horizontal midpoint.
        val expected = width / 2f
        assertEquals(expected, (centre(g, 't') + centre(g, 'y')) / 2f, 0.5f)
        assertTrue(centre(g, 'p') > width * 0.9f)
    }

    @Test
    fun `home row is inset and monotonic`() {
        val g = grid()
        val a = centre(g, 'a')
        val l = centre(g, 'l')
        assertTrue("a must sit right of the wide-row start", a > 0f)
        assertTrue(a < l)
        assertTrue(l < width)
    }

    @Test
    fun `rows step down by key height plus gap`() {
        val g = grid()
        val row1 = centreY(g, 'q')
        val row2 = centreY(g, 'a')
        val row3 = centreY(g, 'z')
        val step = keyHeight * density + 4f * density
        assertEquals(step, row2 - row1, 0.5f)
        assertEquals(step, row3 - row2, 0.5f)
    }

    @Test
    fun `number row pushes the letters down`() {
        val plain = grid(withNumberRow = false)
        val withNumbers = grid(withNumberRow = true)
        assertTrue(centreY(withNumbers, 'q') > centreY(plain, 'q'))
    }

    @Test
    fun `half extents are positive for letters and zero elsewhere`() {
        val g = grid()
        assertTrue(halfWidth(g, 'a') > 0f)
        assertTrue(halfWidth(g, 'm') > 0f)
        // Digits are not part of the letter grid.
        assertEquals(0f, halfWidth(g, '1'), 0f)
        assertEquals(0f, g['1'.code * 4 + 3], 0f)
    }

    @Test
    fun `degenerate board produces an empty grid`() {
        val g = GestureLayoutGrid.qwerty(0f, density, keyHeight, false)
        assertEquals(0f, halfWidth(g, 'a'), 0f)
    }
}

package com.example.keyboard

/**
 * Analytic geometry for the letter board.
 *
 * The Compose layouts place keys with `Modifier.weight(...)`, so their pixel
 * centres are a pure function of the board size, key height and the weight
 * tables below. Reproducing that math here lets the native gesture decoder know
 * where every key sits without instrumenting the UI tree.
 *
 * Only the letters the dictionary can contain (a-z, plus the apostrophe for
 * contractions) are emitted. Special keys (shift, backspace, space) carry no
 * dictionary signal and are deliberately skipped.
 */
object GestureLayoutGrid {

    /** Rows of the QWERTY layer, matching [QwertyLayoutView]. */
    private val ROW_TOP = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val ROW_HOME = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val ROW_BOTTOM = listOf("z", "x", "c", "v", "b", "n", "m")

    /** Horizontal padding applied to the home row in [QwertyLayoutView] (dp). */
    private const val HOME_ROW_PADDING_DP = 14f

    /** Gap between key rows in [QwertyLayoutView] (dp). */
    private const val ROW_GAP_DP = 4f

    /** Shift/backspace weight in the bottom letter row. */
    private const val WIDE_KEY_WEIGHT = 1.4f

    /** Vertical offset from the top of the layout box (outer + inner padding, dp). */
    private const val TOP_INSET_DP = 8f

    /** Height factor of the optional number row. */
    private const val NUMBER_ROW_SCALE = 0.8f

    /**
     * Build a flat `[cx,cy,halfWidth,halfHeight]` grid indexed by the ASCII byte
     * of each key, ready for `NativeEngineBridge.gestureDecode`.
     *
     * @param boardWidthPx  usable width of the board in pixels
     * @param densityDpToPx display density (pixels per dp)
     * @param keyHeightDp   value of `prefs.keyHeight`
     * @param withNumberRow whether the optional number row is showing
     */
    fun qwerty(
        boardWidthPx: Float,
        densityDpToPx: Float,
        keyHeightDp: Int,
        withNumberRow: Boolean
    ): FloatArray {
        val grid = FloatArray(256 * 4)
        if (boardWidthPx <= 0f || densityDpToPx <= 0f || keyHeightDp <= 0) return grid

        val keyHeight = keyHeightDp * densityDpToPx
        val gap = ROW_GAP_DP * densityDpToPx
        val homePad = HOME_ROW_PADDING_DP * densityDpToPx

        var y = TOP_INSET_DP * densityDpToPx
        if (withNumberRow) {
            // Mirrors the NumberRowView box: 2dp vertical padding + 0.8-height keys.
            y += 2f * densityDpToPx + keyHeight * NUMBER_ROW_SCALE + gap
        }

        // Row 1: ten equal keys spanning the full width.
        val topKeyWidth = boardWidthPx / ROW_TOP.size
        placeRow(
            grid,
            ROW_TOP,
            startX = 0f,
            keyWidth = topKeyWidth,
            centreY = y + keyHeight / 2f,
            keyHeight = keyHeight,
            edgePadPx = densityDpToPx
        )

        // Row 2: nine equal keys inset by the home-row padding.
        val homeKeyWidth = (boardWidthPx - 2f * homePad) / ROW_HOME.size
        placeRow(
            grid,
            ROW_HOME,
            startX = homePad,
            keyWidth = homeKeyWidth,
            centreY = y + (keyHeight + gap) + keyHeight / 2f,
            keyHeight = keyHeight,
            edgePadPx = densityDpToPx
        )

        // Row 3: shift (1.4) + seven letters (1.0 each) + backspace (1.4).
        // The letters begin after the wide shift key.
        val bottomUnit = boardWidthPx / (2f * WIDE_KEY_WEIGHT + ROW_BOTTOM.size)
        val bottomLettersStart = WIDE_KEY_WEIGHT * bottomUnit
        placeRow(
            grid,
            ROW_BOTTOM,
            startX = bottomLettersStart,
            keyWidth = bottomUnit,
            centreY = y + 2f * (keyHeight + gap) + keyHeight / 2f,
            keyHeight = keyHeight,
            edgePadPx = densityDpToPx
        )

        return grid
    }

    private fun placeRow(
        grid: FloatArray,
        labels: List<String>,
        startX: Float,
        keyWidth: Float,
        centreY: Float,
        keyHeight: Float,
        edgePadPx: Float
    ) {
        // SoftKey insets each key by 1dp horizontally; mirror it so hit-boxes
        // match what the user actually sees.
        val halfWidth = (keyWidth / 2f) - edgePadPx
        val halfHeight = keyHeight / 2f
        labels.forEachIndexed { index, label ->
            if (label.length != 1) return@forEachIndexed
            val code = label[0].code
            if (code !in 0..255) return@forEachIndexed
            val cx = startX + (index + 0.5f) * keyWidth
            val base = code * 4
            grid[base] = cx
            grid[base + 1] = centreY
            grid[base + 2] = halfWidth
            grid[base + 3] = halfHeight
        }
    }
}

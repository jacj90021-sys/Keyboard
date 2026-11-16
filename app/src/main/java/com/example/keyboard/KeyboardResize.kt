package com.example.keyboard

/**
 * Keyboard sizing model for the Resize Keyboard screen.
 *
 * Clean-room design: the four modes (Standard, One hand, Split, Float) are
 * described by a small, serialisable settings object. The settings screen edits
 * it with drag handles; the keyboard view reads it to size and place itself.
 *
 * All measurements are in dp unless noted. The object is stored as one compact
 * string preference so the DataStore stays readable and no extra keys are added.
 */

enum class ResizeMode(val key: String, val label: String) {
    STANDARD("standard", "Standard"),
    ONE_HANDED("one_hand", "One hand"),
    SPLIT("split", "Split"),
    FLOATING("float", "Float");

    companion object {
        fun fromKey(key: String?): ResizeMode =
            entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

enum class ResizeSide(val key: String, val label: String) {
    LEFT("left", "Left"),
    RIGHT("right", "Right");

    companion object {
        fun fromKey(key: String?): ResizeSide =
            entries.firstOrNull { it.key == key } ?: RIGHT
    }
}

data class ResizeSettings(
    val mode: ResizeMode = ResizeMode.STANDARD,
    // Symmetric horizontal inset, and the gap above the navigation bar.
    val sidePadding: Float = 0f,
    val bottomPadding: Float = 0f,
    // Extra key height on top of the base key height.
    val heightAddition: Float = 0f,
    // One hand.
    val oneHandedSide: ResizeSide = ResizeSide.RIGHT,
    val oneHandedWidth: Float = 320f,
    // Split: the fraction of the width the keyboard occupies (rest is the gap).
    val splitFraction: Float = 0.6f,
    // Floating: size and bottom-left origin.
    val floatingWidth: Float = 360f,
    val floatingHeight: Float = 220f,
    val floatingX: Float = 0f,
    val floatingY: Float = 0f
) {
    /** Effective key height including the resize addition, clamped to the board's
     *  supported range so the layout never collapses. */
    fun effectiveKeyHeight(base: Int): Int =
        (base + heightAddition).toInt().coerceIn(34, 60)

    fun encode(): String = listOf(
        mode.key,
        sidePadding, bottomPadding, heightAddition,
        oneHandedSide.key, oneHandedWidth, splitFraction,
        floatingWidth, floatingHeight, floatingX, floatingY
    ).joinToString(",")

    companion object {
        val Default = ResizeSettings()

        fun decode(raw: String?): ResizeSettings {
            if (raw.isNullOrBlank()) return Default
            val p = raw.split(",")
            if (p.size < 11) return Default
            return try {
                ResizeSettings(
                    mode = ResizeMode.fromKey(p[0]),
                    sidePadding = p[1].toFloat(),
                    bottomPadding = p[2].toFloat(),
                    heightAddition = p[3].toFloat(),
                    oneHandedSide = ResizeSide.fromKey(p[4]),
                    oneHandedWidth = p[5].toFloat(),
                    splitFraction = p[6].toFloat(),
                    floatingWidth = p[7].toFloat(),
                    floatingHeight = p[8].toFloat(),
                    floatingX = p[9].toFloat(),
                    floatingY = p[10].toFloat()
                )
            } catch (_: Exception) {
                Default
            }
        }
    }
}

/** Drag limits, mirroring the reference behaviour (kept in dp). */
object ResizeLimits {
    const val MAX_SIDE_PADDING = 64f
    const val MAX_BOTTOM_PADDING = 72f
    const val MIN_HEIGHT_ADDITION = -20f
    const val MAX_HEIGHT_ADDITION = 40f
    const val MIN_ONE_HANDED_WIDTH = 180f
    const val MAX_ONE_HANDED_WIDTH = 520f
    const val MIN_SPLIT_FRACTION = 0.1f
    const val MAX_SPLIT_FRACTION = 0.9f
    const val MIN_FLOATING_WIDTH = 180f
    const val MAX_FLOATING_WIDTH = 520f
    const val MIN_FLOATING_HEIGHT = 140f
    const val MAX_FLOATING_HEIGHT = 360f
}
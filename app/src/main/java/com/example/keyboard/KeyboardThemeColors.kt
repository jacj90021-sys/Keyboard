package com.example.keyboard

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.Tokens

/**
 * Keyboard theme model. Add a new theme by adding one entry to [KeyboardThemes].
 *
 * Colours the REAL keyboard: background, key fill, key text, pressed state,
 * enter/active-shift key (accent), toolbar icons and the suggestion strip.
 */
enum class ThemeChoice(val displayName: String, val prefKey: String) {
    PAPER("Paper", "paper"),
    INK("Ink", "ink"),
    LIME("Lime", "lime"),
    BLUSH("Blush", "blush");

    companion object {
        fun fromKey(key: String?): ThemeChoice =
            entries.firstOrNull { it.prefKey == key } ?: PAPER
    }
}

data class KeyboardTheme(
    val id: ThemeChoice,
    val bg: Color,
    val key: Color,
    val text: Color,
    val accent: Color,
    val accentText: Color,
    val accentShadow: Color,
    val keyShadow: Color,
    val line: Color,
    val muted: Color,
    val functionKey: Color
)

val KeyboardThemes: List<KeyboardTheme> = listOf(
    KeyboardTheme(
        id = ThemeChoice.PAPER,
        bg = Color(0xFFE9E6D9),
        key = Color(0xFFFFFEF9),
        text = Color(0xFF292B23),
        accent = Color(0xFFD6F36A),
        accentText = Color(0xFF25271F),
        accentShadow = Color(0xFFC3DF58),
        keyShadow = Color(0xFFB9B6A6),
        line = Color(0xFFE4E0D3),
        muted = Color(0xFF77796D),
        functionKey = Color(0xFFE3E0D2)
    ),
    KeyboardTheme(
        id = ThemeChoice.INK,
        bg = Color(0xFF1D1F19),
        key = Color(0xFF2F3229),
        text = Color(0xFFF1F0E8),
        accent = Color(0xFFD6F36A),
        accentText = Color(0xFF25271F),
        accentShadow = Color(0xFF9EB837),
        keyShadow = Color(0xFF101109),
        line = Color(0xFF3A3D32),
        muted = Color(0xFF9A9D8F),
        functionKey = Color(0xFF23251E)
    ),
    KeyboardTheme(
        id = ThemeChoice.LIME,
        bg = Color(0xFFD6F36A),
        key = Color(0xFFF6FCD5),
        text = Color(0xFF25271F),
        accent = Color(0xFF292B23),
        accentText = Color(0xFFFFFFFF),
        accentShadow = Color(0xFF1B1D17),
        keyShadow = Color(0xFFB6CE55),
        line = Color(0xFFC9E45C),
        muted = Color(0xFF6B7250),
        functionKey = Color(0xFFBFE04C)
    ),
    KeyboardTheme(
        id = ThemeChoice.BLUSH,
        bg = Color(0xFFF1D9D0),
        key = Color(0xFFFFF8F5),
        text = Color(0xFF3A2620),
        accent = Color(0xFFE8836A),
        accentText = Color(0xFFFFFFFF),
        accentShadow = Color(0xFFC96A52),
        keyShadow = Color(0xFFDDB2A4),
        line = Color(0xFFEAD3C9),
        muted = Color(0xFF8A6E63),
        functionKey = Color(0xFFEBC5B8)
    )
)

fun themeFor(choice: ThemeChoice): KeyboardTheme =
    KeyboardThemes.firstOrNull { it.id == choice } ?: KeyboardThemes.first()

data class KeyboardPalette(
    val paper: Color,
    val paperSubtle: Color,
    val paperDeep: Color,
    val keyBg: Color,
    val ink: Color,
    val inkSecondary: Color,
    val muted: Color,
    val lime: Color,
    val onAccent: Color,
    val limeShadow: Color,
    val line: Color,
    val keyShadow: Color,
    val specialKey: Color,
    val specialShadow: Color,
    val functionBg: Color,
    val canvasBg: Color
)

fun getPalette(choice: ThemeChoice): KeyboardPalette {
    val t = themeFor(choice)
    return KeyboardPalette(
        paper = t.bg,
        paperSubtle = t.key,
        paperDeep = t.keyShadow,
        keyBg = t.key,
        ink = t.text,
        inkSecondary = t.text.copy(alpha = 0.82f),
        muted = t.muted,
        lime = t.accent,
        onAccent = t.accentText,
        limeShadow = t.accentShadow,
        line = t.line,
        keyShadow = t.keyShadow,
        specialKey = t.key,
        specialShadow = t.keyShadow,
        functionBg = t.functionKey,
        // Settings/app background is ALWAYS paper (light only).
        canvasBg = Tokens.Paper
    )
}

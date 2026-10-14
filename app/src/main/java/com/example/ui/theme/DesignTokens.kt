package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R

/**
 * Single source of truth for the Paper + Lime design tokens.
 * Light only, NO dark mode. The app background is always paper.
 */
object Tokens {
    val Paper = Color(0xFFF3F0E7)
    val Surface = Color(0xFFFFFEF9)
    val Ink = Color(0xFF292B23)
    val Muted = Color(0xFF77796D)
    val Line = Color(0xFFE9E6DC)
    val Lime = Color(0xFFD6F36A)
    val LimeDeep = Color(0xFFC3DF58)
    val Chip = Color(0xFFF3F1E8)
}

// Manrope (headings)
val Manrope = FontFamily(
    Font(R.font.manrope, FontWeight.Normal),
    Font(R.font.manrope, FontWeight.Medium),
    Font(R.font.manrope, FontWeight.SemiBold),
    Font(R.font.manrope, FontWeight.Bold),
    Font(R.font.manrope, FontWeight.ExtraBold)
)

// DM Sans (body)
val DmSans = FontFamily(
    Font(R.font.dmsans, FontWeight.Normal),
    Font(R.font.dmsans, FontWeight.Medium),
    Font(R.font.dmsans, FontWeight.SemiBold),
    Font(R.font.dmsans, FontWeight.Bold)
)

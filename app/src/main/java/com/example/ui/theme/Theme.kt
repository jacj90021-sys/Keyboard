package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Light-only theme. There is intentionally NO dark mode; background is always paper.
 */
private val LightColorScheme = lightColorScheme(
    primary = Tokens.Lime,
    secondary = Tokens.LimeDeep,
    tertiary = Tokens.Chip,
    background = Tokens.Paper,
    surface = Tokens.Paper,
    onPrimary = Tokens.Ink,
    onSecondary = Tokens.Ink,
    onBackground = Tokens.Ink,
    onSurface = Tokens.Ink
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}

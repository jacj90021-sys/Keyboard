package com.example.keyboard

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Rendering options derived from user preferences, shared by every layout. */
data class KeyRenderConfig(
    val keyHeight: Dp = 44.dp,
    val showSubText: Boolean = false,
    val showFnKey: Boolean = false,
    val showPopups: Boolean = true
)

@Composable
fun NumberRowView(
    palette: KeyboardPalette,
    config: KeyRenderConfig,
    onKeyClick: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        "1234567890".forEach { digit ->
            SoftKey(
                text = digit.toString(),
                palette = palette,
                keyHeight = config.keyHeight * 0.8f,
                showPopup = config.showPopups,
                modifier = Modifier.weight(1f),
                onClick = { onKeyClick(digit.toString()) }
            )
        }
    }
}

@Composable
fun QwertyLayoutView(
    palette: KeyboardPalette,
    config: KeyRenderConfig,
    shiftState: ShiftState,
    onKeyClick: (String) -> Unit,
    onLongKeyClick: (String) -> Unit,
    onShiftClick: () -> Unit,
    onBackspaceClick: () -> Unit,
    onSymbolsClick: () -> Unit,
    onFnClick: () -> Unit,
    onFnLongClick: () -> Unit = {},
    isFnLongActive: Boolean = false,
    onSpaceClick: () -> Unit,
    onEnterClick: () -> Unit,
    onSpaceDrag: (Float) -> Unit
) {
    val isUpper = shiftState != ShiftState.OFF
    val row1 = listOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
    )
    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1
        Row(modifier = Modifier.fillMaxWidth()) {
            row1.forEach { (char, num) ->
                val display = if (isUpper) char.uppercase() else char
                SoftKey(
                    text = display,
                    subText = if (config.showSubText) num else null,
                    palette = palette,
                    keyHeight = config.keyHeight,
                    showPopup = config.showPopups,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyClick(display) },
                    onLongClick = { onLongKeyClick(num) }
                )
            }
        }

        // Row 2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            row2.forEach { char ->
                val display = if (isUpper) char.uppercase() else char
                SoftKey(
                    text = display,
                    palette = palette,
                    keyHeight = config.keyHeight,
                    showPopup = config.showPopups,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyClick(display) }
                )
            }
        }

        // Row 3
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(
                text = "⇧",
                palette = palette,
                style = KeyStyle.SPECIAL,
                isArmed = shiftState != ShiftState.OFF,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1.4f),
                onClick = onShiftClick
            )

            row3.forEach { char ->
                val display = if (isUpper) char.uppercase() else char
                SoftKey(
                    text = display,
                    palette = palette,
                    keyHeight = config.keyHeight,
                    showPopup = config.showPopups,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyClick(display) }
                )
            }

            SoftKey(
                text = "⌫",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1.4f),
                onClick = onBackspaceClick
            )
        }

        // Row 4
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(
                text = "?123",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1.4f),
                onClick = onSymbolsClick
            )

            SoftKey(
                text = ",",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(0.9f),
                onClick = { onKeyClick(",") }
            )

            if (config.showFnKey) {
                SoftKey(
                    text = "Fn",
                    palette = palette,
                    style = KeyStyle.SPECIAL,
                    isArmed = isFnLongActive,
                    keyHeight = config.keyHeight,
                    modifier = Modifier.weight(0.9f),
                    onClick = onFnClick,
                    onLongClick = onFnLongClick
                )
            }

            SoftKey(
                text = "English (US)",
                palette = palette,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(3.5f),
                onClick = onSpaceClick,
                onSlideDrag = onSpaceDrag
            )

            SoftKey(
                text = ".",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(0.9f),
                onClick = { onKeyClick(".") }
            )

            SoftKey(
                text = "↵",
                palette = palette,
                style = KeyStyle.LIME,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1.4f),
                onClick = onEnterClick
            )
        }
    }
}

@Composable
fun SymbolsLayoutView(
    palette: KeyboardPalette,
    config: KeyRenderConfig,
    page: Int,
    onKeyClick: (String) -> Unit,
    onSwitchPage: () -> Unit,
    onLettersClick: () -> Unit,
    onNumpadClick: () -> Unit,
    onBackspaceClick: () -> Unit,
    onSpaceClick: () -> Unit,
    onEnterClick: () -> Unit
) {
    val row1 = if (page == 1) {
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    } else {
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "Δ")
    }

    val row2 = if (page == 1) {
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
    } else {
        listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")
    }

    val row3 = if (page == 1) {
        listOf("=", "*", "\"", "'", ":", ";", "!", "?")
    } else {
        listOf("%", "©", "®", "™", "✓", "[", "]", "<", ">")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1
        Row(modifier = Modifier.fillMaxWidth()) {
            row1.forEach { sym ->
                SoftKey(
                    text = sym,
                    palette = palette,
                    keyHeight = config.keyHeight,
                    showPopup = config.showPopups,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyClick(sym) }
                )
            }
        }

        // Row 2
        Row(modifier = Modifier.fillMaxWidth()) {
            row2.forEach { sym ->
                SoftKey(
                    text = sym,
                    palette = palette,
                    keyHeight = config.keyHeight,
                    showPopup = config.showPopups,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyClick(sym) }
                )
            }
        }

        // Row 3
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(
                text = if (page == 1) "=\\<" else "?123",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1.3f),
                onClick = onSwitchPage
            )

            row3.forEach { sym ->
                SoftKey(
                    text = sym,
                    palette = palette,
                    keyHeight = config.keyHeight,
                    showPopup = config.showPopups,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyClick(sym) }
                )
            }

            SoftKey(
                text = "⌫",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1.3f),
                onClick = onBackspaceClick
            )
        }

        // Row 4
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(
                text = "ABC",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1.4f),
                onClick = onLettersClick
            )

            SoftKey(
                text = ",",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(0.9f),
                onClick = { onKeyClick(",") }
            )

            SoftKey(
                text = "12\n34",
                palette = palette,
                style = KeyStyle.NUMPAD_SWITCH,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(0.9f),
                onClick = onNumpadClick
            )

            SoftKey(
                text = "English (US)",
                palette = palette,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(3.5f),
                onClick = onSpaceClick
            )

            SoftKey(
                text = ".",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(0.9f),
                onClick = { onKeyClick(".") }
            )

            SoftKey(
                text = "↵",
                palette = palette,
                style = KeyStyle.LIME,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1.4f),
                onClick = onEnterClick
            )
        }
    }
}

@Composable
fun NumpadLayoutView(
    palette: KeyboardPalette,
    config: KeyRenderConfig,
    onKeyClick: (String) -> Unit,
    onLettersClick: () -> Unit,
    onSymbolsClick: () -> Unit,
    onEnterClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: %, 1, 2, 3, *
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(text = "%", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick("%") })
            SoftKey(text = "1", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("1") })
            SoftKey(text = "2", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("2") })
            SoftKey(text = "3", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("3") })
            SoftKey(text = "*", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick("*") })
        }

        // Row 2: /, 4, 5, 6, -
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(text = "/", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick("/") })
            SoftKey(text = "4", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("4") })
            SoftKey(text = "5", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("5") })
            SoftKey(text = "6", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("6") })
            SoftKey(text = "-", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick("-") })
        }

        // Row 3: (, 7, 8, 9, +
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(text = "(", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick("(") })
            SoftKey(text = "7", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("7") })
            SoftKey(text = "8", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("8") })
            SoftKey(text = "9", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("9") })
            SoftKey(text = "+", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick("+") })
        }

        // Row 4: ABC, Split (, / ?123), 0, Split (= / .), ↵
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(
                text = "ABC",
                palette = palette,
                style = KeyStyle.SPECIAL,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1f),
                onClick = onLettersClick
            )

            Row(modifier = Modifier.weight(1f)) {
                SoftKey(text = ",", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick(",") })
                SoftKey(text = "?123", palette = palette, style = KeyStyle.NUMPAD_SWITCH, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = onSymbolsClick)
            }

            SoftKey(text = "0", palette = palette, keyHeight = config.keyHeight, showPopup = config.showPopups, modifier = Modifier.weight(1f), onClick = { onKeyClick("0") })

            Row(modifier = Modifier.weight(1f)) {
                SoftKey(text = "=", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick("=") })
                SoftKey(text = ".", palette = palette, style = KeyStyle.SPECIAL, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick(".") })
            }

            SoftKey(
                text = "↵",
                palette = palette,
                style = KeyStyle.LIME,
                keyHeight = config.keyHeight,
                modifier = Modifier.weight(1f),
                onClick = onEnterClick
            )
        }
    }
}

@Composable
fun FnKeysLayoutView(
    palette: KeyboardPalette,
    config: KeyRenderConfig,
    onKeyClick: (String) -> Unit,
    onLettersClick: () -> Unit,
    onBackspaceClick: () -> Unit,
    onSpaceClick: () -> Unit,
    onEnterClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: F1 - F8
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8").forEach { f ->
                SoftKey(text = f, palette = palette, style = KeyStyle.REGULAR, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick(f) })
            }
        }

        // Row 2: F9 - F12, Del, PgUp, PgDn
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("F9", "F10", "F11", "F12", "Del", "PgUp", "PgDn").forEach { f ->
                SoftKey(text = f, palette = palette, style = KeyStyle.REGULAR, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick(f) })
            }
        }

        // Row 3: Shift, Ins, Psc, Brk, Scrl, Numl, Win, Syrc, Backspace
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(text = "⇧", palette = palette, style = KeyStyle.REGULAR, keyHeight = config.keyHeight, modifier = Modifier.weight(1.2f), onClick = {})
            listOf("Ins", "Psc", "Brk", "Scrl", "Numl", "Win", "Syrc").forEach { f ->
                SoftKey(text = f, palette = palette, style = KeyStyle.REGULAR, keyHeight = config.keyHeight, modifier = Modifier.weight(1f), onClick = { onKeyClick(f) })
            }
            SoftKey(text = "⌫", palette = palette, style = KeyStyle.REGULAR, keyHeight = config.keyHeight, modifier = Modifier.weight(1.2f), onClick = onBackspaceClick)
        }

        // Row 4
        Row(modifier = Modifier.fillMaxWidth()) {
            SoftKey(text = "ABC", palette = palette, style = KeyStyle.REGULAR, keyHeight = config.keyHeight, modifier = Modifier.weight(1.4f), onClick = onLettersClick)
            SoftKey(text = ",", palette = palette, style = KeyStyle.REGULAR, keyHeight = config.keyHeight, modifier = Modifier.weight(0.9f), onClick = { onKeyClick(",") })
            SoftKey(text = "Fn", palette = palette, style = KeyStyle.LIME, keyHeight = config.keyHeight, modifier = Modifier.weight(0.9f), onClick = onLettersClick)
            SoftKey(text = "English (US)", palette = palette, keyHeight = config.keyHeight, modifier = Modifier.weight(3.5f), onClick = onSpaceClick)
            SoftKey(text = ".", palette = palette, style = KeyStyle.REGULAR, keyHeight = config.keyHeight, modifier = Modifier.weight(0.9f), onClick = { onKeyClick(".") })
            SoftKey(text = "↵", palette = palette, style = KeyStyle.LIME, keyHeight = config.keyHeight, modifier = Modifier.weight(1.4f), onClick = onEnterClick)
        }
    }
}

package com.example.settings

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.KeyboardMode
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.PaperLimeKeyboardView
import com.example.keyboard.getPalette
import com.example.ui.theme.DmSans
import com.example.ui.theme.Tokens

// The height range the stepper offers. The real board renders keyHeight directly
// as dp, so what the preview shows is what the keyboard does.
private const val MIN_HEIGHT_DP = 34
private const val MAX_HEIGHT_DP = 60
private const val HEIGHT_STEP_DP = 2

/**
 * "Resize keyboard": FUTO's flow — the entry opens a screen of its own that puts
 * the keyboard on screen and lets you size it against a live preview. It is our
 * own screen: the live board IS the real keyboard view (not a faked mock), the
 * size control is a plain −/+ stepper rather than FUTO's drag handles, and there
 * is no tip box and no reset entry.
 */
@Composable
fun ResizeKeysPage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val palette = remember(prefs.theme) { getPalette(prefs.theme) }
    var previewMode by remember { mutableStateOf(KeyboardMode.QWERTY) }

    // The board's real layouts. Fn only appears when the Fn key is switched on,
    // because the board itself has no Fn layout otherwise.
    val modes = remember(prefs.fn) {
        buildList {
            add("Letters" to KeyboardMode.QWERTY)
            add("Numbers" to KeyboardMode.NUMPAD)
            add("Symbols" to KeyboardMode.SYMBOLS_1)
            if (prefs.fn) add("Fn" to KeyboardMode.FN)
        }
    }
    // Both symbol pages read as "Symbols" here; anything unexpected falls back to it.
    val selectedMode = modes
        .indexOfFirst { it.second == previewMode }
        .takeIf { it >= 0 }
        ?: modes.indexOfFirst { it.second == KeyboardMode.SYMBOLS_1 }

    PageScaffold(title = "Resize keyboard", onBack = onBack) {
        // The REAL keyboard: the size change is visible the moment you tap − or +,
        // and there is no second, faked preview to keep in step. It stays fully
        // interactive, and the mode you pick below drives it both ways — tapping
        // "?123" or "ABC" inside the preview moves the switch, too.
        PaperLimeKeyboardView(
            palette = palette,
            prefs = prefs,
            onCommitText = {},
            onDelete = {},
            onEnter = {},
            preview = true,
            previewMode = previewMode,
            onPreviewModeChange = { previewMode = it }
        )

        Spacer(Modifier.height(20.dp))
        Label("Keyboard size")
        StepperRow(
            value = prefs.keyHeight.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP),
            min = MIN_HEIGHT_DP,
            max = MAX_HEIGHT_DP,
            step = HEIGHT_STEP_DP,
            unit = "dp",
            caption = { presetName(it) },
            onValue = { scope.launch { repository.setKeyHeight(it) } }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "$MIN_HEIGHT_DP–$MAX_HEIGHT_DP dp · 44 dp is Normal. " +
                "This is the same height the keyboard itself uses.",
            color = Tokens.Muted,
            fontFamily = DmSans,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(20.dp))
        Label("Layout mode")
        SegControl(
            options = modes.map { it.first },
            selectedIndex = selectedMode,
            modifier = Modifier.fillMaxWidth(),
            onSelect = { i -> previewMode = modes[i].second }
        )
    }
}

// Same preset names the Appearance screen uses, so 38 / 44 / 50 read the same in
// both places. Everything in between is honestly just "Custom".
private fun presetName(dp: Int): String = when (dp) {
    38 -> "Compact"
    44 -> "Normal"
    50 -> "Tall"
    else -> "Custom"
}

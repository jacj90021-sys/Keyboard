package com.example.settings

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.BackspaceSwipe
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.SpacebarAction
import com.example.ui.theme.DmSans
import com.example.ui.theme.Tokens

// FUTO's range for the long-press timeout is 100–700 ms with a 300 ms default
// (its slider's soft range); 25 ms a step keeps the same granularity.
private const val MIN_DURATION_MS = 100
private const val MAX_DURATION_MS = 700
private const val DURATION_STEP_MS = 25
private const val DEFAULT_DURATION_MS = 300

/**
 * "Long-press keys & spacebar" — FUTO's second entry on the keyboard screen. Three
 * groups only, the three that were asked for: Backspace, Spacebar, Long press
 * duration. FUTO's other two items (the long-press key-order editor and the key
 * hints switch) are deliberately not here.
 *
 * Honest state of play: these write real preferences, but the board does not read
 * them yet — the backspace still repeats single deletes and the spacebar has no
 * slide action. The screen is the design; the wiring comes next.
 */
@Composable
fun LongPressKeysPage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val swipeModes = BackspaceSwipe.entries

    PageScaffold(title = "Long-press keys & spacebar", onBack = onBack) {
        Label("Backspace")
        SwitchCard {
            SwitchRow(
                title = "Hold to delete words",
                subtitle = "Keep holding the delete key and it starts erasing whole words",
                checked = prefs.backspaceHoldWords,
                onToggle = {
                    scope.launch { repository.setBackspaceHoldWords(!prefs.backspaceHoldWords) }
                }
            )
        }
        Spacer(Modifier.height(14.dp))
        PickerLabel("Swipe to delete")
        SegControl(
            options = swipeModes.map { it.label },
            selectedIndex = swipeModes.indexOf(prefs.backspaceSwipe).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
            onSelect = { i -> scope.launch { repository.setBackspaceSwipe(swipeModes[i]) } }
        )

        Spacer(Modifier.height(24.dp))
        Label("Spacebar")
        PickerLabel("Swipe")
        SegControl(
            options = SpacebarAction.entries.map { it.label },
            selectedIndex = SpacebarAction.entries.indexOf(prefs.spacebarSwipe).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
            onSelect = { i -> scope.launch { repository.setSpacebarSwipe(SpacebarAction.entries[i]) } }
        )
        Spacer(Modifier.height(14.dp))
        PickerLabel("Hold")
        SegControl(
            // A hold always does something in FUTO, so "Off" is not offered here.
            options = listOf(SpacebarAction.CURSOR.label, SpacebarAction.LANGUAGE.label),
            selectedIndex = if (prefs.spacebarHold == SpacebarAction.LANGUAGE) 1 else 0,
            modifier = Modifier.fillMaxWidth(),
            onSelect = { i ->
                scope.launch {
                    repository.setSpacebarHold(
                        if (i == 1) SpacebarAction.LANGUAGE else SpacebarAction.CURSOR
                    )
                }
            }
        )

        Spacer(Modifier.height(24.dp))
        Label("Long press duration")
        StepperRow(
            value = prefs.longPressMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
            min = MIN_DURATION_MS,
            max = MAX_DURATION_MS,
            step = DURATION_STEP_MS,
            unit = "ms",
            caption = { if (it == DEFAULT_DURATION_MS) "Default" else "" },
            onValue = { scope.launch { repository.setLongPressMs(it) } }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "How long a key has to be held before it counts as a long press. " +
                "$MIN_DURATION_MS–$MAX_DURATION_MS ms; $DEFAULT_DURATION_MS ms is the default.",
            color = Tokens.Muted,
            fontFamily = DmSans,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

/** The small bold caption above one picker, under a [Label] section heading. */
@Composable
private fun PickerLabel(text: String) {
    Text(
        text = text,
        color = Tokens.Ink,
        fontFamily = DmSans,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

package com.example.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import kotlinx.coroutines.launch

@Composable
fun SoundPage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    PageScaffold(title = "Sound & vibration", onBack = onBack) {
        SwitchCard {
            SwitchRow(
                title = "Key sound",
                subtitle = "Play a sound when keys are pressed",
                checked = prefs.sound,
                onToggle = { scope.launch { repository.setBool("sound", !prefs.sound) } }
            )
            Divider()
            SwitchRow(
                title = "Haptic feedback",
                subtitle = "Vibrate when keys are pressed",
                checked = prefs.haptics,
                onToggle = { scope.launch { repository.setBool("haptics", !prefs.haptics) } }
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Notes")
        SwitchCard {
            Hint("Sounds and haptics depend on your device settings.")
        }
    }
}

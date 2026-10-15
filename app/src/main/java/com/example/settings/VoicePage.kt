package com.example.settings

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.DmSans
import com.example.ui.theme.Tokens

/**
 * Real "Voice input" settings page.
 *
 * The dictation itself lives in the IME service (the Voice Input card in the All
 * Actions grid); this page only reports the two things that decide whether that
 * card can work — whether the device ships a recogniser, and whether the app
 * holds `RECORD_AUDIO` — and offers the permission prompt the IME cannot show.
 */
@Composable
fun VoicePage(onBack: () -> Unit) {
    val context = LocalContext.current

    val recognitionAvailable = remember {
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    }
    var granted by remember {
        mutableStateOf(
            runCatching {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    PageScaffold(title = "Voice input", onBack = onBack) {
        Label("Status")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Mic,
                title = "Speech recognition",
                subtitle = "Provided by this device",
                trailingValue = if (recognitionAvailable) "Available" else "Unavailable"
            )
            Divider()
            SettingRow(
                icon = Icons.Default.RecordVoiceOver,
                title = "Microphone access",
                subtitle = "Required for dictation",
                trailingValue = if (granted) "Granted" else "Not granted",
                onClick = if (granted) null else {
                    { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                }
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("How it works")
        SwitchCard {
            HintRow(
                if (recognitionAvailable && granted) {
                    "Tap the mic card in All Actions and start talking. What the " +
                        "recogniser hears is committed at the cursor."
                } else if (!recognitionAvailable) {
                    "This device has no speech recogniser installed, so the mic " +
                        "card will stay inert."
                } else {
                    "Grant microphone access above (or from the mic card) to " +
                        "enable dictation."
                }
            )
        }
    }
}

@Composable
private fun HintRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = text,
            color = Tokens.Muted,
            fontFamily = DmSans,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

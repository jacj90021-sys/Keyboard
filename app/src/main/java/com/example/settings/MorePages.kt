package com.example.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.native.NativeEngineBridge
import com.example.ui.theme.DmSans
import com.example.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.io.File

/**
 * The settings rows that used to fall through to [PlaceholderPage] but actually
 * map onto real engine state or preferences: prediction, swipe typing,
 * personalization (learned words) and about.
 */

@Composable
fun PredictionPage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val loaded = NativeEngineBridge.isLoaded()

    PageScaffold(title = "Text prediction", onBack = onBack) {
        SwitchCard {
            SwitchRow(
                title = "Word suggestions",
                subtitle = "Show candidates above the keys as you type",
                checked = prefs.suggestions,
                onToggle = { scope.launch { repository.setBool("suggestions", !prefs.suggestions) } }
            )
            Divider()
            SwitchRow(
                title = "Autocorrect",
                subtitle = "Fix obvious typos when you press space",
                checked = prefs.autoCorrect,
                onToggle = { scope.launch { repository.setBool("autoCorrect", !prefs.autoCorrect) } }
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Engine")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Memory,
                title = "Prediction engine",
                subtitle = "On-device dictionary and reranker",
                trailingValue = if (loaded) "Ready" else "Unavailable"
            )
        }
    }
}

@Composable
fun SwipePage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val loaded = NativeEngineBridge.isLoaded()

    PageScaffold(title = "Swipe typing", onBack = onBack) {
        SwitchCard {
            SwitchRow(
                title = "Swipe typing",
                subtitle = "Glide across the letters of a word",
                checked = prefs.swipeTyping,
                enabled = loaded,
                onToggle = { scope.launch { repository.setBool("swipeTyping", !prefs.swipeTyping) } }
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Status")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.TouchApp,
                title = "Gesture decoder",
                subtitle = "Native stroke matching",
                trailingValue = if (loaded) "Available" else "Unavailable"
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Notes")
        SwitchCard {
            Hint("Trips shorter than a deliberate stroke are treated as taps, so " +
                "single-key presses are never swallowed by swipe typing.")
        }
    }
}

@Composable
fun PersonalizationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val learnedFile = remember(context) { File(context.filesDir, "learned_words.tsv") }
    var learnedCount by remember { mutableIntStateOf(NativeEngineBridge.dictionaryPromotionCount()) }

    PageScaffold(title = "Personalization", onBack = onBack) {
        Label("Learned words")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Storage,
                title = "Words you've taught",
                subtitle = "Kept on this device only",
                trailingValue = "$learnedCount"
            )
            Divider()
            SettingRow(
                icon = Icons.Default.Delete,
                title = "Clear learned words",
                subtitle = "Forget everything the keyboard has adapted to",
                onClick = {
                    NativeEngineBridge.dictionaryClearPromotions()
                    NativeEngineBridge.dictionarySavePromotions(learnedFile.absolutePath)
                    learnedCount = NativeEngineBridge.dictionaryPromotionCount()
                }
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Privacy")
        SwitchCard {
            Hint("What you type never leaves the device: learning is a small, " +
                "local table of words the engine ranks higher for you.")
        }
    }
}

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val loaded = remember { NativeEngineBridge.isLoaded() }
    val engineVersion = remember { NativeEngineBridge.engineVersion() }
    val dictionarySize = remember {
        // The sample dictionary is loaded at app start; report its size if so.
        if (loaded) NativeEngineBridge.dictionarySize() else 0
    }

    PageScaffold(title = "About", onBack = onBack) {
        Label("Version")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Info,
                title = "Keyboard",
                subtitle = "Calendar-style keyboard with a native core",
                trailingValue = BuildConfig.VERSION_NAME
            )
            Divider()
            SettingRow(
                icon = Icons.Default.AutoAwesome,
                title = "Native engine",
                subtitle = "Original Rust dictionary and typing core",
                trailingValue = engineVersion ?: "Unavailable"
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Dictionary")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Memory,
                title = "Words loaded",
                subtitle = "In the on-device vocabulary",
                trailingValue = if (dictionarySize > 0) "$dictionarySize" else "—"
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
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

package com.example.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.keyboard.allActionCards
import com.example.keyboard.emojiCategories
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.native.NativeEngineBridge

/**
 * The last settings rows that used to fall through to [PlaceholderPage]. None of
 * these invent an engine: they report real state (bundled language, available
 * actions, engine diagnostics) or link out to the project's issue tracker.
 */

@Composable
fun ActionsPage(onBack: () -> Unit) {
    PageScaffold(title = "Actions", onBack = onBack) {
        Label("Available actions")
        SwitchCard {
            allActionCards.forEachIndexed { index, card ->
                if (index > 0) Divider()
                SettingRow(
                    icon = card.icon,
                    title = card.title,
                    subtitle = card.actionId
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Label("Notes")
        SwitchCard {
            Hint("These are the cards shown in the keyboard's All Actions grid " +
                "(${allActionCards.size} in total).")
        }
    }
}

@Composable
fun OtherPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val loaded = remember { NativeEngineBridge.isLoaded() }
    val dictionarySize = remember { if (loaded) NativeEngineBridge.dictionarySize() else 0 }
    val learned = remember { NativeEngineBridge.dictionaryPromotionCount() }
    val mlStatus = remember { NativeEngineBridge.mlStatus() }
    val runtimeReport = remember { NativeEngineBridge.mlRuntimeReport() }

    PageScaffold(title = "Other", onBack = onBack) {
        Label("Diagnostics")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Memory,
                title = "Native engine",
                subtitle = "Rust core",
                trailingValue = if (loaded) "Loaded" else "Not loaded"
            )
            Divider()
            SettingRow(
                icon = Icons.Default.Storage,
                title = "Dictionary",
                subtitle = "Words in the active vocabulary",
                trailingValue = if (dictionarySize > 0) "$dictionarySize" else "—"
            )
            Divider()
            SettingRow(
                icon = Icons.Default.Storage,
                title = "Learned words",
                subtitle = "Adapted from your typing",
                trailingValue = "$learned"
            )
            Divider()
            SettingRow(
                icon = Icons.Default.Bolt,
                title = "Reranker",
                subtitle = "Suggestion scoring model",
                trailingValue = mlStatus ?: "Unavailable"
            )
            Divider()
            SettingRow(
                icon = Icons.Default.BugReport,
                title = "Inference runtimes",
                subtitle = "Detected on this device",
                trailingValue = runtimeReport ?: "None"
            )
            Divider()
            SettingRow(
                icon = Icons.Default.BugReport,
                title = "Build",
                subtitle = "App version",
                trailingValue = BuildConfig.VERSION_NAME
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Storage")
        SwitchCard {
            Hint("Preferences, learned words and clipboard history are stored " +
                "in this app's private directory (${context.filesDir.name}/).")
        }
    }
}

@Composable
fun HelpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val open = { url: String ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    PageScaffold(title = "Help & feedback", onBack = onBack) {
        Label("Getting started")
        SwitchCard {
            Hint("1. Enable the keyboard in system settings.\n" +
                "2. Select it as the active input method.\n" +
                "3. Type in any app; suggestions appear above the keys.")
        }

        Spacer(Modifier.height(20.dp))
        Label("Feedback")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.BugReport,
                title = "Report an issue",
                subtitle = "Open the project's issue tracker",
                onClick = { open("https://github.com/jacj90021-sys/Keyboard/issues") }
            )
            Divider()
            SettingRow(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = "Read the docs",
                subtitle = "How the native core works",
                onClick = { open("https://github.com/jacj90021-sys/Keyboard") }
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("More")
        SwitchCard {
            SettingRow(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                title = "About",
                subtitle = "Version and engine details",
                trailingValue = BuildConfig.VERSION_NAME
            )
        }
    }
}

@Composable
fun ClipboardPage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    PageScaffold(title = "Clipboard", onBack = onBack) {
        SwitchCard {
            SwitchRow(
                title = "Clipboard history",
                subtitle = "Capture a bounded, de-duplicated history while typing",
                checked = prefs.clipboardHistory,
                onToggle = { scope.launch { repository.setBool("clipboardHistory", !prefs.clipboardHistory) } }
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Privacy")
        SwitchCard {
            Hint("History is only captured while the keyboard is visible and is kept on this device. No sync or cloud access.")
        }
    }
}

@Composable
fun EmojiPage(onBack: () -> Unit) {
    PageScaffold(title = "Emoji & media", onBack = onBack) {
        Label("Available emoji categories")
        SwitchCard {
            emojiCategories.forEachIndexed { index, cat ->
                if (index > 0) Divider()
                SettingRow(
                    icon = Icons.Default.Face,
                    title = cat.first,
                    subtitle = "${cat.second.size} emojis"
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Label("Notes")
        SwitchCard {
            Hint("The emoji picker is available in the keyboard's toolbar and in the All Actions grid.")
        }
    }
}

@Composable
fun PremiumPage(onBack: () -> Unit) {
    PageScaffold(title = "Premium", onBack = onBack) {
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Star,
                title = "No premium tier",
                subtitle = "All features included in this build"
            )
        }
        Spacer(Modifier.height(20.dp))
        Label("Notes")
        SwitchCard {
            Hint("This build contains no premium upsells or gated features.")
        }
    }
}

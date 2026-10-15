package com.example.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.BuildConfig

@Composable
fun PrivacyPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val micGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    PageScaffold(title = "Privacy", onBack = onBack) {
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Mic,
                title = "Microphone",
                subtitle = "Only used when you trigger voice input",
                trailingValue = if (micGranted) "Granted" else "Not granted"
            )
            Divider()
            SettingRow(
                icon = Icons.Default.Lock,
                title = "Data stays on device",
                subtitle = "Learned words, prefs, clipboard history are local",
                trailingValue = "Local only"
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("About this build")
        SwitchCard {
            SettingRow(
                icon = Icons.Default.Lock,
                title = "Version",
                subtitle = BuildConfig.VERSION_NAME,
                trailingValue = null
            )
        }
    }
}

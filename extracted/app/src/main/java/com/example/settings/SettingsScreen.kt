package com.example.settings

import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.getPalette
import com.example.ui.theme.DmSans
import com.example.ui.theme.Manrope
import com.example.ui.theme.Tokens

private data class SettingsEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val pageId: String,
    val trailing: String? = null
)

@Composable
fun SettingsScreen(
    prefs: KeyboardPrefs,
    repository: KeyboardPrefsRepository,
    onOpenPage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var showTry by remember { mutableStateOf(false) }

    val entries = listOf(
        SettingsEntry("Languages & layouts", "Choose languages and keyboard layouts", Icons.Default.Language, "languages", "English (US)"),
        SettingsEntry("Appearance", "Themes, height and key style", Icons.Default.Palette, "appearance"),
        SettingsEntry("Keyboard & typing", "Layout and key behavior", Icons.Default.Keyboard, "typing"),
        SettingsEntry("Swipe typing", "Gesture and glide typing", Icons.Default.TouchApp, "swipe"),
        SettingsEntry("Text prediction", "Suggestions and correction", Icons.Default.TextFields, "prediction"),
        SettingsEntry("Voice input", "Dictation and voice typing", Icons.Default.Mic, "voice"),
        SettingsEntry("Top bar", "Suggestion strip and quick tools", Icons.Default.ViewAgenda, "topbar"),
        SettingsEntry("Clipboard", "History, pinned clips and sync", Icons.Default.ContentPaste, "clipboard"),
        SettingsEntry("Emoji & media", "Emoji, GIFs and stickers", Icons.Default.EmojiEmotions, "emoji"),
        SettingsEntry("Actions", "Shortcuts and quick actions", Icons.Default.Bolt, "actions"),
        SettingsEntry("Personalization", "Dictionary, learned words and sync", Icons.Default.Person, "personalization"),
        SettingsEntry("Sound & vibration", "Key feedback and haptics", Icons.Default.VolumeUp, "sound"),
        SettingsEntry("Privacy", "Permissions and data", Icons.Default.Lock, "privacy"),
        SettingsEntry("Premium", "Unlock extra themes and features", Icons.Default.Star, "premium"),
        SettingsEntry("Other", "Advanced and miscellaneous", Icons.Default.MoreHoriz, "other"),
        SettingsEntry("Help & feedback", "FAQs, support and feedback", Icons.Default.MenuBook, "help"),
        SettingsEntry("About", "App information and version", Icons.Default.Info, "about")
    )

    val tiles = listOf(
        Triple(PopularTileType.NUMROW, "Extra numbers row", "numRow"),
        Triple(PopularTileType.TOOLBAR, "Toolbar icons", "toolbar"),
        Triple(PopularTileType.SUGGEST, "Word suggestions", "suggestions"),
        Triple(PopularTileType.SPECIAL, "Special characters", "special"),
        Triple(PopularTileType.FN, "Fn key", "fn"),
        Triple(PopularTileType.POPUP, "Popup on keypress", "popups")
    )

    fun valueOf(key: String): Boolean = when (key) {
        "numRow" -> prefs.numRow
        "toolbar" -> prefs.toolbar
        "suggestions" -> prefs.suggestions
        "special" -> prefs.special
        "fn" -> prefs.fn
        "popups" -> prefs.popups
        else -> false
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Tokens.Paper),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 21.dp, end = 21.dp, top = 27.dp, bottom = 110.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BrandMark(size = 34)
                Text(
                    text = "Keyboard",
                    color = Tokens.Ink,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                    letterSpacing = (-1).sp
                )
            }

            // Popular settings
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Line, RoundedCornerShape(26.dp))
                    .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)
            ) {
                Text(
                    text = "Popular settings",
                    color = Tokens.Ink,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 19.sp,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (type, label, key) ->
                                PopularTile(
                                    type = type,
                                    label = label,
                                    selected = valueOf(key),
                                    modifier = Modifier.weight(1f),
                                    onClick = { scope.launch { repository.setBool(key, !valueOf(key)) } }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Settings list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(21.dp))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Line, RoundedCornerShape(21.dp))
            ) {
                entries.forEachIndexed { i, e ->
                    if (i > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .padding(start = 65.dp, end = 17.dp)
                                .background(Tokens.Line)
                        )
                    }
                    SettingRow(
                        icon = e.icon,
                        title = e.title,
                        subtitle = e.subtitle,
                        trailingValue = e.trailing,
                        onClick = { onOpenPage(e.pageId) }
                    )
                }
            }
        }

        // Floating keyboard button
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 22.dp)
                .size(58.dp)
                .shadow(12.dp, RoundedCornerShape(20.dp), clip = false)
                .clip(RoundedCornerShape(20.dp))
                .background(Tokens.Lime)
                .clickable { showTry = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = "Try your keyboard",
                tint = Tokens.Ink,
                modifier = Modifier.size(26.dp)
            )
        }

        // Try keyboard panel
        TryKeyboardPanel(
            prefs = prefs,
            visible = showTry,
            onClose = { showTry = false }
        )
    }
}

@Composable
private fun BoxScope.TryKeyboardPanel(
    prefs: KeyboardPrefs,
    visible: Boolean,
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val palette = remember(prefs.theme) { getPalette(prefs.theme) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .shadow(16.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), clip = false)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Tokens.Surface)
                .border(1.dp, Tokens.Line, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Try your keyboard",
                    color = Tokens.Ink,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Tokens.Chip)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = Tokens.Ink,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            val keyboardController = LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type here to try your keyboard", color = Tokens.Muted) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) keyboardController?.show()
                    },
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Tokens.Ink,
                    unfocusedBorderColor = Tokens.Line,
                    focusedContainerColor = Tokens.Surface,
                    unfocusedContainerColor = Tokens.Surface
                )
            )

            Spacer(Modifier.height(6.dp))
        }
    }
}


@Composable
fun PlaceholderPage(title: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Tokens.Paper),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 21.dp, end = 21.dp, top = 18.dp, bottom = 38.dp)
        ) {
            BackHeader(title = title, onBack = onBack)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "TODO: this screen is not built yet. Wire it to an existing screen or implement it here.",
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontSize = 14.sp
            )
        }
    }
}

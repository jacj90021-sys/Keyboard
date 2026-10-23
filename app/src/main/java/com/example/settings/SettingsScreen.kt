package com.example.settings

import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
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
    var tryOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }

    LaunchedEffect(tryOpen) {
        if (tryOpen) {
            try { focusRequester.requestFocus() } catch (_: Exception) { }
            keyboardController?.show()
        }
    }

    BackHandler(enabled = tryOpen) {
        keyboardController?.hide()
        tryOpen = false
        text = ""
    }

    val closeTry: () -> Unit = {
        keyboardController?.hide()
        tryOpen = false
        text = ""
    }

    val entries = listOf(
        SettingsEntry("Languages & layouts", "Choose languages and keyboard layouts", Icons.Default.Language, "languages", "English (US)"),
        SettingsEntry("Appearance", "Themes, height and key style", Icons.Default.Palette, "appearance"),
        SettingsEntry("Keyboard & typing", "Keyboard size and layout modes", Icons.Default.Keyboard, "typing"),
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
                .fillMaxSize()
        ) {
            // Scrollable settings content.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(start = 21.dp, end = 21.dp, top = 27.dp, bottom = 16.dp)
            ) {
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

            if (tryOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color(0xFFF3F1E8))
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(androidx.compose.ui.graphics.Color(0xFFE9E6DC))
                    )
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        maxLines = 3,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            color = Tokens.Ink,
                            fontFamily = DmSans
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = "Type here to try your keyboard",
                                    color = Tokens.Muted,
                                    fontSize = 16.sp,
                                    fontFamily = DmSans
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            // Floating button
            // (placed via overlay below to stay pinned)
        }

        val buttonBottom = if (tryOpen) 72.dp else 22.dp
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = buttonBottom)
                .size(58.dp)
                .shadow(12.dp, RoundedCornerShape(20.dp), clip = false)
                .clip(RoundedCornerShape(20.dp))
                .background(Tokens.Lime)
                .clickable {
                    if (tryOpen) closeTry() else tryOpen = true
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (tryOpen) Icons.Default.KeyboardArrowDown else Icons.Default.Keyboard,
                contentDescription = "Try your keyboard",
                tint = Tokens.Ink,
                modifier = Modifier.size(26.dp)
            )
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

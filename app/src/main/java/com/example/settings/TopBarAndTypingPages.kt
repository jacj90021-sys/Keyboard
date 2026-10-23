package com.example.settings

import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.PaperLimeKeyboardView
import com.example.keyboard.getPalette
import com.example.ui.theme.Tokens

@Composable
internal fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
            content()
        }
    }
}

@Composable
internal fun SwitchCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Line, RoundedCornerShape(20.dp)),
        content = content
    )
}

@Composable
internal fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(start = 15.dp)
            .background(Tokens.Line)
    )
}

@Composable
fun TopBarPage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val palette = remember(prefs.theme) { getPalette(prefs.theme) }
    var previewText by remember { mutableStateOf("") }

    val layoutIndex = when {
        prefs.suggestions && prefs.toolbar -> 0
        !prefs.suggestions && prefs.toolbar -> 1
        prefs.suggestions && !prefs.toolbar -> 2
        else -> -1
    }

    PageScaffold(title = "Top bar", onBack = onBack) {
        PaperLimeKeyboardView(
            palette = palette,
            prefs = prefs,
            onCommitText = { previewText += it },
            onDelete = { previewText = previewText.dropLast(1) },
            onEnter = { previewText += "\n" }
        )
        Spacer(Modifier.height(20.dp))

        Label("Layout")
        SegControl(
            options = listOf("Both", "Tools only", "Words only"),
            selectedIndex = layoutIndex,
            modifier = Modifier.fillMaxWidth(),
            onSelect = { i ->
                scope.launch {
                    when (i) {
                        0 -> repository.setTopBar(true, true, true, true)
                        1 -> repository.setTopBar(false, true, prefs.arrow, prefs.startOpen)
                        2 -> repository.setTopBar(true, false, prefs.arrow, prefs.startOpen)
                    }
                }
            }
        )

        Spacer(Modifier.height(20.dp))
        Label("Details")
        SwitchCard {
            SwitchRow(
                title = "Word suggestions",
                subtitle = "Candidates above the keys",
                checked = prefs.suggestions,
                onToggle = { scope.launch { repository.setBool("suggestions", !prefs.suggestions) } }
            )
            Divider()
            SwitchRow(
                title = "Toolbar",
                subtitle = "Emoji, clipboard, translate and GIF icons",
                checked = prefs.toolbar,
                onToggle = { scope.launch { repository.setBool("toolbar", !prefs.toolbar) } }
            )
            Divider()
            SwitchRow(
                title = "Expand arrow",
                subtitle = "The ^ arrow shows or hides the toolbar",
                checked = prefs.arrow,
                enabled = prefs.toolbar && prefs.showStrip,
                onToggle = { scope.launch { repository.setBool("arrow", !prefs.arrow) } }
            )
            Divider()
            SwitchRow(
                title = "Start with toolbar open",
                subtitle = "Off = only word suggestions at first",
                checked = prefs.startOpen,
                enabled = prefs.toolbar && prefs.showStrip && prefs.arrow,
                onToggle = { scope.launch { repository.setBool("startOpen", !prefs.startOpen) } }
            )
        }
    }
}

@Composable
fun TypingPage(
    prefs: KeyboardPrefs,
    repository: KeyboardPrefsRepository,
    onBack: () -> Unit,
    onOpenResize: () -> Unit,
    onOpenLongPress: () -> Unit
) {
    // The page is a short list now, the way FUTO's Keyboard settings leads with
    // "Resize Keyboard" and "Long-Press Keys & Spacebar", then hands the plain
    // switches the same list. "Emoji Suggestions" and "Auto-capitalization" come
    // from FUTO's Typing screen; we keep one page for both. The switches that used
    // to live here are not gone either: Number row, Special characters, Key preview
    // popup and Fn are in Appearance, and Autocorrect is in Prediction.
    PageScaffold(title = "Keyboard & typing", onBack = onBack) {
        val scope = rememberCoroutineScope()
        SwitchCard {
            SettingRow(
                icon = Icons.Default.OpenInFull,
                title = "Resize keyboard",
                subtitle = "Keyboard height and layout modes, with a live preview",
                trailingValue = "${prefs.keyHeight} dp",
                onClick = onOpenResize
            )
            Divider()
            // FUTO's second entry on this screen: its own screen, three groups
            // (backspace, spacebar, long-press duration).
            SettingRow(
                icon = Icons.Default.SpaceBar,
                title = "Long-press keys & spacebar",
                subtitle = "Backspace and spacebar gestures, and how long a long press is",
                onClick = onOpenLongPress
            )
            // FUTO puts its plain switches below the two entries that open a
            // screen, in the same list. "Emoji Suggestions" and
            // "Auto-capitalization" come from FUTO's *Typing* screen, the one we
            // fold into this page, so they sit with the switches that come from its
            // Keyboard screen.
            Divider()
            SwitchRow(
                title = "Emoji Suggestions",
                subtitle = "Suggest emojis while you're typing",
                checked = prefs.suggestEmojis,
                onToggle = {
                    scope.launch { repository.setSuggestEmojis(!prefs.suggestEmojis) }
                }
            )
            Divider()
            SwitchRow(
                title = "Auto-capitalization",
                subtitle = "Capitalize the first word of each sentence",
                checked = prefs.autoCap,
                onToggle = {
                    scope.launch { repository.setAutoCap(!prefs.autoCap) }
                }
            )
            // FUTO's own order in that list is inline autofill, "Quick period key",
            // then hide-when-USB. We skip Quick period key by request, so these two
            // sit next to each other where FUTO has three.
            Divider()
            SwitchRow(
                title = "Inline autofill",
                subtitle = "Password manager and quick-reply chips above the keys",
                checked = prefs.inlineAutofill,
                onToggle = {
                    scope.launch { repository.setInlineAutofill(!prefs.inlineAutofill) }
                }
            )
            Divider()
            SwitchRow(
                title = "Hide when USB keyboard is detected",
                subtitle = "Keep the on-screen keyboard away while a real keyboard is plugged in",
                checked = prefs.hideWhenHardKeyboard,
                onToggle = {
                    scope.launch { repository.setHideWhenHardKeyboard(!prefs.hideWhenHardKeyboard) }
                }
            )
        }
    }
}

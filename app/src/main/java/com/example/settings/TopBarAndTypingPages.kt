package com.example.settings

import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
private fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
private fun SwitchCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun Divider() {
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
                subtitle = "Yes · Okay · Thanks above the keys",
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
                subtitle = "When both are on, ^ shows or hides the toolbar",
                checked = prefs.arrow,
                enabled = prefs.toolbar && prefs.suggestions,
                onToggle = { scope.launch { repository.setBool("arrow", !prefs.arrow) } }
            )
            Divider()
            SwitchRow(
                title = "Start with toolbar open",
                subtitle = "Off = only word suggestions at first",
                checked = prefs.startOpen,
                enabled = prefs.toolbar && prefs.suggestions && prefs.arrow,
                onToggle = { scope.launch { repository.setBool("startOpen", !prefs.startOpen) } }
            )
        }
    }
}

@Composable
fun TypingPage(prefs: KeyboardPrefs, repository: KeyboardPrefsRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    PageScaffold(title = "Keyboard & typing", onBack = onBack) {
        SwitchCard {
            SwitchRow(
                title = "Number row",
                subtitle = "Digits always on top",
                checked = prefs.numRow,
                onToggle = { scope.launch { repository.setBool("numRow", !prefs.numRow) } }
            )
            Divider()
            SwitchRow(
                title = "Key preview popup",
                subtitle = "Show a bubble when you press",
                checked = prefs.popups,
                onToggle = { scope.launch { repository.setBool("popups", !prefs.popups) } }
            )
            Divider()
            SwitchRow(
                title = "Special characters",
                subtitle = "Extra symbols on keys",
                checked = prefs.special,
                onToggle = { scope.launch { repository.setBool("special", !prefs.special) } }
            )
            Divider()
            SwitchRow(
                title = "Fn key",
                subtitle = "Extra key for developers",
                checked = prefs.fn,
                onToggle = { scope.launch { repository.setBool("fn", !prefs.fn) } }
            )
        }
    }
}

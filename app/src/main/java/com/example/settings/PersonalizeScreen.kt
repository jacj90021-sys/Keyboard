package com.example.settings

import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.PaperLimeKeyboardView
import com.example.keyboard.ThemeChoice
import com.example.keyboard.getPalette
import com.example.ui.theme.DmSans
import com.example.ui.theme.Manrope
import com.example.ui.theme.Tokens

private data class ExtraItem(val key: String, val title: String, val subtitle: String, val value: Boolean)

@Composable
fun PersonalizeScreen(
    prefs: KeyboardPrefs,
    repository: KeyboardPrefsRepository,
    isOnboarding: Boolean,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val palette = remember(prefs.theme) { getPalette(prefs.theme) }
    var previewText by remember { mutableStateOf("") }

    val extras = listOf(
        ExtraItem("numRow", "Number row", "Digits always on top", prefs.numRow),
        ExtraItem("toolbar", "Toolbar icons", "Emoji, clipboard, translate, GIF", prefs.toolbar),
        ExtraItem("suggestions", "Word suggestions", "Yes · Okay · Thanks above the keys", prefs.suggestions),
        ExtraItem("special", "Special characters", "Extra symbols on keys", prefs.special),
        ExtraItem("popups", "Key preview popup", "Show a bubble when you press", prefs.popups),
        ExtraItem("fn", "Fn key", "Extra key for developers", prefs.fn)
    )

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
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 22.dp)
        ) {
            if (!isOnboarding) {
                BackHeader(title = "Appearance", onBack = onBack)
            }

            Text(
                text = "Make it yours",
                color = Tokens.Ink,
                fontFamily = Manrope,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 29.sp,
                letterSpacing = (-1.2).sp,
                lineHeight = 33.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tap the keys. The preview updates as you change things.",
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(18.dp))

            // Live preview using the REAL keyboard view
            PaperLimeKeyboardView(
                palette = palette,
                prefs = prefs,
                onCommitText = { previewText += it },
                onDelete = { previewText = previewText.dropLast(1).coerceAtLeast("") },
                onEnter = { previewText += "\n" },
                // In the onboarding preview the three-dot "More" must do nothing.
                preview = isOnboarding
            )

            Spacer(Modifier.height(20.dp))
            Label("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeChoice.entries.forEach { choice ->
                    ThemeSwatch(
                        choice = choice,
                        selected = prefs.theme == choice,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { repository.setTheme(choice) } }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Label("Key height")
            val heights = listOf(38, 44, 50)
            SegControl(
                options = listOf("Compact", "Normal", "Tall"),
                selectedIndex = heights.indexOf(prefs.keyHeight).coerceAtLeast(1),
                modifier = Modifier.fillMaxWidth(),
                onSelect = { i -> scope.launch { repository.setKeyHeight(heights[i]) } }
            )

            Spacer(Modifier.height(20.dp))
            Label("Extras")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Line, RoundedCornerShape(20.dp))
            ) {
                extras.forEachIndexed { i, item ->
                    if (i > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .padding(start = 15.dp)
                                .background(Tokens.Line)
                        )
                    }
                    SwitchRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        checked = item.value,
                        onToggle = { scope.launch { repository.setBool(item.key, !item.value) } }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            LimeButton(text = if (isOnboarding) "Finish" else "Done", onClick = onFinish)
        }
    }
}

@Composable
fun Label(text: String) {
    Text(
        text = text.uppercase(),
        color = Tokens.Muted,
        fontFamily = Manrope,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Tokens.Chip)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Tokens.Ink,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = title,
            color = Tokens.Ink,
            fontFamily = Manrope,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,
            letterSpacing = (-0.8).sp
        )
    }
}

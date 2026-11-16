package com.example.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DmSans
import com.example.ui.theme.Manrope
import com.example.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * Languages & Layouts.
 *
 * A clean, dynamic list of the active languages. The screen keeps the two top
 * settings rows (display-name locale + subtype labels), then renders one plain
 * text block per language — name, an optional "New" badge, and its current
 * layout — with no cards, borders or per-language gear icons. Two full-width
 * buttons sit below the list: "+ Add Keyboard Layout" opens the layout picker,
 * and "+ Add languages" opens the bottom sheet that appends a new language.
 */

private enum class LangScreen { MAIN, ADD }

private enum class LangSheet { LOCALE, ADD_LANGUAGE }

private enum class NameLocale(val label: String) {
    SYSTEM("System locale"),
    APP("App language")
}

private data class KeyboardLayoutOption(
    val name: String,
    val subtitle: String,
    val row1: String,
    val row2: String,
    val row3: String
)

private val layoutOptions = listOf(
    KeyboardLayoutOption("QWERTY", "Standard US layout", "qwertyuiop", "asdfghjkl", "zxcvbnm"),
    KeyboardLayoutOption("AZERTY", "French standard layout", "azertyuiop", "qsdfghjklm", "wxcvbn"),
    KeyboardLayoutOption("Colemak", "Ergonomic layout", "qwfpgjluy", "arstdhneio", "zxcvbnm"),
    KeyboardLayoutOption("Dvorak", "Speed optimized layout", "pyfgcrl", "aoeuidhtns", "qjkxbmwvz"),
    KeyboardLayoutOption("QWERTZ", "Germanic layout", "qwertyuzop", "asdfghjkl", "yxcvbnm"),
    KeyboardLayoutOption("QZERTY", "Classic Italian layout", "qzgtyuiop", "asdfghjklm", "wxcvbn")
)

/** One active language: its display name, its keyboard layout, and whether it
 *  was added recently (so it shows the "New" badge). */
private data class Subtype(
    val name: String,
    val layout: String,
    val isNew: Boolean = false
)

/** A language the user can add from the bottom sheet, with the layout it
 *  starts on. */
private data class LanguageOption(
    val name: String,
    val defaultLayout: String
)

private val languageOptions = listOf(
    LanguageOption("Spanish (ES)", "QWERTY"),
    LanguageOption("French (FR)", "AZERTY"),
    LanguageOption("German (DE)", "QWERTZ"),
    LanguageOption("Italian (IT)", "QWERTY"),
    LanguageOption("Portuguese (BR)", "QWERTY"),
    LanguageOption("Russian (RU)", "QWERTY"),
    LanguageOption("Arabic (AR)", "QWERTY"),
    LanguageOption("Hindi (IN)", "QWERTY")
)

private val SheetBg = Color(0xFFF6F6F4)
private val SheetInk = Color(0xFF1C1C1A)
private val TileInkSelected = Color(0xFF25271F)
private val TileSubSelected = Color(0xFF4A4D3F)
private val ScrimColor = Color(0x73292B23)

@Composable
fun LanguagesLayoutsScreen(onBack: () -> Unit) {
    var screen by remember { mutableStateOf(LangScreen.MAIN) }
    var pendingLayout by remember { mutableStateOf("QWERTY") }
    var locale by remember { mutableStateOf(NameLocale.SYSTEM) }
    var subtypeLabels by remember { mutableStateOf(true) }
    var subtypes by remember {
        mutableStateOf(listOf(Subtype("English (US)", "QWERTY", isNew = true)))
    }
    var sheetKind by remember { mutableStateOf(LangSheet.LOCALE) }
    var sheetVisible by remember { mutableStateOf(false) }

    var toastText by remember { mutableStateOf<String?>(null) }
    var toastVisible by remember { mutableStateOf(false) }
    var toastId by remember { mutableIntStateOf(0) }
    fun showToast(message: String) {
        toastText = message
        toastVisible = true
        toastId++
    }

    LaunchedEffect(toastId) {
        if (toastVisible) {
            delay(1800)
            toastVisible = false
        }
    }

    BackHandler {
        when {
            sheetVisible -> sheetVisible = false
            screen != LangScreen.MAIN -> screen = LangScreen.MAIN
            else -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Paper),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxHeight()
                .fillMaxWidth()
        ) {
            when (screen) {
                LangScreen.MAIN -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 44.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BackCircle(onClick = onBack)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Languages & Layouts",
                            color = Tokens.Ink,
                            fontFamily = Manrope,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 21.sp,
                            letterSpacing = (-0.7).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Tokens.Chip)
                                .clickable {
                                    if (subtypes.any { it.isNew }) {
                                        subtypes = subtypes.map { it.copy(isNew = false) }
                                        showToast("New languages acknowledged")
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = Tokens.Ink,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    LangListCard {
                        LangRow(
                            title = "Display language names in",
                            subtitle = locale.label,
                            onClick = { sheetKind = LangSheet.LOCALE; sheetVisible = true }
                        )
                        LangDivider()
                        LangRow(
                            title = "Display keyboard labels in subtype language",
                            trailing = {
                                SwitchPill(on = subtypeLabels, onToggle = { subtypeLabels = !subtypeLabels })
                            }
                        )
                    }

                    Spacer(Modifier.height(26.dp))

                    subtypes.forEach { subtype ->
                        LanguageBlock(subtype)
                        Spacer(Modifier.height(16.dp))
                    }

                    Spacer(Modifier.height(10.dp))

                    SurfaceActionButton(
                        text = "+ Add Keyboard Layout",
                        icon = Icons.Default.Add,
                        onClick = {
                            pendingLayout = subtypes.firstOrNull()?.layout ?: "QWERTY"
                            screen = LangScreen.ADD
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    LimeActionButton(
                        text = "+ Add languages",
                        icon = Icons.Default.Add,
                        onClick = { sheetKind = LangSheet.ADD_LANGUAGE; sheetVisible = true }
                    )
                }

                LangScreen.ADD -> AddLayoutPage(
                    selected = pendingLayout,
                    onSelect = { pendingLayout = it },
                    onBack = { screen = LangScreen.MAIN },
                    onSave = {
                        subtypes = subtypes.mapIndexed { index, subtype ->
                            if (index == 0) subtype.copy(layout = pendingLayout) else subtype
                        }
                        screen = LangScreen.MAIN
                        showToast("Layout applied")
                    }
                )
            }

            if (screen == LangScreen.ADD) {
                FloatingKeyboardButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 18.dp, bottom = 22.dp),
                    onClick = { showToast("Testing keyboard layout…") }
                )
            }
        }

        AnimatedVisibility(
            visible = toastVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (screen == LangScreen.ADD) 92.dp else 22.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Tokens.Ink)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = toastText.orEmpty(),
                    color = Color.White,
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }

        // Bottom sheet: scrim + panel. The kind is remembered separately from
        // visibility so the panel keeps its content while it slides away.
        AnimatedVisibility(
            visible = sheetVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScrimColor)
                    .clickable { sheetVisible = false }
            )
        }

        AnimatedVisibility(
            visible = sheetVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            when (sheetKind) {
                LangSheet.LOCALE -> LocaleSheet(
                    locale = locale,
                    onPick = { picked ->
                        locale = picked
                        sheetVisible = false
                        showToast("Language names updated")
                    },
                    onClose = { sheetVisible = false }
                )

                LangSheet.ADD_LANGUAGE -> AddLanguageSheet(
                    existing = subtypes.map { it.name },
                    onPick = { option ->
                        if (subtypes.none { it.name == option.name }) {
                            subtypes = subtypes + Subtype(option.name, option.defaultLayout, isNew = true)
                            showToast("${option.name} added")
                        }
                        sheetVisible = false
                    },
                    onClose = { sheetVisible = false }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dynamic language list
// ---------------------------------------------------------------------------

/** One plain text block: name (with optional "New" badge) and its layout. No
 *  card, no border, no gear icon. */
@Composable
private fun LanguageBlock(subtype: Subtype) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = subtype.name,
                color = Tokens.Ink,
                fontFamily = Manrope,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
                letterSpacing = (-0.3).sp
            )
            if (subtype.isNew) {
                Spacer(Modifier.width(8.dp))
                NewBadge()
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "Layout: ${subtype.layout}",
            color = Tokens.Muted,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun NewBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Tokens.Lime)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "New",
            color = Tokens.Ink,
            fontFamily = DmSans,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.3.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Add Keyboard Layout page
// ---------------------------------------------------------------------------

@Composable
private fun AddLayoutPage(
    selected: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 100.dp)
    ) {
        SubHeader(
            title = "Add Keyboard Layout",
            onBack = onBack,
            trailing = {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { onSave() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Save layout",
                        tint = Tokens.Ink,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        )

        Label("Select Layout")

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            layoutOptions.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { option ->
                        LayoutTile(
                            option = option,
                            selected = option.name == selected,
                            onClick = { onSelect(option.name) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutTile(
    option: KeyboardLayoutOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .shadow(if (selected) 8.dp else 0.dp, RoundedCornerShape(18.dp))
            .clip(shape)
            .background(if (selected) Tokens.Lime else Tokens.Chip)
            .border(1.5.dp, if (selected) Tokens.LimeDeep else Tokens.Line, shape)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = option.name,
                    color = if (selected) TileInkSelected else Tokens.Ink,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.5.sp,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(26.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = option.subtitle,
                color = if (selected) TileSubSelected else Tokens.Muted,
                fontFamily = DmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            MiniKeyboard(option = option, selected = selected)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) Tokens.Ink else Color.Transparent)
                .border(1.5.dp, Tokens.Ink, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Tokens.Lime,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniKeyboard(option: KeyboardLayoutOption, selected: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0x1A000000) else Color(0x0D000000))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        MiniRow(option.row1.take(8))
        MiniRow(option.row2.take(7))
        MiniRow(option.row3.take(6))
    }
}

@Composable
private fun MiniRow(letters: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        letters.forEach { ch ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ch.uppercase(),
                    color = Color(0xFF292B23),
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 6.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom sheets
// ---------------------------------------------------------------------------

@Composable
private fun SheetPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(SheetBg)
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 22.dp),
        content = content
    )
}

@Composable
private fun SheetTitle(title: String) {
    Text(
        text = title,
        color = SheetInk,
        fontFamily = Manrope,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
        letterSpacing = (-0.4).sp
    )
}

@Composable
private fun SheetGrab() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0xFFE0E0DC))
        )
    }
}

@Composable
private fun LocaleSheet(
    locale: NameLocale,
    onPick: (NameLocale) -> Unit,
    onClose: () -> Unit
) {
    SheetPanel {
        SheetGrab()
        SheetTitle("Display language names in")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Choose preferred language display naming style",
            color = Tokens.Muted,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp
        )
        Spacer(Modifier.height(10.dp))

        RadioOption(
            selected = locale == NameLocale.SYSTEM,
            title = "System locale",
            subtitle = "Use your device's language for names",
            onClick = { onPick(NameLocale.SYSTEM) }
        )
        RadioOption(
            selected = locale == NameLocale.APP,
            title = "App language",
            subtitle = "Always show names in English",
            onClick = { onPick(NameLocale.APP) }
        )

        Spacer(Modifier.height(14.dp))
        SurfaceActionButton(text = "Close", icon = null, onClick = onClose)
    }
}

@Composable
private fun AddLanguageSheet(
    existing: List<String>,
    onPick: (LanguageOption) -> Unit,
    onClose: () -> Unit
) {
    SheetPanel {
        SheetGrab()
        SheetTitle("Add languages")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Choose a language to add to your keyboard.",
            color = Tokens.Muted,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp
        )
        Spacer(Modifier.height(10.dp))

        languageOptions.forEach { option ->
            val added = option.name in existing
            LangRow(
                title = option.name,
                subtitle = if (added) "Already added" else "Layout: ${option.defaultLayout}",
                trailing = {
                    Icon(
                        imageVector = if (added) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = null,
                        tint = if (added) Tokens.Muted else Tokens.Ink,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = if (added) null else { { onPick(option) } }
            )
            LangDivider()
        }

        Spacer(Modifier.height(14.dp))
        SurfaceActionButton(text = "Close", icon = null, onClick = onClose)
    }
}

@Composable
private fun RadioOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) Tokens.Lime else Color.Transparent)
                .border(2.dp, if (selected) Tokens.Ink else Tokens.Muted, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Tokens.Ink)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = SheetInk,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun LangListCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(21.dp))
            .clip(RoundedCornerShape(21.dp))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Line, RoundedCornerShape(21.dp)),
        content = content
    )
}

@Composable
private fun LangRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = onClick?.let { callback ->
        Modifier.clickable { callback() }
    } ?: Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Tokens.Ink,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = (-0.22).sp
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Tokens.Muted,
                    fontFamily = DmSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun LangDivider(inset: Dp = 16.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(Tokens.Line)
    )
}

@Composable
private fun SubHeader(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Tokens.Chip)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Tokens.Ink,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = Tokens.Ink,
            fontFamily = Manrope,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 21.sp,
            letterSpacing = (-0.7).sp,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) trailing()
    }
}

@Composable
private fun SwitchPill(on: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onToggle() }
    ) {
        ToggleSwitch(on = on)
    }
}

@Composable
private fun LimeActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit
) {
    LimePanel(
        color = Tokens.Lime,
        shadowColor = Tokens.LimeDeep,
        radius = 16.dp,
        offsetY = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TileInkSelected,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = text,
                color = TileInkSelected,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun SurfaceActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Line, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Tokens.Ink,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = text,
                color = Tokens.Ink,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun FloatingKeyboardButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    LimePanel(
        color = Tokens.Lime,
        shadowColor = Tokens.LimeDeep,
        radius = 20.dp,
        offsetY = 4.dp,
        modifier = modifier
            .size(58.dp)
            .shadow(10.dp, RoundedCornerShape(20.dp))
            .clickable { onClick() }
    ) {
        Icon(
            imageVector = Icons.Default.Keyboard,
            contentDescription = "Try your keyboard",
            tint = Tokens.Ink,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun BackCircle(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Tokens.Chip)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Tokens.Ink,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * A filled, rounded panel with a crisp offset "hard" shadow underneath — the
 * signature look of the design's lime buttons and badges. The content sizes the
 * panel, so it works for both fill-width buttons and wrap-content chips.
 */
@Composable
private fun LimePanel(
    color: Color,
    shadowColor: Color,
    radius: Dp,
    offsetY: Dp,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .offsetShadow(shadowColor = shadowColor, radius = radius, offsetY = offsetY)
            .clip(RoundedCornerShape(radius))
            .background(color),
        contentAlignment = contentAlignment,
        content = content
    )
}

private fun Modifier.offsetShadow(
    shadowColor: Color,
    radius: Dp,
    offsetY: Dp
): Modifier = this.drawBehind {
    val corner = radius.toPx()
    val dy = offsetY.toPx()
    drawRoundRect(
        color = shadowColor,
        topLeft = Offset(0f, dy),
        size = Size(size.width, size.height),
        cornerRadius = CornerRadius(corner, corner)
    )
}
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
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.style.TextAlign
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
 * A faithful Compose port of the "Languages & Layouts" design: the same paper
 * shell and card/list language, and the same three-step flow — the overview, the
 * FUTO-style "Add Keyboard Layout" tile picker (with its floating keyboard
 * button), and the per-resource "Explore" pages — plus the two bottom sheets
 * (language naming, remove-language confirm) and the toast.
 */

private enum class LangScreen { MAIN, ADD, EXPLORE }

private enum class ExploreKind { NONE, IMPORT, VOICE, DICT, TRANSFORMER }

private enum class LangSheet { LOCALE, REMOVE }

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

private val SheetBg = Color(0xFFF6F6F4)
private val SheetInk = Color(0xFF1C1C1A)
private val DangerRed = Color(0xFFFF3B30)
private val DangerBg = Color(0xFFFFF2F2)
private val DangerShadow = Color(0xFFFFDCDC)
private val TileInkSelected = Color(0xFF25271F)
private val TileSubSelected = Color(0xFF4A4D3F)
private val ScrimColor = Color(0x73292B23)

@Composable
fun LanguagesLayoutsScreen(onBack: () -> Unit) {
    var screen by remember { mutableStateOf(LangScreen.MAIN) }
    var exploreKind by remember { mutableStateOf(ExploreKind.NONE) }
    var activeLayout by remember { mutableStateOf("QWERTY") }
    var pendingLayout by remember { mutableStateOf("QWERTY") }
    var locale by remember { mutableStateOf(NameLocale.SYSTEM) }
    var subtypeLabels by remember { mutableStateOf(true) }
    var multilingual by remember { mutableStateOf(true) }
    var subtypes by remember { mutableStateOf(listOf("English (US)")) }
    var sheetKind by remember { mutableStateOf(LangSheet.LOCALE) }
    var sheetVisible by remember { mutableStateOf(false) }
    var voiceOn by remember { mutableStateOf(true) }
    var dictOn by remember { mutableStateOf(true) }
    var transformerOn by remember { mutableStateOf(true) }

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
                LangScreen.MAIN -> MainOverview(
                    activeLayout = activeLayout,
                    locale = locale,
                    subtypeLabels = subtypeLabels,
                    multilingual = multilingual,
                    subtypes = subtypes,
                    onBack = onBack,
                    onLocaleRow = { sheetKind = LangSheet.LOCALE; sheetVisible = true },
                    onSubtypeLabels = { subtypeLabels = !subtypeLabels },
                    onManagePacks = { showToast("Manage language packs") },
                    onMultilingual = { multilingual = !multilingual },
                    onExplore = { kind ->
                        exploreKind = kind
                        screen = LangScreen.EXPLORE
                    },
                    onAddLayout = { pendingLayout = activeLayout; screen = LangScreen.ADD },
                    onRemoveLanguage = { sheetKind = LangSheet.REMOVE; sheetVisible = true },
                    onAddSubtype = {
                        if (subtypes.isEmpty()) {
                            subtypes = listOf("English (US)")
                            showToast("Subtype added")
                        } else {
                            showToast("Adding new subtype…")
                        }
                    }
                )

                LangScreen.ADD -> AddLayoutPage(
                    selected = pendingLayout,
                    onSelect = {
                        pendingLayout = it
                        showToast("$it selected")
                    },
                    onBack = { screen = LangScreen.MAIN },
                    onSave = {
                        activeLayout = pendingLayout
                        screen = LangScreen.MAIN
                        showToast("Layout applied")
                    }
                )

                LangScreen.EXPLORE -> ExplorePage(
                    kind = exploreKind,
                    onBack = { screen = LangScreen.MAIN },
                    voiceOn = voiceOn,
                    onVoice = { voiceOn = !voiceOn },
                    onVoiceDownload = { showToast("Downloading speech pack…") },
                    dictOn = dictOn,
                    onDict = { dictOn = !dictOn },
                    onDictAdd = { showToast("Dictionary installed") },
                    transformerOn = transformerOn,
                    onTransformer = { transformerOn = !transformerOn },
                    onBrowse = { showToast("File picker opened") }
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

                LangSheet.REMOVE -> RemoveSheet(
                    language = subtypes.firstOrNull() ?: "English (US)",
                    onConfirm = {
                        subtypes = emptyList()
                        sheetVisible = false
                        showToast("Language removed")
                    },
                    onCancel = { sheetVisible = false }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Main overview
// ---------------------------------------------------------------------------

@Composable
private fun MainOverview(
    activeLayout: String,
    locale: NameLocale,
    subtypeLabels: Boolean,
    multilingual: Boolean,
    subtypes: List<String>,
    onBack: () -> Unit,
    onLocaleRow: () -> Unit,
    onSubtypeLabels: () -> Unit,
    onManagePacks: () -> Unit,
    onMultilingual: () -> Unit,
    onExplore: (ExploreKind) -> Unit,
    onAddLayout: () -> Unit,
    onRemoveLanguage: () -> Unit,
    onAddSubtype: () -> Unit
) {
    Column(
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
            LimePanel(
                color = Tokens.Lime,
                shadowColor = Tokens.LimeDeep,
                radius = 12.dp,
                offsetY = 3.dp,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = null,
                    tint = Tokens.Ink,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Languages & Layouts",
                color = Tokens.Ink,
                fontFamily = Manrope,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
                letterSpacing = (-0.7).sp,
                modifier = Modifier.weight(1f)
            )
        }

        LangListCard {
            LangRow(
                title = "Display language names in",
                subtitle = locale.label,
                onClick = onLocaleRow
            )
            LangDivider()
            LangRow(
                title = "Display keyboard labels in subtype language",
                trailing = {
                    SwitchPill(on = subtypeLabels, onToggle = onSubtypeLabels)
                }
            )
            LangDivider()
            LangRow(
                title = "Manage installed language packs",
                subtitle = "Experimental: manage extensions that add support for specific languages",
                onClick = onManagePacks
            )
        }

        Spacer(Modifier.height(20.dp))
        Label("Subtypes")

        if (subtypes.isEmpty()) {
            LangListCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = "No subtypes installed. Add one to start typing.",
                        color = Tokens.Muted,
                        fontFamily = DmSans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        } else {
            subtypes.forEach { name ->
                LanguageCard(
                    name = name,
                    activeLayout = activeLayout,
                    multilingual = multilingual,
                    onExplore = { kind -> onExplore(kind) },
                    onMultilingual = { onMultilingual() },
                    onAddLayout = { onAddLayout() },
                    onRemove = { onRemoveLanguage() }
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        SurfaceActionButton(
            text = "Add subtype",
            icon = Icons.Default.Add,
            onClick = { onAddSubtype() }
        )

        Spacer(Modifier.height(20.dp))
        Label("Other options")
        LangListCard {
            LangRow(title = "Import resource file", onClick = { onExplore(ExploreKind.IMPORT) })
            LangDivider()
            LangRow(title = "Explore voice input models", onClick = { onExplore(ExploreKind.VOICE) })
            LangDivider()
            LangRow(title = "Explore dictionaries", onClick = { onExplore(ExploreKind.DICT) })
            LangDivider()
            LangRow(title = "Explore transformer models", onClick = { onExplore(ExploreKind.TRANSFORMER) })
        }
    }
}

/**
 * The "Manage installed language packs" row is wired to the screen's toast
 * through [MainOverview]'s `onManagePacks` callback.
 */

@Composable
private fun LanguageCard(
    name: String,
    activeLayout: String,
    multilingual: Boolean,
    onExplore: (ExploreKind) -> Unit,
    onMultilingual: () -> Unit,
    onAddLayout: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Line, RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Text(
            text = name,
            color = Tokens.Ink,
            fontFamily = Manrope,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 17.sp,
            letterSpacing = (-0.3).sp
        )

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Tokens.Chip)
                .border(1.dp, Tokens.Line, RoundedCornerShape(16.dp))
        ) {
            ModelRow(
                icon = Icons.Default.Mic,
                label = "Voice Input Model",
                value = "English-39 (default)",
                onClick = { onExplore(ExploreKind.VOICE) }
            )
            LangDivider(inset = 14.dp)
            ModelRow(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                label = "Dictionary",
                value = "Built-in Dictionary",
                onClick = { onExplore(ExploreKind.DICT) }
            )
            LangDivider(inset = 14.dp)
            ModelRow(
                icon = Icons.Default.Memory,
                label = "Transformer Model",
                value = "English v1",
                onClick = { onExplore(ExploreKind.TRANSFORMER) }
            )
        }

        Spacer(Modifier.height(14.dp))
        LangDivider(inset = 0.dp)
        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(
                text = "Keyboard Layouts",
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = activeLayout,
                color = Tokens.Ink,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = (-0.2).sp
            )
        }

        Spacer(Modifier.height(4.dp))
        LangDivider(inset = 0.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Multilingual Typing",
                color = Tokens.Ink,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            SwitchPill(on = multilingual, onToggle = onMultilingual)
        }

        Spacer(Modifier.height(12.dp))
        LimeActionButton(text = "Add Keyboard Layout", icon = Icons.Default.Add, onClick = onAddLayout)
        Spacer(Modifier.height(10.dp))
        DangerActionButton(text = "Remove Language", icon = Icons.Default.Delete, onClick = onRemove)
    }
}

@Composable
private fun ModelRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        LimePanel(
            color = Tokens.Lime,
            shadowColor = Tokens.LimeDeep,
            radius = 9.dp,
            offsetY = 2.dp,
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Tokens.Ink,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label.uppercase(),
                color = Tokens.Muted,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = value,
                color = Tokens.Ink,
                fontFamily = DmSans,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp
            )
        }
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

        Label("Select Layout (FUTO Box Style)")

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
// Explore page
// ---------------------------------------------------------------------------

@Composable
private fun ExplorePage(
    kind: ExploreKind,
    onBack: () -> Unit,
    voiceOn: Boolean,
    onVoice: () -> Unit,
    onVoiceDownload: () -> Unit,
    dictOn: Boolean,
    onDict: () -> Unit,
    onDictAdd: () -> Unit,
    transformerOn: Boolean,
    onTransformer: () -> Unit,
    onBrowse: () -> Unit
) {
    val title = when (kind) {
        ExploreKind.IMPORT -> "Import resource file"
        ExploreKind.VOICE -> "Voice Input Models"
        ExploreKind.DICT -> "Dictionaries"
        ExploreKind.TRANSFORMER -> "Transformer Models"
        ExploreKind.NONE -> "Explore"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 38.dp)
    ) {
        SubHeader(title = title, onBack = onBack)

        when (kind) {
            ExploreKind.IMPORT -> LangListCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Select a custom layout bundle, keyboard theme, or model dictionary file (.yaml, .zip).",
                        color = Tokens.Muted,
                        fontFamily = DmSans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    LimeActionButton(
                        text = "Browse Device Storage",
                        icon = Icons.Default.Upload,
                        onClick = onBrowse
                    )
                }
            }

            ExploreKind.VOICE -> LangListCard {
                LangRow(
                    title = "English-39 (Active)",
                    subtitle = "High accuracy offline speech recognition (142 MB)",
                    trailing = { SwitchPill(on = voiceOn, onToggle = onVoice) }
                )
                LangDivider()
                LangRow(
                    title = "English Multilingual v2",
                    subtitle = "Supports accent variations (188 MB)",
                    trailing = { SmallLimeButton(text = "Download", onClick = onVoiceDownload) }
                )
            }

            ExploreKind.DICT -> LangListCard {
                LangRow(
                    title = "Built-in US English Dictionary",
                    subtitle = "Default prediction vocabulary",
                    trailing = { SwitchPill(on = dictOn, onToggle = onDict) }
                )
                LangDivider()
                LangRow(
                    title = "Extended Technical Terms",
                    subtitle = "Programming & medical vocabulary",
                    trailing = { SmallLimeButton(text = "Add", onClick = onDictAdd) }
                )
            }

            ExploreKind.TRANSFORMER -> LangListCard {
                LangRow(
                    title = "English v1 Transformer",
                    subtitle = "Neural next-word prediction engine",
                    trailing = { SwitchPill(on = transformerOn, onToggle = onTransformer) }
                )
            }

            ExploreKind.NONE -> Unit
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
private fun RemoveSheet(
    language: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    SheetPanel {
        SheetGrab()
        SheetTitle("Remove $language?")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "This removes the subtype and its keyboard layouts from this keyboard. You can add it back later.",
            color = Tokens.Muted,
            fontFamily = DmSans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(16.dp))
        DangerActionButton(text = "Remove Language", icon = Icons.Default.Delete, onClick = onConfirm)
        Spacer(Modifier.height(10.dp))
        SurfaceActionButton(text = "Cancel", icon = null, onClick = onCancel)
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
private fun DangerActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit
) {
    LimePanel(
        color = DangerBg,
        shadowColor = DangerShadow,
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
                    tint = DangerRed,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = text,
                color = DangerRed,
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
private fun SmallLimeButton(text: String, onClick: () -> Unit) {
    LimePanel(
        color = Tokens.Lime,
        shadowColor = Tokens.LimeDeep,
        radius = 12.dp,
        offsetY = 3.dp,
        modifier = Modifier
            .height(32.dp)
            .clickable { onClick() }
    ) {
        Text(
            text = text,
            color = TileInkSelected,
            fontFamily = DmSans,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
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

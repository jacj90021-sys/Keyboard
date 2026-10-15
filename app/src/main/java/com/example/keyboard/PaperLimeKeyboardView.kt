package com.example.keyboard

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.native.NativeEngineBridge

@Composable
fun PaperLimeKeyboardView(
    palette: KeyboardPalette,
    prefs: KeyboardPrefs,
    onCommitText: (String) -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
    // Previews (onboarding / appearance) keep the keyboard visual-only: the
    // toolbar "More" (three dots) must not replace the view with All Actions.
    preview: Boolean = false
) {
    var mode by rememberSaveable { mutableStateOf(KeyboardMode.QWERTY) }
    var shiftState by rememberSaveable { mutableStateOf(ShiftState.OFF) }
    var overlay by rememberSaveable { mutableStateOf(OverlayView.NONE) }
    var isToolbarExpanded by rememberSaveable { mutableStateOf(prefs.startOpen) }
    var fnLongOverlay by rememberSaveable { mutableStateOf(false) }
    var lastShiftTapTime by remember { mutableLongStateOf(0L) }
    var clipboardList by remember {
        mutableStateOf(
            listOf(
                "Paper + Lime is an ergonomic keyboard.",
                "Quick brown fox jumps over the lazy dog.",
                "Hello World!"
            )
        )
    }

    var currentPrefix by remember { mutableStateOf("") }
    val dictionaryCandidates = remember(currentPrefix) {
        if (currentPrefix.length >= 2 && NativeEngineBridge.isLoaded()) {
            val count = NativeEngineBridge.dictionaryPrefixCount(currentPrefix).coerceIn(0, 4)
            (0 until count).mapNotNull { NativeEngineBridge.dictionaryPrefixKey(currentPrefix, it) }
        } else {
            emptyList()
        }
    }
    val trackedCommit: (String) -> Unit = { text ->
        currentPrefix = when {
            text == " " -> ""
            text.length == 1 && text[0].isLetter() -> currentPrefix + text.lowercase()
            else -> ""
        }
        onCommitText(text)
    }
    val trackedDelete: () -> Unit = {
        currentPrefix = if (currentPrefix.isNotEmpty()) currentPrefix.dropLast(1) else ""
        onDelete()
    }
    val trackedEnter: () -> Unit = {
        currentPrefix = ""
        onEnter()
    }

    val context = LocalContext.current
    LaunchedEffect(context) {
        NativeEngineBridge.tryLoad()
        NativeEngineBridge.loadSampleDictionary(context)
    }

    LaunchedEffect(prefs.startOpen) { isToolbarExpanded = prefs.startOpen }

    // Keep the visible layout in sync with the preferences INSTANTLY. These used to
    // be remembered, which left the Fn key (and others) stale until another toggle
    // changed the remember keys.
    val config = KeyRenderConfig(
        keyHeight = prefs.keyHeight.dp,
        // KEEP EXISTING BEHAVIOUR: the letters row always shows its number hints.
        // TODO(special): the "Special characters" switch is persisted but has no extra
        // behaviour yet — the existing keyboard already shows the symbols on keys.
        showSubText = true,
        showFnKey = prefs.fn,
        showPopups = prefs.popups,
        showNumberRow = prefs.numRow
    )

    // Turning the Fn key off must immediately leave the Fn layout / Fn suggestions.
    LaunchedEffect(prefs.fn) {
        if (!prefs.fn) {
            if (mode == KeyboardMode.FN) mode = KeyboardMode.QWERTY
            fnLongOverlay = false
        }
    }

    // Turning the top bar off must immediately close any open overlay.
    LaunchedEffect(prefs.toolbar) {
        if (!prefs.toolbar) overlay = OverlayView.NONE
    }

    val showChevron = prefs.showChevron
    val toolbarVisible = prefs.toolbar && (!showChevron || isToolbarExpanded)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(palette.paper)
            .border(1.dp, palette.line)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        // Floating Toolbar Overlay - placed at the top above suggestions
        AnimatedVisibility(
            visible = toolbarVisible,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            FloatingToolbar(
                palette = palette,
                onToolClick = { targetOverlay ->
                    overlay = if (overlay == targetOverlay) OverlayView.NONE else targetOverlay
                },
                onMoreClick = {
                    if (preview) {
                        // Onboarding/appearance previews: the three-dot must do nothing.
                        overlay = OverlayView.NONE
                    } else {
                        overlay = if (overlay == OverlayView.ALL_ACTIONS) OverlayView.NONE else OverlayView.ALL_ACTIONS
                    }
                }
            )
        }

        // Suggestions Bar
        if (prefs.suggestions) {
            SuggestionsBar(
                palette = palette,
                isExpanded = isToolbarExpanded,
                isFnMode = mode == KeyboardMode.FN || fnLongOverlay,
                suggestionsOverride = if (mode == KeyboardMode.FN || fnLongOverlay) emptyList() else dictionaryCandidates,
                showChevron = showChevron,
                onToggleExpand = {
                    isToolbarExpanded = !isToolbarExpanded
                    if (isToolbarExpanded) overlay = OverlayView.NONE
                },
                onSuggestionClick = { word ->
                    if (mode == KeyboardMode.FN || fnLongOverlay) {
                        if (word == "Tab") onCommitText("\t")
                        else onCommitText("[$word]")
                    } else {
                        onCommitText("$word ")
                    }
                }
            )
        }

        // Number row (letters view only)
        if (prefs.numRow && mode == KeyboardMode.QWERTY && overlay == OverlayView.NONE) {
            Box(modifier = Modifier.padding(vertical = 2.dp)) {
                NumberRowView(
                    palette = palette,
                    config = config,
                    onKeyClick = { trackedCommit(it) }
                )
            }
        }

        // OVERLAYS (All Actions, Emoji, Clipboard) OR KEYBOARD LAYOUTS
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            when (overlay) {
                OverlayView.ALL_ACTIONS -> {
                    AllActionsGrid(
                        palette = palette,
                        onClose = { overlay = OverlayView.NONE },
                        onActionClick = { actionId ->
                            when (actionId) {
                                // TODO(existing): these actions have no behaviour in the current keyboard.
                                "language" -> {}
                                "undo" -> onDelete()
                                "redo" -> {}
                                "select_all" -> {}
                                "copy" -> {}
                                "paste" -> {
                                    if (clipboardList.isNotEmpty()) {
                                        onCommitText(clipboardList.first())
                                    }
                                }
                                "theme" -> {} // Theming now lives in Settings; intentionally no-op here.
                                "numpad" -> {
                                    mode = KeyboardMode.NUMPAD
                                    overlay = OverlayView.NONE
                                }
                                "fn" -> {
                                    mode = KeyboardMode.FN
                                    overlay = OverlayView.NONE
                                }
                                // TODO(existing): arrows, case, clear, voice, translate remain unimplemented.
                                "arrow_left" -> {}
                                "arrow_right" -> {}
                                "case" -> {}
                                "clear" -> {}
                                "voice" -> {}
                                "translate" -> {}
                            }
                        }
                    )
                }

                OverlayView.EMOJI -> {
                    EmojiPickerGrid(
                        palette = palette,
                        onClose = { overlay = OverlayView.NONE },
                        onEmojiClick = { emo ->
                            onCommitText(emo)
                        }
                    )
                }

                OverlayView.CLIPBOARD -> {
                    ClipboardListPanel(
                        palette = palette,
                        clips = clipboardList,
                        onClose = { overlay = OverlayView.NONE },
                        onClearAll = { clipboardList = emptyList() },
                        onClipClick = { clip ->
                            onCommitText(clip)
                        }
                    )
                }

                else -> {
                    // Standard Keyboard Layouts
                    when (mode) {
                        KeyboardMode.QWERTY -> {
                            QwertyLayoutView(
                                palette = palette,
                                config = config,
                                shiftState = shiftState,
                                onKeyClick = { char ->
                                    trackedCommit(char)
                                    if (shiftState == ShiftState.SHIFT) {
                                        shiftState = ShiftState.OFF
                                    }
                                },
                                onLongKeyClick = { num ->
                                    trackedCommit(num)
                                },
                                onShiftClick = {
                                    val now = System.currentTimeMillis()
                                    if (now - lastShiftTapTime < 350) {
                                        shiftState = if (shiftState == ShiftState.CAPS_LOCK) ShiftState.OFF else ShiftState.CAPS_LOCK
                                    } else {
                                        shiftState = if (shiftState == ShiftState.OFF) ShiftState.SHIFT else ShiftState.OFF
                                    }
                                    lastShiftTapTime = now
                                },
                                onBackspaceClick = trackedDelete,
                                onSymbolsClick = {
                                    fnLongOverlay = false
                                    overlay = OverlayView.NONE
                                    mode = KeyboardMode.SYMBOLS_1
                                },
                                onFnClick = {
                                    fnLongOverlay = false
                                    mode = KeyboardMode.FN
                                },
                                onFnLongClick = { fnLongOverlay = !fnLongOverlay },
                                isFnLongActive = fnLongOverlay,
                                onSpaceClick = { trackedCommit(" ") },
                                onEnterClick = trackedEnter,
                                onSpaceDrag = {}
                            )
                        }

                        KeyboardMode.SYMBOLS_1 -> {
                            SymbolsLayoutView(
                                palette = palette,
                                config = config,
                                page = 1,
                                onKeyClick = { trackedCommit(it) },
                                onSwitchPage = { mode = KeyboardMode.SYMBOLS_2 },
                                onLettersClick = {
                                    fnLongOverlay = false
                                    mode = KeyboardMode.QWERTY
                                },
                                onNumpadClick = {
                                    fnLongOverlay = false
                                    mode = KeyboardMode.NUMPAD
                                },
                                onBackspaceClick = trackedDelete,
                                onSpaceClick = { trackedCommit(" ") },
                                onEnterClick = trackedEnter
                            )
                        }

                        KeyboardMode.SYMBOLS_2 -> {
                            SymbolsLayoutView(
                                palette = palette,
                                config = config,
                                page = 2,
                                onKeyClick = { trackedCommit(it) },
                                onSwitchPage = { mode = KeyboardMode.SYMBOLS_1 },
                                onLettersClick = {
                                    fnLongOverlay = false
                                    mode = KeyboardMode.QWERTY
                                },
                                onNumpadClick = {
                                    fnLongOverlay = false
                                    mode = KeyboardMode.NUMPAD
                                },
                                onBackspaceClick = trackedDelete,
                                onSpaceClick = { trackedCommit(" ") },
                                onEnterClick = trackedEnter
                            )
                        }

                        KeyboardMode.NUMPAD -> {
                            NumpadLayoutView(
                                palette = palette,
                                config = config,
                                onKeyClick = { trackedCommit(it) },
                                onLettersClick = {
                                    fnLongOverlay = false
                                    mode = KeyboardMode.QWERTY
                                },
                                onSymbolsClick = { mode = KeyboardMode.SYMBOLS_1 },
                                onEnterClick = trackedEnter
                            )
                        }

                        KeyboardMode.FN -> {
                            FnKeysLayoutView(
                                palette = palette,
                                config = config,
                                onKeyClick = { f -> currentPrefix = ""; onCommitText("[$f]") },
                                onLettersClick = {
                                    fnLongOverlay = false
                                    mode = KeyboardMode.QWERTY
                                },
                                onBackspaceClick = trackedDelete,
                                onSpaceClick = { trackedCommit(" ") },
                                onEnterClick = trackedEnter
                            )
                        }
                    }
                }
            }
        }
    }
}

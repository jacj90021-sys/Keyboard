package com.example.keyboard

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keyboard.native.NativeEngineBridge
import com.example.keyboard.native.SuggestionCase
import com.example.keyboard.native.SuggestionPlanner
import java.io.File

@Composable
fun PaperLimeKeyboardView(
    palette: KeyboardPalette,
    prefs: KeyboardPrefs,
    onCommitText: (String) -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit,
    // Platform actions the view cannot perform itself (clipboard, cursor moves,
    // undo/redo, hiding the keyboard, opening Settings, toggling a preference):
    // the IME service owns the InputConnection, so it answers these. The default
    // keeps previews and tests inert.
    onAction: (String) -> Boolean = { false },
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

    val context = LocalContext.current
    val density = LocalDensity.current
    val learnedWordsFile = remember(context) { File(context.filesDir, "learned_words.tsv") }
    // Promote a word the user accepted or typed correctly, and persist the table
    // so the engine keeps adapting across sessions.
    val learnWord: (String) -> Unit = { word ->
        if (word.isNotBlank()) {
            NativeEngineBridge.dictionaryPromote(word)
            NativeEngineBridge.dictionarySavePromotions(learnedWordsFile.absolutePath)
        }
    }

    var currentPrefix by remember { mutableStateOf("") }
    var rawPrefix by remember { mutableStateOf("") }
    var lastCommittedWord by remember { mutableStateOf("") }
    // Gesture typing only engages once the native engine has actually loaded and
    // mapped a dictionary, so the host-JVM/test path is completely unaffected.
    var engineReady by remember { mutableStateOf(NativeEngineBridge.isLoaded()) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    val dictionaryCandidates = remember(currentPrefix, lastCommittedWord) {
        SuggestionPlanner.candidates(
            prefix = currentPrefix,
            previousWord = lastCommittedWord,
            suggest = NativeEngineBridge::dictionarySuggest
        )
    }
    val trackedCommit: (String) -> Unit = { text ->
        currentPrefix = when {
            text == " " -> {
                if (currentPrefix.isNotBlank()) {
                    // Autocorrect on word commit: the engine decides whether the
                    // typed word should be swapped for a likely correction. The
                    // user can turn this off from Settings or the AutoCorrect
                    // action card.
                    val correction =
                        if (prefs.autoCorrect) {
                            NativeEngineBridge.dictionaryAutocorrect(currentPrefix)
                        } else {
                            null
                        }
                    if (correction != null && correction != currentPrefix) {
                        val typedLength =
                            if (rawPrefix.isNotEmpty()) rawPrefix.length else currentPrefix.length
                        repeat(typedLength) { onDelete() }
                        onCommitText(SuggestionCase.apply(rawPrefix, correction))
                        lastCommittedWord = correction
                        learnWord(correction)
                    } else {
                        lastCommittedWord = currentPrefix
                        // Learn correctly typed words (only if they are real entries).
                        if (NativeEngineBridge.dictionaryExactValue(currentPrefix) != null) {
                            learnWord(currentPrefix)
                        }
                    }
                }
                ""
            }
            text.length == 1 && text[0].isLetter() -> currentPrefix + text.lowercase()
            else -> ""
        }
        rawPrefix = when {
            text == " " -> ""
            text.length == 1 && text[0].isLetter() -> rawPrefix + text
            else -> ""
        }
        onCommitText(text)
    }
    val trackedDelete: () -> Unit = {
        currentPrefix = if (currentPrefix.isNotEmpty()) currentPrefix.dropLast(1) else ""
        rawPrefix = if (rawPrefix.isNotEmpty()) rawPrefix.dropLast(1) else ""
        onDelete()
    }
    val trackedEnter: () -> Unit = {
        currentPrefix = ""
        rawPrefix = ""
        onEnter()
    }

    LaunchedEffect(context) {
        NativeEngineBridge.tryLoad()
        NativeEngineBridge.loadSampleDictionary(context)
        // Restore words this user has taught the engine in earlier sessions.
        NativeEngineBridge.dictionaryLoadPromotions(learnedWordsFile.absolutePath)
        engineReady = NativeEngineBridge.isLoaded()
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

    // Gesture typing runs only on the live letter board, never in a preview, an
    // overlay, or before the dictionary has loaded.
    val swipeEnabled = !preview && engineReady && mode == KeyboardMode.QWERTY && overlay == OverlayView.NONE
    val swipeTyping = Modifier.swipeTyping(
        enabled = swipeEnabled,
        minTravelPx = with(density) { 24.dp.toPx() }
    ) { stroke ->
        if (boardSize.width > 0) {
            val grid = GestureLayoutGrid.qwerty(
                boardWidthPx = boardSize.width.toFloat(),
                densityDpToPx = density.density,
                keyHeightDp = prefs.keyHeight,
                withNumberRow = prefs.numRow
            )
            val match = NativeEngineBridge.gestureDecode(stroke, grid, 4).firstOrNull()
            if (!match.isNullOrBlank()) {
                learnWord(match.lowercase())
                lastCommittedWord = match.lowercase()
                currentPrefix = ""
                rawPrefix = ""
                onCommitText("$match ")
            }
        }
    }

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
                        // Accepting a suggestion finishes the word: restore the
                        // user's capitalisation and reset the typing state so the
                        // next word starts clean (and next-word hints fire).
                        val cased = SuggestionCase.apply(rawPrefix, word)
                        learnWord(word.lowercase())
                        lastCommittedWord = word.lowercase()
                        currentPrefix = ""
                        rawPrefix = ""
                        onCommitText("$cased ")
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
                .onSizeChanged { boardSize = it }
                .then(swipeTyping)
        ) {
            when (overlay) {
                OverlayView.ALL_ACTIONS -> {
                    AllActionsGrid(
                        palette = palette,
                        onClose = { overlay = OverlayView.NONE },
                        onActionClick = { actionId ->
                            when (actionId) {
                                // View-local: opening an overlay or switching the
                                // layout needs no text editor.
                                "clipboard" -> overlay = OverlayView.CLIPBOARD
                                "emojis" -> overlay = OverlayView.EMOJI
                                "modes" -> {
                                    mode = when (mode) {
                                        KeyboardMode.QWERTY -> KeyboardMode.NUMPAD
                                        KeyboardMode.NUMPAD -> KeyboardMode.FN
                                        else -> KeyboardMode.QWERTY
                                    }
                                    overlay = OverlayView.NONE
                                }
                                // There is exactly one shipped dictionary, so
                                // there is nothing to switch to yet (roadmap
                                // item 16); translate has no on-device backend.
                                "language", "translate" -> overlay = OverlayView.NONE
                                // Everything else runs against the platform text
                                // editor, which the IME service owns: undo/redo,
                                // clipboard, selection, arrows, word styles,
                                // Settings, hide-keyboard, theme and autocorrect.
                                else -> {
                                    onAction(actionId)
                                    overlay = OverlayView.NONE
                                }
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
                                onKeyClick = { f -> currentPrefix = ""; rawPrefix = ""; onCommitText("[$f]") },
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

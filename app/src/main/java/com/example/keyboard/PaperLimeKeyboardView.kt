package com.example.keyboard

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    // Real clipboard history, owned by the IME service (the overlay used to show
    // three hard-coded demo clips). Previews get an empty, inert list.
    clipboardClips: List<String> = emptyList(),
    onClearClipboard: () -> Unit = {},
    // Emoji recents, owned by the IME service (session-scoped, like the
    // clipboard). Previews get an empty list and a no-op recorder.
    recentEmojis: List<String> = emptyList(),
    onEmojiUsed: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    // Voice input is owned by the IME service: it holds the SpeechRecognizer and
    // the input connection. The view only draws the mic window and reports taps.
    voiceInput: VoiceInputState = VoiceInputState(),
    onStopVoice: () -> Unit = {},
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

    val context = LocalContext.current
    val density = LocalDensity.current
    val learnedWordsFile = remember(context) { File(context.filesDir, "learned_words.tsv") }
    // Promote a word the user accepted or typed correctly. The engine keeps the
    // authoritative table in memory (microseconds); persisting it is coalesced
    // onto a background dispatcher so a committed word never does a file write
    // on the frame.
    val learnWord: (String) -> Unit = { word ->
        if (word.isNotBlank()) {
            NativeEngineBridge.dictionaryPromote(word)
            LearningPersistence.scheduleSave(learnedWordsFile)
        }
    }
    // Guarantee a final save when the keyboard disappears.
    DisposableEffect(learnedWordsFile) {
        onDispose { LearningPersistence.flush(learnedWordsFile) }
    }

    var currentPrefix by remember { mutableStateOf("") }
    var rawPrefix by remember { mutableStateOf("") }
    var lastCommittedWord by remember { mutableStateOf("") }
    // Gesture typing only engages once the native engine has actually loaded and
    // mapped a dictionary, so the host-JVM/test path is completely unaffected.
    var engineReady by remember { mutableStateOf(NativeEngineBridge.isLoaded()) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    // The live keyboard's height, remembered while the plain board is showing so
    // the emoji panel can take the same footprint (FUTO's action window does the
    // same). Only captured with no overlay open, so the panel cannot feed its own
    // size back into it.
    var keyboardAreaHeightPx by remember { mutableStateOf(0) }
    // Suggestions run on a background dispatcher: the query used to resolve
    // during composition, blocking the frame (next-word ranking alone cost ~9 ms
    // on the first keystroke of every word). produceState restarts when the
    // prefix changes, which also debounces a fast typist's intermediate states.
    val dictionaryCandidates by produceState(
        initialValue = emptyList<String>(),
        currentPrefix,
        lastCommittedWord
    ) {
        value = withContext(Dispatchers.Default) {
            SuggestionPlanner.candidates(
                prefix = currentPrefix,
                previousWord = lastCommittedWord,
                suggest = NativeEngineBridge::dictionarySuggest
            )
        }
    }
    // Prefetch the autocorrect verdict while the word is being typed, so swinging
    // to the next word (space) never runs the correction scan on the frame.
    val pendingCorrection by produceState(
        initialValue = null as String?,
        currentPrefix,
        prefs.autoCorrect
    ) {
        value = withContext(Dispatchers.Default) {
            if (prefs.autoCorrect && currentPrefix.length >= 3 && NativeEngineBridge.isLoaded()) {
                NativeEngineBridge.dictionaryAutocorrect(currentPrefix)?.takeIf { it != currentPrefix }
            } else {
                null
            }
        }
    }
    val trackedCommit: (String) -> Unit = { text ->
        currentPrefix = when {
            text == " " -> {
                if (currentPrefix.isNotBlank()) {
                    // Autocorrect on word commit: use the verdict prefetched
                    // while typing (see `pendingCorrection`) instead of blocking
                    // the frame on the correction scan. The user can turn this
                    // off from Settings or the AutoCorrect action card.
                    val correction = pendingCorrection
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
        // The first show maps the dictionary, validates it, builds the automaton
        // and copies the asset (~100 ms on a fast host, more on a phone). Keep it
        // off the frame so opening the keyboard is never blocked by engine setup.
        withContext(Dispatchers.IO) {
            NativeEngineBridge.tryLoad()
            NativeEngineBridge.loadSampleDictionary(context)
            // Restore words this user has taught the engine in earlier sessions.
            NativeEngineBridge.dictionaryLoadPromotions(learnedWordsFile.absolutePath)
        }
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

    // A committed transcript closes the mic window by itself (after a beat, so
    // the "Added" state is readable). Every other phase, including a failure,
    // stays until the user dismisses it: a silent auto-close would look like the
    // tap did nothing at all. The flag makes this one-shot, so re-opening the
    // window after a DONE verdict is not immediately closed again.
    var voiceAutoClosed by remember { mutableStateOf(false) }
    var voiceSessionStarted by remember { mutableStateOf(false) }
    LaunchedEffect(voiceInput.phase) {
        when (voiceInput.phase) {
            VoicePhase.STARTING -> {
                voiceAutoClosed = false
                voiceSessionStarted = true
            }
            VoicePhase.DONE -> if (overlay == OverlayView.VOICE && !voiceAutoClosed) {
                voiceAutoClosed = true
                delay(650)
                overlay = OverlayView.NONE
            }
            // The mic was torn down by the system (the field lost focus or the
            // keyboard hid): do not leave a stale window behind.
            VoicePhase.IDLE -> if (overlay == OverlayView.VOICE && voiceSessionStarted) {
                voiceSessionStarted = false
                overlay = OverlayView.NONE
            }
            else -> Unit
        }
    }

    val showChevron = prefs.showChevron
    // FUTO swaps the entire top bar for the action's own header when an action
    // window opens, so the floating toolbar must not float over the emoji panel
    // (or over any other overlay) — the panel carries its own back row instead.
    val overlayOpen = overlay != OverlayView.NONE
    val toolbarVisible = prefs.toolbar && !overlayOpen && (!showChevron || isToolbarExpanded)

    // The emoji panel takes the board's footprint plus the row the toolbar just
    // vacated, so the keyboard does not jump height when the picker opens.
    val emojiPanelHeight = if (keyboardAreaHeightPx > 0) {
        with(density) { keyboardAreaHeightPx.toDp() } + 40.dp
    } else {
        300.dp
    }

    // Gesture typing runs only on the live letter board, never in a preview, an
    // overlay, before the dictionary has loaded, or when the user turned it off.
    val swipeEnabled = prefs.swipeTyping && !preview && engineReady &&
        mode == KeyboardMode.QWERTY && overlay == OverlayView.NONE
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
        // Floating Toolbar Overlay - placed at the top above suggestions.
        // Swapped in with a plain `if`, not AnimatedVisibility: FUTO's bar appears
        // in the same frame the chevron is tapped, and our old slide+fade (~300ms
        // spring) also re-measured the whole keyboard on every frame while it ran.
        if (toolbarVisible) {
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
                },
                onAction = { actionId ->
                    if (actionId == "voice") {
                        // The mic opens our own voice window (FUTO keeps the mic on
                        // the bar and opens a window for it) instead of silently
                        // starting a recognizer behind the keyboard.
                        if (overlay == OverlayView.VOICE) {
                            onStopVoice()
                            overlay = OverlayView.NONE
                        } else {
                            overlay = OverlayView.VOICE
                            onAction("voice")
                        }
                        true
                    } else {
                        onAction(actionId)
                    }
                }
            )
        }

        // Suggestions Bar
        if (prefs.suggestions && !overlayOpen) {
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
                .onSizeChanged {
                    boardSize = it
                    // Only the plain board sets the remembered height; the emoji
                    // panel's own measure must not overwrite it.
                    if (overlay == OverlayView.NONE) keyboardAreaHeightPx = it.height
                }
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
                                "voice" -> {
                                    overlay = OverlayView.VOICE
                                    onAction("voice")
                                }
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
                        recentEmojis = recentEmojis,
                        panelHeight = emojiPanelHeight,
                        onClose = { overlay = OverlayView.NONE },
                        onBackspace = trackedDelete,
                        onEmojiClick = { emo ->
                            onCommitText(emo)
                            onEmojiUsed(emo)
                        }
                    )
                }

                OverlayView.CLIPBOARD -> {
                    ClipboardListPanel(
                        palette = palette,
                        clips = clipboardClips,
                        onClose = { overlay = OverlayView.NONE },
                        onClearAll = onClearClipboard,
                        onClipClick = { clip ->
                            onCommitText(clip)
                        }
                    )
                }

                OverlayView.VOICE -> {
                    VoiceInputOverlay(
                        palette = palette,
                        state = voiceInput,
                        onStop = onStopVoice,
                        onClose = { overlay = OverlayView.NONE }
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

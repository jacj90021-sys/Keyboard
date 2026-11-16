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


private data class AutoCorrectUndo(
    val originalRaw: String,
    val correctedWord: String
)
@Composable
fun PaperLimeKeyboardView(
    palette: KeyboardPalette,
    prefs: KeyboardPrefs,
    // Auto-capitalization: the IME service asks Android whether the caret sits at
    // the start of a sentence and hands the answer in here. `autoShiftEpoch` ticks
    // on every re-check, so Shift re-arms for each new sentence even though the
    // answer is the same "yes" every time. Previews and tests get "no".
    autoShiftWanted: Boolean = false,
    autoShiftEpoch: Long = 0,
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
    clipboardClips: List<ClipItem> = emptyList(),
    onClearClipboard: () -> Unit = {},
    onDeleteClip: (ClipItem) -> Unit = {},
    onTogglePinClip: (ClipItem) -> Unit = {},
    // Emoji recents, owned by the IME service (session-scoped, like the
    // clipboard). Previews get an empty list and a no-op recorder.
    recentEmojis: List<String> = emptyList(),
    onEmojiUsed: (String) -> Unit = {},
    // Persists the last skin tone chosen from a long-press; the service writes it
    // to preferences.
    onEmojiToneChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    // Voice input is owned by the IME service: it holds the SpeechRecognizer and
    // the input connection. The view only draws the mic window and reports taps.
    voiceInput: VoiceInputState = VoiceInputState(),
    onStopVoice: () -> Unit = {},
    // Previews (onboarding / appearance) keep the keyboard visual-only: the
    // toolbar "More" (three dots) must not replace the view with All Actions.
    preview: Boolean = false,
    // A preview host can own the layout mode (the resize screen offers its own
    // mode switcher): the board then follows [previewMode] and reports every
    // change back through [onPreviewModeChange], so tapping "?123" inside the
    // preview moves the switcher too. Left null — every other caller — the board
    // owns its mode exactly as before.
    previewMode: KeyboardMode? = null,
    onPreviewModeChange: (KeyboardMode) -> Unit = {},
    // Keyboard sizing from the Resize Keyboard screen. Null everywhere except
    // the IME host, which passes the persisted settings so the live board
    // matches the chosen mode and size.
    resize: ResizeSettings? = null
) {
    var internalMode by rememberSaveable { mutableStateOf(KeyboardMode.QWERTY) }
    val mode = previewMode ?: internalMode
    // Single funnel for every layout change, so a controlled preview host stays
    // in sync with the keys instead of the board and the switcher overwriting
    // each other.
    val setMode: (KeyboardMode) -> Unit = { next ->
        if (previewMode != null) onPreviewModeChange(next) else internalMode = next
    }
    var shiftState by rememberSaveable { mutableStateOf(ShiftState.OFF) }
    // Auto-capitalization: raise Shift when Android says a sentence starts here —
    // but never fight the user. Caps Lock stays put, and a Shift the user turned
    // off by hand is left alone until the next sentence asks again.
    LaunchedEffect(autoShiftEpoch, prefs.autoCap) {
        if (prefs.autoCap && autoShiftWanted && shiftState == ShiftState.OFF) {
            shiftState = ShiftState.SHIFT
        }
    }
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
    // Undo autocorrect on backspace: if the last space-triggered autocorrect replaced
    // the typed word, the next backspace deletes the corrected word and restores
    // the original typed form.
    var lastAutoCorrect by remember { mutableStateOf<AutoCorrectUndo?>(null) }
    var lastPastedOrInserted by remember { mutableStateOf(false) }
    var lastInsertedLength by remember { mutableStateOf(0) }
    // Gesture typing only engages once the native engine has actually loaded and
    // mapped a dictionary, so the host-JVM/test path is completely unaffected.
    var engineReady by remember { mutableStateOf(NativeEngineBridge.isLoaded()) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    // The live keyboard's height, remembered while the plain board is showing so
    // the emoji panel can take the same footprint. Only captured with no overlay
    // open, so the panel cannot feed its own size back into it.
    var keyboardAreaHeightPx by remember { mutableStateOf(0) }
    // Full height of the keyboard content (toolbar + strip + board) while no
    // overlay is open. Overlays are sized against this so they replace the
    // whole keyboard instead of only the board.
    var contentHeightPx by remember { mutableStateOf(0) }
    // Suggestions run on a background dispatcher: the query used to resolve
    // during composition, blocking the frame (next-word ranking alone cost ~9 ms
    // on the first keystroke of every word). produceState restarts when the
    // prefix changes, which also debounces a fast typist's intermediate states.
    // Suggestion cache: keep the last native result, serve a prefix filter from it
    // when possible, and debounce the native call so a fast typist does not hit
    // the engine on every intermediate prefix.
    var dictionaryCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    val nativeCandidates = remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(currentPrefix, lastCommittedWord) {
        val cached = if (currentPrefix.isBlank()) {
            emptyList()
        } else {
            nativeCandidates.value.filter { it.startsWith(currentPrefix, ignoreCase = true) }
        }
        if (cached.isNotEmpty()) {
            dictionaryCandidates = cached.take(SuggestionPlanner.MAX_CANDIDATES)
        } else {
            delay(30)
            val fresh = withContext(Dispatchers.Default) {
                SuggestionPlanner.candidates(
                    prefix = currentPrefix,
                    previousWord = lastCommittedWord,
                    suggest = NativeEngineBridge::dictionarySuggest
                )
            }
            nativeCandidates.value = fresh
            dictionaryCandidates = fresh
        }
    }
    // Emoji suggestions: a word we know an emoji for gets that emoji in front of
    // the word suggestions, so "love" offers ❤️ before anything else. The emoji is
    // never learned as a word — see the tap handler below. Remembered so the board
    // does not rebuild the list on every recomposition.
    val emojiSuggestion: String? = remember(currentPrefix, prefs.suggestEmojis) {
        if (prefs.suggestEmojis) EmojiSuggestions.forTypedWord(currentPrefix) else null
    }
    val stripSuggestions: List<String> = remember(emojiSuggestion, dictionaryCandidates) {
        if (emojiSuggestion == null) {
            dictionaryCandidates
        } else {
            EmojiSuggestions.withSuggestion(currentPrefix, dictionaryCandidates)
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
                        val originalRaw = if (rawPrefix.isNotEmpty()) rawPrefix else currentPrefix
                        repeat(typedLength) { onDelete() }
                        onCommitText(SuggestionCase.apply(rawPrefix, correction))
                        lastAutoCorrect = AutoCorrectUndo(originalRaw = originalRaw, correctedWord = correction)
                        lastCommittedWord = correction
                        learnWord(correction)
                    } else {
                        lastAutoCorrect = null
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
        if (text == " ") {
            lastPastedOrInserted = false
            lastInsertedLength = 0
        } else if (text.length > 1) {
            lastPastedOrInserted = true
            lastInsertedLength = text.length
        } else if (text.isNotEmpty() && !text[0].isLetter()) {
            lastPastedOrInserted = true
            lastInsertedLength = text.length
        } else {
            lastPastedOrInserted = false
            lastInsertedLength = 0
        }
        onCommitText(text)
    }
    val trackedDelete: () -> Unit = {
        val undo = lastAutoCorrect
        if (undo != null && currentPrefix == undo.correctedWord) {
            // Undo autocorrect: remove corrected word and restore original typed form.
            repeat(undo.correctedWord.length) { onDelete() }
            currentPrefix = undo.originalRaw.lowercase()
            rawPrefix = undo.originalRaw
            onCommitText(SuggestionCase.apply(undo.originalRaw, undo.originalRaw))
            lastAutoCorrect = null
            lastCommittedWord = ""
            lastPastedOrInserted = false
            lastInsertedLength = 0
        } else if (lastPastedOrInserted && lastInsertedLength > 0 && currentPrefix.isEmpty()) {
            // Delete inserted/pasted text in chunks (backspace deletes what was just inserted/pasted).
            repeat(lastInsertedLength) { onDelete() }
            lastPastedOrInserted = false
            lastInsertedLength = 0
            lastAutoCorrect = null
        } else {
            lastAutoCorrect = null
            lastPastedOrInserted = false
            lastInsertedLength = 0
            currentPrefix = if (currentPrefix.isNotEmpty()) currentPrefix.dropLast(1) else ""
            rawPrefix = if (rawPrefix.isNotEmpty()) rawPrefix.dropLast(1) else ""
            onDelete()
        }
    }
    val trackedEnter: () -> Unit = {
        currentPrefix = ""
        rawPrefix = ""
        onEnter()
    }

    // Stable callbacks for the key grid. Each reads the latest handler through a
    // State, so the 59-key tree does not recompose when the suggestion prefix
    // changes.
    val qwertyKeyHandler = rememberUpdatedState { char: String ->
        trackedCommit(char)
        if (shiftState == ShiftState.SHIFT) shiftState = ShiftState.OFF
    }
    val onQwertyKey = remember(qwertyKeyHandler) { { char: String -> qwertyKeyHandler.value(char) } }

    val commitKeyHandler = rememberUpdatedState { text: String -> trackedCommit(text) }
    val onCommitKey = remember(commitKeyHandler) { { text: String -> commitKeyHandler.value(text) } }

    val spaceHandler = rememberUpdatedState { trackedCommit(" ") }
    val onSpace = remember(spaceHandler) { { spaceHandler.value() } }

    val shiftHandler = rememberUpdatedState {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapTime < 350) {
            shiftState = if (shiftState == ShiftState.CAPS_LOCK) ShiftState.OFF else ShiftState.CAPS_LOCK
        } else {
            shiftState = if (shiftState == ShiftState.OFF) ShiftState.SHIFT else ShiftState.OFF
        }
        lastShiftTapTime = now
    }
    val onShift = remember(shiftHandler) { { shiftHandler.value() } }

    val backspaceHandler = rememberUpdatedState { trackedDelete() }
    val onBackspace = remember(backspaceHandler) { { backspaceHandler.value() } }

    val enterHandler = rememberUpdatedState { trackedEnter() }
    val onEnter = remember(enterHandler) { { enterHandler.value() } }

    val symbolsHandler = rememberUpdatedState {
        fnLongOverlay = false
        overlay = OverlayView.NONE
        setMode(KeyboardMode.SYMBOLS_1)
    }
    val onSymbols = remember(symbolsHandler) { { symbolsHandler.value() } }

    val fnHandler = rememberUpdatedState {
        fnLongOverlay = false
        setMode(KeyboardMode.FN)
    }
    val onFn = remember(fnHandler) { { fnHandler.value() } }

    val fnLongHandler = rememberUpdatedState { fnLongOverlay = !fnLongOverlay }
    val onFnLong = remember(fnLongHandler) { { fnLongHandler.value() } }

    val lettersHandler = rememberUpdatedState {
        fnLongOverlay = false
        setMode(KeyboardMode.QWERTY)
    }
    val onLetters = remember(lettersHandler) { { lettersHandler.value() } }

    val numpadHandler = rememberUpdatedState {
        fnLongOverlay = false
        setMode(KeyboardMode.NUMPAD)
    }
    val onNumpad = remember(numpadHandler) { { numpadHandler.value() } }

    val page1Handler = rememberUpdatedState { setMode(KeyboardMode.SYMBOLS_2) }
    val onPage1 = remember(page1Handler) { { page1Handler.value() } }

    val page2Handler = rememberUpdatedState { setMode(KeyboardMode.SYMBOLS_1) }
    val onPage2 = remember(page2Handler) { { page2Handler.value() } }

    val fnKeyHandler = rememberUpdatedState { f: String ->
        currentPrefix = ""
        rawPrefix = ""
        onCommitText("[$f]")
    }
    val onFnKey = remember(fnKeyHandler) { { f: String -> fnKeyHandler.value(f) } }

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
        keyHeight = (resize?.effectiveKeyHeight(prefs.keyHeight) ?: prefs.keyHeight).dp,
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
            if (mode == KeyboardMode.FN) setMode(KeyboardMode.QWERTY)
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
    // The entire top bar is swapped for the action's own header when an action
    // window opens, so the floating toolbar must not float over the emoji panel
    // (or over any other overlay) — the panel carries its own back row instead.
    val overlayOpen = overlay != OverlayView.NONE
    val toolbarVisible = prefs.toolbar && !overlayOpen && (!showChevron || isToolbarExpanded)

    // The emoji panel takes the board's footprint plus the row the toolbar just
    // vacated, so the keyboard does not jump height when the picker opens.
    // Overlays replace the whole keyboard content (toolbar + strip + board), so
    // they are sized against the full content height, not just the board.
    val overlayHeight = if (contentHeightPx > 0) {
        with(density) { contentHeightPx.toDp() }
    } else {
        300.dp
    }

    // Gesture typing runs only on the live letter board, never in a preview, an
    // overlay, before the dictionary has loaded, or when the user turned it off.
    val swipeEnabled = prefs.swipeTyping && !preview && engineReady &&
        mode == KeyboardMode.QWERTY && overlay == OverlayView.NONE
    val swipeMinTravelDp = when (prefs.swipeSensitivity) {
        0 -> 12.dp // High sensitivity - easier to trigger
        2 -> 28.dp // Low sensitivity - harder to trigger
        else -> 18.dp // Medium (default)
    }
    val swipeTyping = Modifier.swipeTyping(
        enabled = swipeEnabled,
        minTravelPx = with(density) { swipeMinTravelDp.toPx() }
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

    // Size the board for the chosen resize mode. Standard keeps the original
    // full-width behaviour; the other modes narrow and/or offset the board.
    val sizeModifier = when (resize?.mode) {
        ResizeMode.ONE_HANDED -> Modifier.width(resize.oneHandedWidth.dp)
        ResizeMode.SPLIT -> Modifier.fillMaxWidth(resize.splitFraction.coerceIn(0.1f, 0.9f))
        ResizeMode.FLOATING -> Modifier
            .width(resize.floatingWidth.dp)
            .offset(x = resize.floatingX.dp, y = (-resize.floatingY).dp)
        else -> Modifier.fillMaxWidth()
    }

    Column(
        modifier = modifier
            .then(sizeModifier)
            .navigationBarsPadding()
            .background(palette.paper)
            .border(1.dp, palette.line)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    if (overlay == OverlayView.NONE) contentHeightPx = it.height
                }
        ) {
            Column {
        // Floating Toolbar Overlay - placed at the top above suggestions.
        // Swapped in with a plain `if`, not AnimatedVisibility: the bar appears
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
                        // The mic opens our own voice window instead of silently
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

        // Suggestions Bar. Not gated on `suggestions` alone: the Fn key puts its
        // Esc / Ctrl chips here too, so the strip must exist whenever Fn is on
        // even if word suggestions are off (it is then an empty state until Fn).
        if (prefs.showStrip && !overlayOpen) {
            SuggestionsBar(
                palette = palette,
                isExpanded = isToolbarExpanded,
                isFnMode = mode == KeyboardMode.FN || fnLongOverlay,
                suggestionsOverride = if (mode == KeyboardMode.FN || fnLongOverlay) emptyList() else stripSuggestions,
                loading = !engineReady,
                showChevron = showChevron,
                onToggleExpand = {
                    isToolbarExpanded = !isToolbarExpanded
                    if (isToolbarExpanded) overlay = OverlayView.NONE
                },
                onSuggestionClick = { word ->
                    if (mode == KeyboardMode.FN || fnLongOverlay) {
                        if (word == "Tab") onCommitText("\t")
                        else onCommitText("[$word]")
                    } else if (word == emojiSuggestion) {
                        // An emoji finishes the word like a word suggestion does,
                        // but it is not a word: it must not be promoted into the
                        // learning dictionary, and it is no help as next-word
                        // context. It is remembered in the picker's Recent tab,
                        // and a trailing space keeps typing flowing.
                        currentPrefix = ""
                        rawPrefix = ""
                        onEmojiUsed(word)
                        onCommitText("$word ")
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
                    onKeyClick = onCommitKey
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
                        panelHeight = overlayHeight,
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
                                    setMode(
                                        when (mode) {
                                            KeyboardMode.QWERTY -> KeyboardMode.NUMPAD
                                            KeyboardMode.NUMPAD -> KeyboardMode.FN
                                            else -> KeyboardMode.QWERTY
                                        }
                                    )
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
                        panelHeight = overlayHeight,
                        activeTone = prefs.emojiTone,
                        onClose = { overlay = OverlayView.NONE },
                        onBackspace = trackedDelete,
                        onToneSelected = onEmojiToneChange,
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
                        panelHeight = overlayHeight,
                        pinnedFirst = prefs.clipboardPinnedFirst,
                        confirmDelete = prefs.clipboardConfirmDelete,
                        onClose = { overlay = OverlayView.NONE },
                        onClearAll = onClearClipboard,
                        onPaste = { item -> onCommitText(item.text) },
                        onDelete = onDeleteClip,
                        onTogglePin = onTogglePinClip
                    )
                }

                OverlayView.VOICE -> {
                    VoiceInputOverlay(
                        palette = palette,
                        panelHeight = overlayHeight,
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
                                onKeyClick = onQwertyKey,
                                onLongKeyClick = onCommitKey,
                                onShiftClick = onShift,
                                onBackspaceClick = onBackspace,
                                onSymbolsClick = onSymbols,
                                onFnClick = onFn,
                                onFnLongClick = onFnLong,
                                isFnLongActive = fnLongOverlay,
                                onSpaceClick = onSpace,
                                onEnterClick = onEnter,
                                onSpaceDrag = {}
                            )
                        }

                        KeyboardMode.SYMBOLS_1 -> {
                            SymbolsLayoutView(
                                palette = palette,
                                config = config,
                                page = 1,
                                onKeyClick = onCommitKey,
                                onSwitchPage = onPage1,
                                onLettersClick = onLetters,
                                onNumpadClick = onNumpad,
                                onBackspaceClick = onBackspace,
                                onSpaceClick = onSpace,
                                onEnterClick = onEnter
                            )
                        }

                        KeyboardMode.SYMBOLS_2 -> {
                            SymbolsLayoutView(
                                palette = palette,
                                config = config,
                                page = 2,
                                onKeyClick = onCommitKey,
                                onSwitchPage = onPage2,
                                onLettersClick = onLetters,
                                onNumpadClick = onNumpad,
                                onBackspaceClick = onBackspace,
                                onSpaceClick = onSpace,
                                onEnterClick = onEnter
                            )
                        }

                        KeyboardMode.NUMPAD -> {
                            NumpadLayoutView(
                                palette = palette,
                                config = config,
                                onKeyClick = onCommitKey,
                                onLettersClick = onLetters,
                                onSymbolsClick = onPage2,
                                onEnterClick = onEnter
                            )
                        }

                        KeyboardMode.FN -> {
                            FnKeysLayoutView(
                                palette = palette,
                                config = config,
                                onKeyClick = onFnKey,
                                onLettersClick = onLetters,
                                onBackspaceClick = onBackspace,
                                onSpaceClick = onSpace,
                                onEnterClick = onEnter
                            )
                        }
                    }
                }
            }
        }
            }
        }
    }
}

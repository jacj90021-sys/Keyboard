package com.example

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.runtime.collectAsState
import com.example.keyboard.ClipboardHistory
import com.example.keyboard.ContextAction
import com.example.keyboard.CursorMove
import com.example.keyboard.EditorActions
import com.example.keyboard.EditorConnection
import com.example.keyboard.EditorSelection
import com.example.keyboard.EmojiRecents
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.PaperLimeKeyboardView
import com.example.keyboard.ThemeChoice
import com.example.keyboard.VoiceInput
import com.example.keyboard.VoiceInputState
import com.example.keyboard.VoicePhase
import com.example.keyboard.getPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class PaperLimeInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private var inputView: ComposeView? = null
    private var currentInputEditorInfo: EditorInfo? = null

    private lateinit var prefsRepository: KeyboardPrefsRepository
    private val _prefs = MutableStateFlow(KeyboardPrefs())
    val prefs: StateFlow<KeyboardPrefs> = _prefs.asStateFlow()

    // Android only hands out the *current* primary clip, so the keyboard keeps
    // its own bounded history, captured while the IME view is visible (see
    // ClipboardHistory for the rules).
    private val clipboardHistory = ClipboardHistory()
    private val _clipboardItems = MutableStateFlow<List<String>>(emptyList())
    val clipboardItems: StateFlow<List<String>> = _clipboardItems.asStateFlow()
    private var clipboardListenerRegistered = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        runCatching {
            val manager = getSystemService(ClipboardManager::class.java) ?: return@runCatching
            val clip = manager.primaryClip ?: return@runCatching
            if (clip.itemCount == 0) return@runCatching
            val text = clip.getItemAt(0).coerceToText(this)?.toString()
            if (clipboardHistory.add(text)) {
                _clipboardItems.value = clipboardHistory.entries()
            }
        }.onFailure { Log.e(TAG, "clipboard capture failed", it) }
    }

    // Emoji recents: session-scoped like the clipboard history above. FUTO's
    // picker opens on a Recent tab, and this is what feeds ours.
    private val emojiRecents = EmojiRecents()
    private val _recentEmojis = MutableStateFlow<List<String>>(emptyList())
    val recentEmojis: StateFlow<List<String>> = _recentEmojis.asStateFlow()
    private fun recordEmoji(emoji: String) {
        if (emojiRecents.add(emoji)) {
            _recentEmojis.value = emojiRecents.entries()
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null

    // The voice window is drawn by the Compose front end, so the recognition
    // lifecycle is published as state instead of hidden behind a boolean:
    // STARTING/LISTENING keep the window on screen with the mic animating,
    // DONE lets it close once the transcript lands.
    private val _voiceInput = MutableStateFlow(VoiceInputState())
    val voiceInput: StateFlow<VoiceInputState> = _voiceInput.asStateFlow()
    private val isListening: Boolean get() = _voiceInput.value.isActive

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "UNCAUGHT in IME thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }

        runCatching {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
        }.onFailure {
            Log.e(TAG, "savedState restore failed", it)
        }

        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
            .onFailure { Log.e(TAG, "ON_CREATE failed", it) }

        // Make the service a valid owner tree for the IME window decor before any
        // input view is created. Compose needs all three owners reachable from
        // the parentPanel hierarchy.
        runCatching {
            window.window?.decorView?.let { d ->
                d.setViewTreeLifecycleOwner(this@PaperLimeInputMethodService)
                d.setViewTreeViewModelStoreOwner(this@PaperLimeInputMethodService)
                d.setViewTreeSavedStateRegistryOwner(this@PaperLimeInputMethodService)
            }
        }.onFailure { Log.e(TAG, "decor owner setup failed", it) }

        // Collect settings in the service lifecycle so the real keyboard follows
        // every app-side preference change.
        prefsRepository = KeyboardPrefsRepository(this)
        lifecycleScope.launch {
            runCatching {
                prefsRepository.prefs.collect { _prefs.value = it }
            }.onFailure { Log.e(TAG, "prefs collect failed", it) }
        }
    }

    override fun onCreateInputView(): View {
        // Reuse one view.
        inputView?.let { return it }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PaperLimeInputMethodService)
            setViewTreeViewModelStoreOwner(this@PaperLimeInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@PaperLimeInputMethodService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val p by this@PaperLimeInputMethodService.prefs.collectAsState()
                val clips by this@PaperLimeInputMethodService.clipboardItems.collectAsState()
                val voice by this@PaperLimeInputMethodService.voiceInput.collectAsState()
                val emojis by this@PaperLimeInputMethodService.recentEmojis.collectAsState()
                val palette = remember(p.theme) { getPalette(p.theme) }

                PaperLimeKeyboardView(
                    palette = palette,
                    prefs = p,
                    clipboardClips = clips,
                    recentEmojis = emojis,
                    onEmojiUsed = { this@PaperLimeInputMethodService.recordEmoji(it) },
                    voiceInput = voice,
                    onStopVoice = { stopVoiceInput() },
                    onClearClipboard = {
                        clipboardHistory.clear()
                        _clipboardItems.value = emptyList()
                    },
                    onCommitText = { text ->
                        runCatching {
                            val ic = currentInputConnection
                            if (ic != null) {
                                ic.beginBatchEdit()
                                ic.commitText(text, 1)
                                ic.endBatchEdit()
                            }
                        }.onFailure { Log.e(TAG, "commitText failed", it) }
                    },
                    onDelete = {
                        runCatching {
                            val ic = currentInputConnection
                            val selected = ic?.getSelectedText(0)
                            if (selected.isNullOrEmpty()) {
                                ic?.deleteSurroundingText(1, 0)
                            } else {
                                ic?.commitText("", 1)
                            }
                        }.onFailure { Log.e(TAG, "delete failed", it) }
                    },
                    onEnter = {
                        runCatching {
                            val ic = currentInputConnection ?: return@runCatching
                            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                            if (action != null &&
                                action != EditorInfo.IME_ACTION_NONE &&
                                action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                                if (!ic.performEditorAction(action)) {
                                    ic.commitText("\n", 1)
                                }
                            } else {
                                ic.commitText("\n", 1)
                            }
                        }.onFailure { Log.e(TAG, "enter failed", it) }
                    },
                    // The All Actions cards that need the platform text editor
                    // (clipboard, cursor, undo/redo, selection, case styling) plus
                    // the service-level actions (hide keyboard, Settings, theme
                    // cycling, autocorrect toggle).
                    onAction = { actionId -> performKeyboardAction(actionId) }
                )
            }
        }
        composeView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.setViewTreeLifecycleOwner(this@PaperLimeInputMethodService)
                v.setViewTreeViewModelStoreOwner(this@PaperLimeInputMethodService)
                v.setViewTreeSavedStateRegistryOwner(this@PaperLimeInputMethodService)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })

        inputView = composeView
        return composeView
    }

    override fun onEvaluateInputViewShown(): Boolean = true

    /**
     * Answer an All Actions card. Returns whether the action did something, so
     * unknown ids stay inert instead of pretending to work.
     */
    private fun performKeyboardAction(actionId: String): Boolean {
        if (EditorActions.handles(actionId)) {
            return runCatching { EditorActions.perform(editorConnection, actionId) }
                .onFailure { Log.e(TAG, "editor action $actionId failed", it) }
                .getOrDefault(false)
        }
        return runCatching {
            when (actionId) {
                "hide_keyboard" -> {
                    requestHideSelf(0)
                    true
                }
                "settings" -> {
                    startActivity(
                        android.content.Intent(this, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }
                "auto_correct" -> {
                    val next = !_prefs.value.autoCorrect
                    lifecycleScope.launch { prefsRepository.setBool("autoCorrect", next) }
                    true
                }
                "theme" -> {
                    // Cycle the four shipped themes; the service already streams
                    // the pref, so the palette recomposes immediately.
                    val choices = ThemeChoice.entries
                    val current = choices.indexOf(_prefs.value.theme)
                    val next = choices[(current + 1).mod(choices.size)]
                    lifecycleScope.launch { prefsRepository.setTheme(next) }
                    true
                }
                "voice" -> {
                    startVoiceInput()
                    true
                }
                else -> false
            }
        }.onFailure { Log.e(TAG, "keyboard action $actionId failed", it) }
            .getOrDefault(false)
    }

    /**
     * The one place that talks to the live `InputConnection` for editor actions.
     * It is deliberately thin: all the decision-making lives in [EditorActions],
     * which is unit tested off-device.
     */
    private val editorConnection = object : EditorConnection {
        override fun commitText(text: String): Boolean =
            currentInputConnection?.commitText(text, 1) ?: false

        override fun deleteSurroundingText(before: Int, after: Int): Boolean =
            currentInputConnection?.deleteSurroundingText(before, after) ?: false

        override fun performContextAction(action: ContextAction): Boolean {
            val id = when (action) {
                ContextAction.UNDO -> android.R.id.undo
                ContextAction.REDO -> android.R.id.redo
                ContextAction.CUT -> android.R.id.cut
                ContextAction.COPY -> android.R.id.copy
                ContextAction.PASTE -> android.R.id.paste
                ContextAction.SELECT_ALL -> android.R.id.selectAll
            }
            return currentInputConnection?.performContextMenuAction(id) ?: false
        }

        override fun sendCursorMove(move: CursorMove): Boolean {
            val keyCode = when (move) {
                CursorMove.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                CursorMove.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                CursorMove.UP -> KeyEvent.KEYCODE_DPAD_UP
                CursorMove.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            }
            // sendDownUpKeyEvents is the IME-level path editors expect for cursor
            // movement; fall back to a raw key event when no connection exists.
            val ic: InputConnection? = currentInputConnection
            if (ic == null) return false
            runCatching { sendDownUpKeyEvents(keyCode) }
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean =
            currentInputConnection?.setSelection(start, end) ?: false

        override fun selection(): EditorSelection? {
            val ic = currentInputConnection ?: return null
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
            val text = extracted.text?.toString() ?: return null
            if (extracted.selectionStart < 0 || extracted.selectionEnd < 0) return null
            return EditorSelection(text, extracted.selectionStart, extracted.selectionEnd)
        }

        override fun textBeforeCursor(maxChars: Int): String? =
            currentInputConnection?.getTextBeforeCursor(maxChars, 0)?.toString()
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        currentInputEditorInfo = info
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentInputEditorInfo = info
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        // Only watch the clipboard while the keyboard is actually on screen: a
        // background IME has no business reading what the user copies.
        if (_prefs.value.clipboardHistory && !clipboardListenerRegistered) {
            runCatching {
                getSystemService(ClipboardManager::class.java)
                    ?.addPrimaryClipChangedListener(clipboardListener)
                clipboardListenerRegistered = true
            }.onFailure { Log.e(TAG, "clipboard listen failed", it) }
        }
        if (!_prefs.value.clipboardHistory && clipboardListenerRegistered) {
            runCatching {
                getSystemService(ClipboardManager::class.java)
                    ?.removePrimaryClipChangedListener(clipboardListener)
            }.onFailure { Log.e(TAG, "clipboard stop failed", it) }
            clipboardListenerRegistered = false
        }
    }

    private fun startVoiceInput() {
        if (isListening) {
            stopVoiceInput()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available")
            _voiceInput.value = VoiceInputState(
                VoicePhase.UNAVAILABLE,
                message = "Voice input isn't available on this device"
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // The IME has no permission-request surface, so bounce to the app,
                // which prompts for RECORD_AUDIO and returns the user here.
                Log.w(TAG, "RECORD_AUDIO not granted; asking via MainActivity")
                _voiceInput.value = VoiceInputState(
                    VoicePhase.PERMISSION,
                    message = "Allow microphone access to use voice input"
                )
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_REQUEST_RECORD_AUDIO, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Log.e(TAG, "record audio prompt failed", it) }
                return
            }
        }

        // Open the mic window before the engine has confirmed anything: a tap
        // that visibly opens the mic reads as working, where the old silent
        // start read as a dead button.
        _voiceInput.value = VoiceInputState(VoicePhase.STARTING)

        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                if (_voiceInput.value.isActive) {
                    _voiceInput.value = _voiceInput.value.copy(phase = VoicePhase.LISTENING)
                }
            }

            override fun onBeginningOfSpeech() {
                if (_voiceInput.value.isActive) {
                    _voiceInput.value = _voiceInput.value.copy(phase = VoicePhase.LISTENING)
                }
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                // The transcript is still in flight; keep the window open and the
                // mic animating until onResults/onError arrives.
            }

            override fun onError(error: Int) {
                Log.w(
                    TAG,
                    "speech error: ${VoiceInput.errorName(error)} ($error)" +
                        if (VoiceInput.isActionableError(error)) " [actionable]" else ""
                )
                _voiceInput.value = VoiceInputState(
                    VoicePhase.ERROR,
                    message = VoiceInput.errorMessage(error)
                )
            }

            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = VoiceInput.bestResult(matches)
                if (text == null) {
                    _voiceInput.value = VoiceInputState(
                        VoicePhase.ERROR,
                        message = VoiceInput.errorMessage(SpeechRecognizer.ERROR_NO_MATCH)
                    )
                    return
                }
                runCatching {
                    val ic = currentInputConnection
                    if (ic != null) {
                        ic.beginBatchEdit()
                        ic.commitText(text, 1)
                        ic.endBatchEdit()
                    }
                }.onFailure { Log.e(TAG, "commit voice text failed", it) }
                _voiceInput.value = VoiceInputState(VoicePhase.DONE, partial = text)
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                _voiceInput.value = _voiceInput.value.copy(
                    partial = VoiceInput.partialCaption(matches, _voiceInput.value.partial)
                )
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            _voiceInput.value = VoiceInputState(
                VoicePhase.ERROR,
                message = "Voice input failed - try again"
            )
        }
    }

    private fun stopVoiceInput() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        _voiceInput.value = VoiceInputState()
    }

    override fun onFinishInputView(finishing: Boolean) {
        stopVoiceInput()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {
        }
        super.onFinishInputView(finishing)
        if (clipboardListenerRegistered) {
            runCatching {
                getSystemService(ClipboardManager::class.java)
                    ?.removePrimaryClipChangedListener(clipboardListener)
            }.onFailure { Log.e(TAG, "clipboard stop failed", it) }
            clipboardListenerRegistered = false
        }
        runCatching {
            if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
        }
        runCatching {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        }
    }

    override fun onDestroy() {
        stopVoiceInput()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {
        }
        super.onDestroy()
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
        store.clear()
    }

    private companion object {
        const val TAG = "PaperLimeIME"
    }
}

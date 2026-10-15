package com.example

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.example.keyboard.ContextAction
import com.example.keyboard.CursorMove
import com.example.keyboard.EditorActions
import com.example.keyboard.EditorConnection
import com.example.keyboard.EditorSelection
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.PaperLimeKeyboardView
import com.example.keyboard.ThemeChoice
import com.example.keyboard.getPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
                val palette = remember(p.theme) { getPalette(p.theme) }

                PaperLimeKeyboardView(
                    palette = palette,
                    prefs = p,
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
    }

    override fun onFinishInputView(finishing: Boolean) {
        super.onFinishInputView(finishing)
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
        super.onDestroy()
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
        store.clear()
    }

    private companion object {
        const val TAG = "PaperLimeIME"
    }
}

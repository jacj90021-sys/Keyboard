package com.example

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.runtime.collectAsState
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.KeyboardPrefsRepository
import com.example.keyboard.PaperLimeKeyboardView
import com.example.keyboard.getPalette

class PaperLimeInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private var inputView: ComposeView? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()

        // The IME shares its process with the launcher activity, so an uncaught throw here
        // looks like "the app crashed" to the user. Tag it so the cause is findable.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "UNCAUGHT in IME thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }

        // performRestore() requires the controller to be attached first; without
        // performAttach() this throws IllegalArgumentException on some savedstate
        // versions and, sharing the process with the activity, crashes the app.
        runCatching {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
        }.onFailure {
            Log.e(TAG, "savedState restore failed", it)
        }
        runCatching { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
            .onFailure { Log.e(TAG, "ON_CREATE failed", it) }
    }

    override fun onCreateInputView(): View {
        // The system reuses this service and can ask for a new input view at any time.
        // Blindingly sending ON_START/ON_RESUME from RESUMED throws
        // IllegalStateException("no event down from RESUMED") and, because the IME runs
        // in the same process as the launcher activity, that takes the whole app down.
        syncLifecycle(resumed = true)

        // Reuse one view. Returning a brand new ComposeView on every request leaks the
        // previous composition and leaves the IME window sized by an empty view.
        inputView?.let { return it }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PaperLimeInputMethodService)
            setViewTreeViewModelStoreOwner(this@PaperLimeInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@PaperLimeInputMethodService)
            setContent {
                val repository = remember { KeyboardPrefsRepository(this@PaperLimeInputMethodService) }
                val prefs by repository.prefs.collectAsState(initial = KeyboardPrefs())
                val palette = remember(prefs.theme) { getPalette(prefs.theme) }

                PaperLimeKeyboardView(
                    palette = palette,
                    prefs = prefs,
                    onCommitText = { text ->
                        currentInputConnection?.commitText(text, 1)
                    },
                    onDelete = {
                        val ic = currentInputConnection
                        val selected = ic?.getSelectedText(0)
                        if (selected.isNullOrEmpty()) {
                            ic?.deleteSurroundingText(1, 0)
                        } else {
                            ic.commitText("", 1)
                        }
                    },
                    onEnter = {
                        currentInputConnection?.sendKeyEvent(
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                        )
                        currentInputConnection?.sendKeyEvent(
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                        )
                    }
                )
            }
        }
        composeView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        inputView = composeView
        return composeView
    }

    /**
     * Without this the keyboard silently never appears on any device that reports a
     * hardware keyboard (the default emulator AVD, ChromeOS, DeX, tablets and phones
     * with a Bluetooth/USB keyboard attached): [InputMethodService]'s default
     * implementation returns false in that case and Android suppresses the soft
     * keyboard on EVERY input-source change. Nothing crashes, so the IME is also never
     * asked for a view. Always showing the keyboard is the behaviour this keyboard is
     * expected to have.
     *
     * `super` is deliberately NOT called: its return value is precisely the hardware
     * keyboard suppression being overridden here, so there is nothing to combine with.
     * The behaviour is pinned by ImeHardwareKeyboardTest.
     */
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A text field became focused: our view is being shown.
        syncLifecycle(resumed = true)
    }

    override fun onFinishInputView(finishing: Boolean) {
        super.onFinishInputView(finishing)
        syncLifecycle(resumed = false)
    }

    /**
     * Moves [lifecycleRegistry] to RESUMED (or back down to CREATED) without ever
     * emitting an event that LifecycleRegistry rejects. Guards against repeated
     * onCreateInputView / onStartInputView calls from the system.
     */
    private fun syncLifecycle(resumed: Boolean) {
        runCatching {
            val state = lifecycleRegistry.currentState
            if (resumed) {
                if (state == Lifecycle.State.RESUMED) return@runCatching
                if (state.isAtLeast(Lifecycle.State.STARTED)) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                }
                if (state.isAtLeast(Lifecycle.State.STARTED)) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            } else {
                if (state == Lifecycle.State.CREATED) return@runCatching
                if (state == Lifecycle.State.RESUMED) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                }
                if (state.isAtLeast(Lifecycle.State.STARTED)) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
            }
        }.onFailure {
            Log.e(TAG, "Lifecycle sync failed (resumed=$resumed)", it)
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

package com.example.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class KeyboardMode(val badgeText: String) {
    QWERTY("QWERTY"),
    SYMBOLS_1("SYM 1"),
    SYMBOLS_2("SYM 2"),
    NUMPAD("NUMPAD"),
    FN("FN KEYS")
}

enum class ShiftState {
    OFF,
    SHIFT,
    CAPS_LOCK
}

enum class OverlayView {
    NONE,
    TOOLBAR,
    ALL_ACTIONS,
    EMOJI,
    CLIPBOARD
}

data class KeyboardUiState(
    val textFieldValue: TextFieldValue = TextFieldValue(""),
    val mode: KeyboardMode = KeyboardMode.QWERTY,
    val shiftState: ShiftState = ShiftState.OFF,
    val overlay: OverlayView = OverlayView.NONE,
    val isToolbarExpanded: Boolean = false,
    val themeChoice: ThemeChoice = ThemeChoice.PAPER,
    val isSoundEnabled: Boolean = true,
    val toastMessage: String? = "Paper + Lime ready",
    val clipboardList: List<String> = listOf(
        "Paper + Lime is an ergonomic keyboard.",
        "Quick brown fox jumps over the lazy dog.",
        "Hello World!"
    ),
    val isListeningVoice: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

class KeyboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(KeyboardUiState())
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    private val undoStack = mutableListOf<String>("")
    private val redoStack = mutableListOf<String>()

    private var lastShiftTapTime = 0L
    private var toastJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 60)
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
    }

    fun playClickSound(isSpecial: Boolean = false) {
        if (!_uiState.value.isSoundEnabled) return
        try {
            val tone = if (isSpecial) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK
            toneGenerator?.startTone(tone, 25)
        } catch (_: Exception) {}
    }

    fun showToast(message: String) {
        toastJob?.cancel()
        _uiState.value = _uiState.value.copy(toastMessage = message)
        toastJob = viewModelScope.launch {
            delay(1800)
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
    }

    private fun pushHistory(newText: String) {
        if (undoStack.isEmpty() || undoStack.last() != newText) {
            undoStack.add(newText)
            if (undoStack.size > 50) undoStack.removeAt(0)
            redoStack.clear()
            updateUndoRedoState()
        }
    }

    private fun updateUndoRedoState() {
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.size > 1,
            canRedo = redoStack.isNotEmpty()
        )
    }

    fun onTextFieldValueChange(newValue: TextFieldValue) {
        if (newValue.text != _uiState.value.textFieldValue.text) {
            pushHistory(newValue.text)
        }
        _uiState.value = _uiState.value.copy(textFieldValue = newValue)
    }

    fun insertText(text: String) {
        playClickSound(false)
        val current = _uiState.value.textFieldValue
        val selStart = current.selection.min
        val selEnd = current.selection.max
        val currentText = current.text

        val newText = currentText.substring(0, selStart) + text + currentText.substring(selEnd)
        val newCursor = selStart + text.length
        val newTfv = TextFieldValue(newText, TextRange(newCursor))

        pushHistory(newText)

        var newShift = _uiState.value.shiftState
        if (newShift == ShiftState.SHIFT) {
            newShift = ShiftState.OFF
        }

        _uiState.value = _uiState.value.copy(
            textFieldValue = newTfv,
            shiftState = newShift
        )
    }

    fun backspace() {
        playClickSound(true)
        val current = _uiState.value.textFieldValue
        val selStart = current.selection.min
        val selEnd = current.selection.max
        val currentText = current.text

        if (selStart != selEnd) {
            val newText = currentText.substring(0, selStart) + currentText.substring(selEnd)
            val newTfv = TextFieldValue(newText, TextRange(selStart))
            pushHistory(newText)
            _uiState.value = _uiState.value.copy(textFieldValue = newTfv)
        } else if (selStart > 0) {
            val charCount = if (selStart >= 2 && Character.isSurrogatePair(currentText[selStart - 2], currentText[selStart - 1])) 2 else 1
            val newPos = selStart - charCount
            val newText = currentText.substring(0, newPos) + currentText.substring(selStart)
            val newTfv = TextFieldValue(newText, TextRange(newPos))
            pushHistory(newText)
            _uiState.value = _uiState.value.copy(textFieldValue = newTfv)
        }
    }

    fun toggleShift() {
        playClickSound(true)
        val now = System.currentTimeMillis()
        val current = _uiState.value.shiftState
        val newState = if (now - lastShiftTapTime < 350) {
            if (current == ShiftState.CAPS_LOCK) {
                ShiftState.OFF
            } else {
                showToast("CAPS LOCK ON")
                ShiftState.CAPS_LOCK
            }
        } else {
            when (current) {
                ShiftState.OFF -> {
                    showToast("Shift")
                    ShiftState.SHIFT
                }
                ShiftState.SHIFT -> {
                    showToast("Normal")
                    ShiftState.OFF
                }
                ShiftState.CAPS_LOCK -> {
                    showToast("Caps Lock Off")
                    ShiftState.OFF
                }
            }
        }
        lastShiftTapTime = now
        _uiState.value = _uiState.value.copy(shiftState = newState)
    }

    fun setMode(mode: KeyboardMode) {
        playClickSound(true)
        _uiState.value = _uiState.value.copy(
            mode = mode,
            overlay = OverlayView.NONE,
            isToolbarExpanded = false
        )
        showToast(mode.badgeText)
    }

    fun toggleToolbar() {
        val next = !_uiState.value.isToolbarExpanded
        _uiState.value = _uiState.value.copy(
            isToolbarExpanded = next,
            overlay = if (next) OverlayView.NONE else _uiState.value.overlay
        )
    }

    fun openOverlay(overlay: OverlayView) {
        _uiState.value = _uiState.value.copy(
            overlay = overlay,
            isToolbarExpanded = false
        )
    }

    fun closeOverlay() {
        _uiState.value = _uiState.value.copy(
            overlay = OverlayView.NONE,
            isToolbarExpanded = false
        )
    }

    fun undo() {
        if (undoStack.size > 1) {
            val current = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(current)
            val prev = undoStack.last()
            val newTfv = TextFieldValue(prev, TextRange(prev.length))
            _uiState.value = _uiState.value.copy(textFieldValue = newTfv)
            updateUndoRedoState()
            showToast("Undo")
        } else {
            showToast("Nothing to undo")
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(next)
            val newTfv = TextFieldValue(next, TextRange(next.length))
            _uiState.value = _uiState.value.copy(textFieldValue = newTfv)
            updateUndoRedoState()
            showToast("Redo")
        } else {
            showToast("Nothing to redo")
        }
    }

    fun selectAll() {
        val len = _uiState.value.textFieldValue.text.length
        _uiState.value = _uiState.value.copy(
            textFieldValue = _uiState.value.textFieldValue.copy(selection = TextRange(0, len))
        )
        showToast("All text selected")
    }

    fun moveCursor(delta: Int) {
        val tfv = _uiState.value.textFieldValue
        val cur = tfv.selection.start
        val target = (cur + delta).coerceIn(0, tfv.text.length)
        _uiState.value = _uiState.value.copy(
            textFieldValue = tfv.copy(selection = TextRange(target))
        )
        showToast("Cursor ${if (delta > 0) "→" else "←"}")
    }

    fun toggleCase() {
        val tfv = _uiState.value.textFieldValue
        val start = tfv.selection.min
        val end = tfv.selection.max
        val full = tfv.text
        if (start == end) {
            val transformed = if (full == full.uppercase()) full.lowercase() else full.uppercase()
            pushHistory(transformed)
            _uiState.value = _uiState.value.copy(
                textFieldValue = TextFieldValue(transformed, TextRange(transformed.length))
            )
        } else {
            val slice = full.substring(start, end)
            val transformed = if (slice == slice.uppercase()) slice.lowercase() else slice.uppercase()
            val newText = full.substring(0, start) + transformed + full.substring(end)
            pushHistory(newText)
            _uiState.value = _uiState.value.copy(
                textFieldValue = TextFieldValue(newText, TextRange(start, start + transformed.length))
            )
        }
        showToast("Case toggled")
    }

    fun copyToClipboard(context: Context) {
        val tfv = _uiState.value.textFieldValue
        val start = tfv.selection.min
        val end = tfv.selection.max
        val textToCopy = if (start != end) tfv.text.substring(start, end) else tfv.text

        if (textToCopy.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PaperLime", textToCopy))
            val currentList = _uiState.value.clipboardList.toMutableList()
            if (!currentList.contains(textToCopy)) {
                currentList.add(0, textToCopy)
            }
            _uiState.value = _uiState.value.copy(clipboardList = currentList)
            showToast("Copied to clipboard")
        } else {
            showToast("Nothing to copy")
        }
    }

    fun pasteFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotEmpty()) {
                insertText(text)
                showToast("Pasted")
                return
            }
        }
        if (_uiState.value.clipboardList.isNotEmpty()) {
            insertText(_uiState.value.clipboardList.first())
            showToast("Pasted saved clip")
        } else {
            showToast("Clipboard is empty")
        }
    }

    fun clearPad() {
        pushHistory("")
        _uiState.value = _uiState.value.copy(textFieldValue = TextFieldValue(""))
        showToast("Pad cleared")
    }

    fun clearClipboardClips() {
        _uiState.value = _uiState.value.copy(clipboardList = emptyList())
        showToast("Clipboard history cleared")
    }

    fun cycleTheme() {
        val values = ThemeChoice.entries
        val nextIndex = (values.indexOf(_uiState.value.themeChoice) + 1) % values.size
        val next = values[nextIndex]
        _uiState.value = _uiState.value.copy(themeChoice = next)
        showToast("Theme: ${next.displayName}")
    }

    fun toggleSound() {
        val next = !_uiState.value.isSoundEnabled
        _uiState.value = _uiState.value.copy(isSoundEnabled = next)
        showToast(if (next) "Key Sound: On" else "Key Sound: Muted")
    }

    fun simulateVoiceInput() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isListeningVoice = true)
            showToast("Listening...")
            delay(1400)
            _uiState.value = _uiState.value.copy(isListeningVoice = false)
            val phrases = listOf(
                "Paper and Lime soft keyboard is typing.",
                "See you soon!",
                "Thanks for using the app."
            )
            insertText(phrases.random() + " ")
            showToast("Voice transcription added")
        }
    }
}

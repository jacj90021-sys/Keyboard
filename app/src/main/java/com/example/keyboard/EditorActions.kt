package com.example.keyboard

import java.util.Locale

/**
 * The editing primitives the "All Actions" cards drive.
 *
 * Every action the keyboard's action grid can request is expressed here against
 * a tiny [EditorConnection] surface rather than `android.view.inputmethod.
 * InputConnection`, so the dispatch and the text maths are plain Kotlin and unit
 * testable without an Android runtime. The adapter in the IME service is the
 * only place that touches the platform.
 */
enum class ContextAction { UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL }

enum class CursorMove { LEFT, RIGHT, UP, DOWN }

/** Text plus cursor/selection offsets around the current editing position. */
data class EditorSelection(val text: String, val start: Int, val end: Int)

/**
 * The platform operations an [EditorActions] dispatch may request. Implementations
 * return `false` when the editor refused or no connection exists.
 */
interface EditorConnection {
    fun commitText(text: String): Boolean
    fun deleteSurroundingText(before: Int, after: Int): Boolean
    fun performContextAction(action: ContextAction): Boolean
    fun sendCursorMove(move: CursorMove): Boolean
    fun setSelection(start: Int, end: Int): Boolean
    fun selection(): EditorSelection?
    fun textBeforeCursor(maxChars: Int): String?
}

/**
 * Maps an action-grid id onto [EditorConnection] calls.
 *
 * Actions that are purely local to the keyboard view (opening an overlay, cycling
 * a layout, toggling a preference) never reach here; they are handled where the
 * view state lives. [ids] is the single source of truth for which cards this
 * class owns.
 */
object EditorActions {

    /** Grid ids dispatched to [EditorConnection]; everything else is view-local. */
    val ids: Set<String> = setOf(
        "undo",
        "redo",
        "copy",
        "cut",
        "paste",
        "select_all",
        "arrow_left",
        "arrow_right",
        "arrow_up",
        "arrow_down",
        "word_styles",
        "text_editor"
    )

    fun handles(actionId: String): Boolean = actionId in ids

    /** Perform [actionId], returning whether the editor accepted something. */
    fun perform(connection: EditorConnection, actionId: String): Boolean = when (actionId) {
        "undo" -> connection.performContextAction(ContextAction.UNDO)
        "redo" -> connection.performContextAction(ContextAction.REDO)
        "copy" -> connection.performContextAction(ContextAction.COPY)
        "cut" -> connection.performContextAction(ContextAction.CUT)
        "paste" -> connection.performContextAction(ContextAction.PASTE)
        "select_all" -> connection.performContextAction(ContextAction.SELECT_ALL)
        "arrow_left" -> connection.sendCursorMove(CursorMove.LEFT)
        "arrow_right" -> connection.sendCursorMove(CursorMove.RIGHT)
        "arrow_up" -> connection.sendCursorMove(CursorMove.UP)
        "arrow_down" -> connection.sendCursorMove(CursorMove.DOWN)
        "word_styles" -> cycleWordCase(connection)
        "text_editor" -> selectWord(connection)
        else -> false
    }

    /**
     * Cycle the word before the cursor through lowercase -> Titlecase -> UPPERCASE.
     *
     * An IME cannot apply bold or italic, so "word styles" is the one text styling
     * a keyboard can actually perform: rewriting the case of the word under the
     * cursor. The original casing decides the next step, so a word the user typed
     * in caps returns to lowercase instead of to Titlecase. The word is erased and
     * retyped rather than edited in place, which keeps multi-byte and accented
     * words intact.
     */
    fun cycleWordCase(connection: EditorConnection): Boolean {
        val before = connection.textBeforeCursor(MAX_WORD_LOOKBACK) ?: return false
        val word = trailingWord(before) ?: return false
        val styled = nextCaseVariant(word)
        if (styled == word) return false
        if (!connection.deleteSurroundingText(word.length, 0)) return false
        return connection.commitText(styled)
    }

    /** Select the word surrounding the cursor, for follow-up copy or cut. */
    fun selectWord(connection: EditorConnection): Boolean {
        val selection = connection.selection() ?: return false
        val bounds = wordBounds(selection.text, selection.start, selection.end) ?: return false
        return connection.setSelection(bounds.first, bounds.second)
    }

    /**
     * The word immediately before the cursor, or `null` when the cursor follows a
     * space or punctuation. The word must *end* at the cursor: rewriting across a
     * trailing gap would delete characters the user already committed.
     */
    fun trailingWord(before: String): String? {
        if (before.isEmpty() || !isWordChar(before.last())) return null
        var start = before.length
        while (start > 0 && isWordChar(before[start - 1])) start--
        val word = before.substring(start)
        return if (word.any { it.isLetter() }) word else null
    }

    /** lowercase -> "Titlecase" -> "UPPERCASE" -> lowercase, for a whole word. */
    fun nextCaseVariant(word: String): String {
        val letters = word.filter { it.isLetter() }
        return when {
            letters.isEmpty() -> word
            letters.all { it.isUpperCase() } -> word.lowercase(Locale.getDefault())
            word.firstOrNull()?.isUpperCase() == true -> word.uppercase(Locale.getDefault())
            else -> word.lowercase(Locale.getDefault()).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        }
    }

    /**
     * Absolute `[start, end]` bounds of the word containing the cursor, clamped to
     * [text]. A collapsed cursor on whitespace falls back to the word before it,
     * or the word after it at the start of the text, so the action is never a
     * silent no-op mid-sentence.
     */
    fun wordBounds(text: String, start: Int, end: Int): Pair<Int, Int>? {
        val cursorStart = start.coerceIn(0, text.length)
        val cursorEnd = end.coerceIn(cursorStart, text.length)
        var left = cursorStart
        while (left > 0 && isWordChar(text[left - 1])) left--
        var right = cursorEnd
        while (right < text.length && isWordChar(text[right])) right++
        if (left != right) return left to right

        // Cursor sits on whitespace: prefer the preceding word.
        var wordEnd = left
        while (wordEnd > 0 && !isWordChar(text[wordEnd - 1])) wordEnd--
        if (wordEnd > 0) {
            var wordStart = wordEnd
            while (wordStart > 0 && isWordChar(text[wordStart - 1])) wordStart--
            return wordStart to wordEnd
        }
        // Start of the text: take the following word instead.
        var after = right
        while (after < text.length && !isWordChar(text[after])) after++
        if (after >= text.length) return null
        var wordEnd2 = after
        while (wordEnd2 < text.length && isWordChar(text[wordEnd2])) wordEnd2++
        return after to wordEnd2
    }

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c.isDigit() || c == '\''

    /** Enough for any real word; bounds the look-behind on very long text. */
    private const val MAX_WORD_LOOKBACK = 96
}

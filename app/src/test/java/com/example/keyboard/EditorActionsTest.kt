package com.example.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The action grid used to render eleven cards that did nothing at all. These
 * tests pin the dispatch each of them performs, plus the two text-rewriting
 * actions (case cycling, word selection) whose maths must not depend on an
 * Android runtime.
 */
class EditorActionsTest {

    /** Records every operation and can be primed with a selection + text. */
    private class FakeEditor(
        var textBefore: String? = "hello ",
        var currentSelection: EditorSelection? = null
    ) : EditorConnection {
        val calls = mutableListOf<String>()
        var selected: Pair<Int, Int>? = null

        override fun commitText(text: String): Boolean {
            calls += "commit:$text"
            return true
        }

        override fun deleteSurroundingText(before: Int, after: Int): Boolean {
            calls += "delete:$before:$after"
            return true
        }

        override fun performContextAction(action: ContextAction): Boolean {
            calls += "context:$action"
            return true
        }

        override fun sendCursorMove(move: CursorMove): Boolean {
            calls += "cursor:$move"
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            selected = start to end
            calls += "select:$start:$end"
            return true
        }

        override fun selection(): EditorSelection? = currentSelection

        override fun textBeforeCursor(maxChars: Int): String? = textBefore?.takeLast(maxChars)
    }

    @Test
    fun everyHandledIdIsDispatched() {
        assertEquals(
            setOf(
                "undo", "redo", "copy", "cut", "paste", "select_all",
                "arrow_left", "arrow_right", "arrow_up", "arrow_down",
                "word_styles", "text_editor"
            ),
            EditorActions.ids
        )
    }

    @Test
    fun clipboardCardsUseTheContextMenuActions() {
        val editor = FakeEditor()
        assertTrue(EditorActions.perform(editor, "copy"))
        assertTrue(EditorActions.perform(editor, "cut"))
        assertTrue(EditorActions.perform(editor, "paste"))
        assertTrue(EditorActions.perform(editor, "select_all"))
        assertEquals(
            listOf("context:COPY", "context:CUT", "context:PASTE", "context:SELECT_ALL"),
            editor.calls
        )
    }

    @Test
    fun undoRedoUseTheContextMenuActions() {
        val editor = FakeEditor()
        assertTrue(EditorActions.perform(editor, "undo"))
        assertTrue(EditorActions.perform(editor, "redo"))
        assertEquals(listOf("context:UNDO", "context:REDO"), editor.calls)
    }

    @Test
    fun arrowCardsMoveTheCursor() {
        val editor = FakeEditor()
        for (id in listOf("arrow_left", "arrow_right", "arrow_up", "arrow_down")) {
            EditorActions.perform(editor, id)
        }
        assertEquals(
            listOf("cursor:LEFT", "cursor:RIGHT", "cursor:UP", "cursor:DOWN"),
            editor.calls
        )
    }

    @Test
    fun unknownIdsAreNotHandled() {
        val editor = FakeEditor()
        assertFalse(EditorActions.handles("language"))
        assertFalse(EditorActions.handles("emojis"))
        assertFalse(EditorActions.perform(editor, "language"))
        assertTrue(editor.calls.isEmpty())
    }

    @Test
    fun wordStylesCycleLowerTitleUpper() {
        // lowercase -> "Titlecase" -> "UPPERCASE" -> lowercase, applied to the
        // real word so a capitalised word goes to caps and an all-caps word
        // returns to lowercase.
        assertEquals("Hello", EditorActions.nextCaseVariant("hello"))
        assertEquals("HELLO", EditorActions.nextCaseVariant("Hello"))
        assertEquals("hello", EditorActions.nextCaseVariant("HELLO"))
        // Apostrophes survive every step.
        assertEquals("Don't", EditorActions.nextCaseVariant("don't"))
        assertEquals("DON'T", EditorActions.nextCaseVariant("Don't"))
        assertEquals("don't", EditorActions.nextCaseVariant("DON'T"))
    }

    @Test
    fun wordStylesRewriteTheWordBeforeTheCursor() {
        val editor = FakeEditor(textBefore = "say hello")
        assertTrue(EditorActions.perform(editor, "word_styles"))
        // "hello" -> "Hello": erased then retyped, which keeps accented and
        // multi-byte words intact (no partial-character arithmetic).
        assertEquals(listOf("delete:5:0", "commit:Hello"), editor.calls)
    }

    @Test
    fun wordStylesDoNothingAfterASpace() {
        // The word is finished and committed; rewriting it would delete the
        // user's space along with the word.
        val editor = FakeEditor(textBefore = "hello ")
        assertFalse(EditorActions.perform(editor, "word_styles"))
        assertTrue(editor.calls.isEmpty())
    }

    @Test
    fun trailingWordKeepsApostrophesAndStopsAtPunctuation() {
        assertEquals("don't", EditorActions.trailingWord("well don't"))
        assertEquals("Hello", EditorActions.trailingWord("oh, Hello"))
        assertNull(EditorActions.trailingWord("finished. "))
        assertNull(EditorActions.trailingWord(""))
    }

    @Test
    fun textEditorSelectsTheWordUnderTheCursor() {
        val editor = FakeEditor(
            currentSelection = EditorSelection(text = "the quick fox", start = 6, end = 6)
        )
        assertTrue(EditorActions.perform(editor, "text_editor"))
        assertEquals(4 to 9, editor.selected)
        assertTrue(editor.calls == listOf("select:4:9"))
    }

    @Test
    fun wordBoundsSpanTheWholeWord() {
        // "the quick fox": t0 h1 e2 ' '3 q4 u5 i6 c7 k8 ' '9 f10 o11 x12
        assertEquals(4 to 9, EditorActions.wordBounds("the quick fox", 6, 6))
        // a cursor on either boundary still selects the word it touches
        assertEquals(4 to 9, EditorActions.wordBounds("the quick fox", 4, 4))
        assertEquals(4 to 9, EditorActions.wordBounds("the quick fox", 9, 9))
        // whitespace between two spaces falls back to the preceding word
        assertEquals(0 to 3, EditorActions.wordBounds("foo   bar", 4, 4))
        // ... or the following one when the text starts with spaces
        assertEquals(2 to 4, EditorActions.wordBounds("  ab", 1, 1))
        assertEquals(0 to 3, EditorActions.wordBounds("the quick", 0, 0))
    }

    @Test
    fun wordBoundsClampOutOfRangeSelection() {
        assertEquals(0 to 3, EditorActions.wordBounds("the", 99, 120))
    }

    @Test
    fun wordBoundsReturnNullOnWhitespaceOnlyText() {
        assertNull(EditorActions.wordBounds("   ", 1, 1))
    }

    @Test
    fun wordBoundsHandleSelectionsAcrossWords() {
        // selecting "ick fox" spans the whole of "quick fox"
        assertEquals(4 to 13, EditorActions.wordBounds("the quick fox", 6, 12))
    }
}

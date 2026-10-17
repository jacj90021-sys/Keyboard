package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.keyboard.KeyboardPrefs
import com.example.keyboard.PaperLimeKeyboardView
import com.example.keyboard.ThemeChoice
import com.example.keyboard.getPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SPACE = "English (US)"

/**
 * Drives the REAL keyboard through every interactive path: letters, number row, symbols,
 * numpad, Fn, backspace/enter/space, every toolbar overlay, suggestions, and every
 * preference toggle. A crash or a dead handler in any path fails the build here instead
 * of on the user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var prefs by mutableStateOf(KeyboardPrefs())
    private var preview by mutableStateOf(false)
    private var typed = ""
    private var deleted = 0
    private var entered = 0

    /** Composes once; later calls only change state so the composable recomposes. */
    private fun start() {
        compose.setContent {
            PaperLimeKeyboardView(
                palette = getPalette(prefs.theme),
                prefs = prefs,
                onCommitText = { typed += it },
                onDelete = { deleted++ },
                onEnter = { entered++ },
                preview = preview
            )
        }
    }

    private fun use(p: KeyboardPrefs) {
        typed = ""; deleted = 0; entered = 0
        prefs = p
    }

    private fun clickKey(key: String) = compose.onNodeWithTag("key:$key").performClick()
    private fun clickTag(tag: String) = compose.onNodeWithTag(tag).performClick()
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `typing a word commits every letter`() {
        start()
        "hello".forEach { clickKey(it.toString()) }
        assertEquals("hello", typed)
    }

    @Test
    fun `number row space backspace and enter all fire`() {
        start()
        clickKey("7")
        assertTrue("number row should commit 7, got '$typed'", typed.contains("7"))
        clickKey(SPACE)
        assertTrue("space should commit a space", typed.endsWith(" "))
        clickKey("⌫")
        assertEquals(1, deleted)
        clickKey("↵")
        assertEquals(1, entered)
    }

    @Test
    fun `symbols pages and numpad navigation works`() {
        start()
        clickKey("?123")            // qwerty -> symbols page 1
        clickKey("=\\<")            // -> symbols page 2
        clickKey("?123")            // -> back to page 1
        clickKey("12\n34")          // -> numpad
        clickKey("ABC")             // -> letters
        clickKey("a")
        assertTrue("expected to be back on qwerty, got '$typed'", typed.contains("a"))
    }

    @Test
    fun `fn key opens the fn page and returns to letters`() {
        start()
        use(KeyboardPrefs(fn = true))
        clickKey("Fn")
        clickKey("ABC")
        clickKey("b")
        assertTrue(typed.contains("b"))
    }

    @Test
    fun `every toolbar overlay opens and closes`() {
        start()
        // Opening an overlay hides the toolbar (FUTO swaps the whole bar for the
        // action's own header), so each overlay is closed before the next tool is
        // reached for.
        clickTag("tool:emoji")
        clickTag("emoji:back")
        clickTag("tool:clipboard")
        clickTag("clipboard:back")
        clickTag("tool:translate")   // inert: closes any overlay, shows nothing
        clickTag("tool:gif")         // inert too
        clickTag("tool:more")
        clickTag("allactions:back")
        compose.onNodeWithTag("key:a").assertIsDisplayed()
    }

    @Test
    fun `three dot is inert in preview mode`() {
        start()
        preview = true
        compose.waitForIdle()
        clickTag("tool:more")
        compose.onNodeWithTag("key:a").assertIsDisplayed()
    }

    @Test
    fun `suggestion strip does not invent words when nothing is typed`() {
        start()
        // The engine has no suggestions (no native library in the test JVM) and
        // nothing has been typed, so the strip must stay silent rather than show
        // the old hardcoded filler words.
        listOf("Yes", "Okay", "Thanks", "Awesome").forEach { word ->
            compose.onAllNodesWithText(word).assertCountEquals(0)
        }
    }

    @Test
    fun `turning fn off drops the fn layout and fn suggestions`() {
        start()
        use(KeyboardPrefs(fn = true))
        clickKey("Fn")
        compose.onNodeWithText("Esc").assertIsDisplayed()
        use(KeyboardPrefs(fn = false))
        compose.waitForIdle()
        compose.onNodeWithTag("key:a").assertIsDisplayed()
        assertFalse(
            "Esc suggestion must be gone once Fn is off",
            compose.onAllNodesWithText("Esc").fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun `turning the toolbar off hides it immediately`() {
        start()
        use(KeyboardPrefs(toolbar = true, suggestions = true, arrow = true, startOpen = true))
        assertTrue("toolbar should start visible", exists("tool:more"))
        use(KeyboardPrefs(toolbar = false, suggestions = true, arrow = true, startOpen = true))
        compose.waitForIdle()
        assertFalse("toolbar must vanish the moment the toggle goes off", exists("tool:more"))
    }

    @Test
    fun `fn key appears only when the fn toggle is on`() {
        start()
        use(KeyboardPrefs(fn = false))
        assertFalse("Fn key must be absent while the toggle is off", exists("key:Fn"))
        use(KeyboardPrefs(fn = true))
        compose.waitForIdle()
        assertTrue("Fn key must appear the moment the toggle goes on", exists("key:Fn"))
    }

    @Test
    fun `every theme renders`() {
        start()
        ThemeChoice.entries.forEach { choice ->
            use(KeyboardPrefs(theme = choice))
            compose.onNodeWithTag("key:a").assertIsDisplayed()
        }
    }

    @Test
    fun `every key height renders`() {
        start()
        listOf(38, 44, 50).forEach { h ->
            use(KeyboardPrefs(keyHeight = h))
            compose.onNodeWithTag("key:a").assertIsDisplayed()
        }
    }

    @Test
    fun `every extra toggle combination renders`() {
        start()
        listOf(true, false).forEach { fn ->
            listOf(true, false).forEach { numRow ->
                listOf(true, false).forEach { toolbar ->
                    listOf(true, false).forEach { suggestions ->
                        use(
                            KeyboardPrefs(
                                fn = fn, numRow = numRow,
                                toolbar = toolbar, suggestions = suggestions
                            )
                        )
                        compose.onNodeWithTag("key:a").assertIsDisplayed()
                    }
                }
            }
        }
    }
}
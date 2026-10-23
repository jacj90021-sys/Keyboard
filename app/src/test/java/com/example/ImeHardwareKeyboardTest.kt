package com.example

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A plain IME with no overrides, used as the reference for the platform default.
 */
class PlainInputMethodService : InputMethodService()

/**
 * "The keyboard doesn't show" on a real device is almost always the platform
 * suppressing the soft keyboard because a hardware keyboard is available. These tests
 * pin the behaviour: the platform default hides the keyboard, ours must not — until
 * "Hide when USB keyboard is detected" is switched on, which is the one setting that
 * lets the soft keyboard bow out to a hard one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "keysexposed-qwerty")
class ImeHardwareKeyboardTest {

    /** A copy of the live configuration with a chosen hard-keyboard state. */
    private fun withHardKeyboard(keyboard: Int, hardKeyboardHidden: Int): Configuration =
        Configuration(RuntimeEnvironment.getApplication().resources.configuration).apply {
            this.keyboard = keyboard
            this.hardKeyboardHidden = hardKeyboardHidden
        }

    @Test
    fun `keyboard still shows with a hardware keyboard attached`() {
        val config = RuntimeEnvironment.getApplication().resources.configuration
        // This is the exact condition InputMethodService uses to suppress the soft
        // keyboard: a reported hard keyboard that is not hidden.
        assertTrue(
            "test setup: expected a hard keyboard that is not hidden, " +
                "got keyboard=${config.keyboard}, hardKeyboardHidden=${config.hardKeyboardHidden}",
            config.keyboard != Configuration.KEYBOARD_NOKEYS &&
                config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        )

        val plain = Robolectric.buildService(PlainInputMethodService::class.java).create().get()
        assertFalse(
            "sanity check: the platform default suppresses the soft keyboard here",
            plain.onEvaluateInputViewShown()
        )

        val keyboard = Robolectric.buildService(PaperLimeInputMethodService::class.java).create().get()
        assertTrue(
            "sanity check: with the switch off, Plug Keyboard must keep showing",
            keyboard.onEvaluateInputViewShown()
        )
    }

    @Test
    fun `the hide switch stands for the platform rule, not a new one`() {
        val attached = withHardKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO)
        val plain = Robolectric.buildService(PlainInputMethodService::class.java).create().get()
        assertFalse(
            "test setup: the platform hides the soft keyboard for this configuration",
            plain.onEvaluateInputViewShown()
        )
        assertFalse(
            "with the switch on we must hide exactly where the platform does",
            shouldShowInputView(hideWhenHardKeyboard = true, config = attached)
        )
    }

    @Test
    fun `with the switch off a hardware keyboard changes nothing`() {
        assertTrue(
            shouldShowInputView(
                false,
                withHardKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO)
            )
        )
        assertTrue(
            shouldShowInputView(
                false,
                withHardKeyboard(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES)
            )
        )
    }

    @Test
    fun `with the switch on only a real, available keyboard hides the board`() {
        assertFalse(
            shouldShowInputView(
                true,
                withHardKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO)
            )
        )
        assertFalse(
            shouldShowInputView(
                true,
                withHardKeyboard(Configuration.KEYBOARD_12KEY, Configuration.HARDKEYBOARDHIDDEN_UNDEFINED)
            )
        )
        // No hard keys at all, or a keyboard folded away, is no reason to hide.
        assertTrue(
            shouldShowInputView(
                true,
                withHardKeyboard(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES)
            )
        )
        assertTrue(
            shouldShowInputView(
                true,
                withHardKeyboard(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES)
            )
        )
    }
}

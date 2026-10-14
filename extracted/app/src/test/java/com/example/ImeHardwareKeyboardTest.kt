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
 * pin the behaviour: the platform default hides the keyboard, ours must not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "keysexposed-qwerty")
class ImeHardwareKeyboardTest {

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
            "Paper + Lime must stay visible while a hardware keyboard is attached",
            keyboard.onEvaluateInputViewShown()
        )
    }
}

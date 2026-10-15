package com.example

import android.view.inputmethod.EditorInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real IME service through the lifecycle Android uses when a user picks
 * the keyboard from the system picker and then focuses a text field. The IME shares
 * a process with MainActivity, so any throw here is an app crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeLifecycleTest {

    @Test
    fun `input view can be created repeatedly and shown`() {
        val service = Robolectric.buildService(PaperLimeInputMethodService::class.java)
            .create()
            .get()

        // First show.
        val first = service.onCreateInputView()
        service.onStartInputView(EditorInfo(), false)

        // Hide, then show again -- the system does this every time focus changes.
        service.onFinishInputView(false)
        val second = service.onCreateInputView()
        service.onStartInputView(EditorInfo(), false)

        // And a third time, which is what used to throw "no event down from RESUMED".
        service.onFinishInputView(false)
        val third = service.onCreateInputView()
        service.onStartInputView(null, false)

        println("views: $first / $second / $third")
        check(first != null && second != null && third != null)
    }

    @Test
    fun `service create and destroy is clean`() {
        val controller = Robolectric.buildService(PaperLimeInputMethodService::class.java).create()
        val service = controller.get()
        service.onCreateInputView()
        controller.destroy()
    }
}
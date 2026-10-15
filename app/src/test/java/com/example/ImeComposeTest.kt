package com.example

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Forces the IME's ComposeView to actually compose, measure and lay out. Robolectric
 * will not do this on its own, so without this test a composition-time crash is invisible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeComposeTest {

    private fun composeAndLayout(view: View) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        // Let the composition and any launched effects run.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `ime keyboard composes without throwing`() {
        val service = Robolectric.buildService(PaperLimeInputMethodService::class.java)
            .create()
            .get()
        val view = service.onCreateInputView()
        composeAndLayout(view)
        println("composed OK, size=${view.measuredWidth}x${view.measuredHeight}")
    }
}
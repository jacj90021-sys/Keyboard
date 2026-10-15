package com.example.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/**
 * Capture a swipe-typing stroke without stealing ordinary taps.
 *
 * The modifier watches the [PointerEventPass.Initial] pass, so it sees every
 * pointer before the individual keys do. It stays entirely passive until the
 * finger has travelled past [minTravelPx]; only then does it start consuming
 * events, which cancels whatever key the gesture happened to start on. A plain
 * tap never crosses the threshold, so the normal `combinedClickable` on
 * [SoftKey] still fires exactly as before.
 *
 * The final path is delivered as a flat `[x0,y0,x1,y1,...]` array in the
 * modifier's own local coordinates, which is the same space the layout grid is
 * built in.
 */
fun Modifier.swipeTyping(
    enabled: Boolean,
    minTravelPx: Float,
    onStroke: (FloatArray) -> Unit
): Modifier {
    if (!enabled) return this
    return this.pointerInput(enabled, minTravelPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val origin = down.position
            val path = ArrayList<Float>(32)
            path.add(origin.x)
            path.add(origin.y)

            var swiping = false
            var furthest = 0f
            val pointerId = down.id

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                if (change.changedToUp()) {
                    if (swiping) {
                        path.add(change.position.x)
                        path.add(change.position.y)
                    }
                    break
                }

                val position = change.position
                path.add(position.x)
                path.add(position.y)

                furthest = maxOf(furthest, hypot(position.x - origin.x, position.y - origin.y))
                if (!swiping && furthest > minTravelPx) {
                    swiping = true
                }
                if (swiping) {
                    change.consume()
                }
            }

            // Three or more samples is the native decoder's minimum.
            if (swiping && path.size >= 6) {
                onStroke(path.toFloatArray())
            }
        }
    }
}

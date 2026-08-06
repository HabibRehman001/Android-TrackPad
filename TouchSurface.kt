package com.example.phonetrackpad

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Raw Android View for touch capture - deliberately NOT Compose's
 * pointerInput for this piece. Two APIs only a real View gives us
 * direct access to:
 *
 * 1. requestUnbufferedDispatch(source) - tells the input system to stop
 *    batching touch samples to the display's vsync and dispatch each
 *    raw digitizer sample as its own onTouchEvent call as soon as it
 *    arrives. Without this, even a 240Hz touchscreen gets throttled
 *    down to your screen's refresh rate (likely 90-120Hz) before your
 *    app ever sees it - Compose's pointerInput doesn't call this for
 *    you, so it inherits that same throttling.
 * 2. MotionEvent.getHistoricalX/Y - defensively, in case any batching
 *    still slips through, every intermediate sample since the last
 *    call is replayed rather than just the latest position.
 *
 * onTouchEvent runs synchronously on the input dispatch thread, so
 * there's no coroutine hop or recomposition between "finger moved" and
 * "packet queued" - just this function and GestureProcessor.
 */
class TouchpadView(
    context: Context,
    private val gestureProcessor: GestureProcessor
) : View(context) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // API 26+: requestUnbufferedDispatch(MotionEvent)
                // API 30+: requestUnbufferedDispatch(int) — do NOT use that here;
                // Hot 9 is Android 10 (API 29) and crashes with NoSuchMethodError.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requestUnbufferedDispatch(event)
                }
                gestureProcessor.onFingerDown(event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    gestureProcessor.onSecondFingerDown()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerCount = event.pointerCount
                val historySize = event.historySize

                if (pointerCount == 1) {
                    for (h in 0 until historySize) {
                        gestureProcessor.onFingerMove(event.getHistoricalX(0, h), event.getHistoricalY(0, h))
                    }
                    gestureProcessor.onFingerMove(event.x, event.y)
                } else if (pointerCount >= 2) {
                    for (h in 0 until historySize) {
                        var sumY = 0f
                        for (p in 0 until pointerCount) sumY += event.getHistoricalY(p, h)
                        gestureProcessor.onTwoFingerSample(sumY / pointerCount)
                    }
                    var sumY = 0f
                    for (p in 0 until pointerCount) sumY += event.getY(p)
                    gestureProcessor.onTwoFingerSample(sumY / pointerCount)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // A non-final finger lifted. If we were in two-finger mode,
                // treat this as the end of the gesture rather than trying
                // to hand tracking off to whichever finger remains down.
                if (event.pointerCount == 2) {
                    gestureProcessor.onGestureEnd(event.x, event.y)
                }
            }

            MotionEvent.ACTION_UP -> {
                gestureProcessor.onGestureEnd(event.x, event.y)
            }

            MotionEvent.ACTION_CANCEL -> {
                gestureProcessor.onGestureCancel()
            }
        }
        return true
    }
}

@Composable
fun TouchSurface(socketManager: SocketManager, modifier: Modifier = Modifier) {
    val gestureProcessor = remember { GestureProcessor { packet -> socketManager.send(packet) } }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context -> TouchpadView(context, gestureProcessor) }
    )
}
finalfinalfinal
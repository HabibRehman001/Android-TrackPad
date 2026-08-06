package com.example.phonetrackpad

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * Raw View touch capture — not Compose pointerInput.
 * Uses requestUnbufferedDispatch(MotionEvent) (API 26+) so Hot 9 (API 29) works.
 */
class TouchpadView(
    context: Context,
    private val gestureProcessor: GestureProcessor,
) : View(context) {

    init {
        setBackgroundColor(Color.parseColor("#121212"))
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        requestUnbufferedDispatch(event)
                    } catch (_: Throwable) {
                        // OEM may lack the API despite SDK_INT — ignore
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                gestureProcessor.onFingerDown(event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    gestureProcessor.onSecondFingerDown()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerCount = event.pointerCount
                val historySize = event.historySize

                if (pointerCount == 1) {
                    for (h in 0 until historySize) {
                        gestureProcessor.onFingerMove(
                            event.getHistoricalX(0, h),
                            event.getHistoricalY(0, h),
                        )
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
                // pointerCount still includes the leaving pointer
                if (event.pointerCount == 2) {
                    val idx = event.actionIndex
                    gestureProcessor.onGestureEnd(event.getX(idx), event.getY(idx))
                }
            }

            MotionEvent.ACTION_UP -> {
                gestureProcessor.onGestureEnd(event.x, event.y)
            }

            MotionEvent.ACTION_CANCEL -> {
                Log.w(TAG, "gesture cancelled by system")
                gestureProcessor.onGestureCancel()
            }
        }
        return true
    }

    companion object {
        private const val TAG = "TouchpadView"
    }
}

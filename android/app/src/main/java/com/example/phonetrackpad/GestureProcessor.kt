package com.example.phonetrackpad

import kotlin.math.abs

/**
 * Turns raw finger positions into high-level gestures and packets.
 *
 * Called from TouchpadView's onTouchEvent for every real hardware touch
 * sample (up to 240/sec), not once per display frame. Two things that
 * matter specifically because of that high sample rate:
 *
 * - Fractional-pixel carry: at 240Hz each individual sample's movement
 *   is often well under a pixel. Naively rounding every sample to an
 *   int would silently drop most of a slow drag's motion. Instead we
 *   accumulate the remainder and only emit whole pixels once they add
 *   up - same idea as Bresenham's line algorithm.
 * - Every value is read fresh from SettingsStore.current on each call,
 *   so a slider drag changes feel on the very next sample, live.
 */
class GestureProcessor(private val onPacket: (Packet) -> Unit) {

    private enum class Mode { IDLE, ONE_FINGER, TWO_FINGER }
    private var mode = Mode.IDLE

    private data class Point(val x: Float, val y: Float, val t: Long)

    // one-finger state
    private var downPoint: Point? = null
    private var lastMove: Point? = null
    private var moved = false
    private var lastTapUpTime = 0L
    private var lastTapUpPoint: Point? = null

    // two-finger state
    private var lastTwoFingerY: Float? = null
    private var twoFingerDownAt = 0L
    private var twoFingerMoved = false

    // fractional-pixel carry (see class doc)
    private var carryX = 0f
    private var carryY = 0f

    // smoothing history - window size itself is user-controlled
    private val recentDx = ArrayDeque<Float>()
    private val recentDy = ArrayDeque<Float>()

    fun onFingerDown(x: Float, y: Float) {
        mode = Mode.ONE_FINGER
        val p = Point(x, y, System.currentTimeMillis())
        downPoint = p
        lastMove = p
        moved = false
        carryX = 0f; carryY = 0f
        recentDx.clear(); recentDy.clear()
    }

    fun onSecondFingerDown() {
        mode = Mode.TWO_FINGER
        lastTwoFingerY = null
        twoFingerDownAt = System.currentTimeMillis()
        twoFingerMoved = false
        // A second finger landing cancels whatever one-finger tap/drag was in progress.
        downPoint = null
        lastMove = null
        moved = false
    }

    fun onFingerMove(x: Float, y: Float) {
        if (mode != Mode.ONE_FINGER) return
        val prev = lastMove ?: return
        val now = System.currentTimeMillis()
        val settings = SettingsStore.current

        val rawDx = x - prev.x
        val rawDy = y - prev.y

        if (abs(x - (downPoint?.x ?: x)) > TOUCH_SLOP || abs(y - (downPoint?.y ?: y)) > TOUCH_SLOP) {
            moved = true
        }
        lastMove = Point(x, y, now)
        if (!moved) return // could still resolve to a tap on finger-up

        var dx = rawDx * settings.sensitivity
        var dy = rawDy * settings.sensitivity

        val (sdx, sdy) = smooth(dx, dy, settings.smoothing)
        dx = sdx; dy = sdy

        if (settings.accelerationEnabled) {
            dx = accelerate(dx, settings.accelerationThreshold, settings.accelerationMultiplier)
            dy = accelerate(dy, settings.accelerationThreshold, settings.accelerationMultiplier)
        }

        carryX += dx
        carryY += dy
        val outX = carryX.toInt()
        val outY = carryY.toInt()
        carryX -= outX
        carryY -= outY

        if (outX != 0 || outY != 0) {
            onPacket(Packet.Move(outX, outY))
        }
    }

    fun onTwoFingerSample(avgY: Float) {
        if (mode != Mode.TWO_FINGER) return
        val settings = SettingsStore.current
        lastTwoFingerY?.let { prev ->
            val delta = avgY - prev
            if (abs(delta) > 3f) twoFingerMoved = true
            var amount = (-delta / SCROLL_DIVISOR) * settings.scrollSensitivity
            if (settings.invertScroll) amount = -amount
            if (amount.toInt() != 0) onPacket(Packet.Scroll(amount.toInt()))
        }
        lastTwoFingerY = avgY
    }

    /** Called on ACTION_UP, or ACTION_POINTER_UP when we were in two-finger mode. */
    fun onGestureEnd(x: Float, y: Float) {
        when (mode) {
            Mode.ONE_FINGER -> resolveOneFingerTap(x, y)
            Mode.TWO_FINGER -> {
                val duration = System.currentTimeMillis() - twoFingerDownAt
                if (!twoFingerMoved && duration < 400L) onPacket(Packet.MiddleClick)
            }
            Mode.IDLE -> {}
        }
        reset()
    }

    fun onGestureCancel() {
        reset()
    }

    private fun resolveOneFingerTap(x: Float, y: Float) {
        val down = downPoint ?: return
        val now = System.currentTimeMillis()
        val duration = now - down.t

        if (!moved && duration < LONG_PRESS_MS) {
            val prevTap = lastTapUpPoint
            if (prevTap != null &&
                now - lastTapUpTime < DOUBLE_TAP_MS &&
                abs(x - prevTap.x) < TOUCH_SLOP * 2 &&
                abs(y - prevTap.y) < TOUCH_SLOP * 2
            ) {
                onPacket(Packet.DoubleClick)
                lastTapUpPoint = null // consumed - don't chain into a triple-tap
            } else {
                onPacket(Packet.Click)
                lastTapUpPoint = Point(x, y, now)
                lastTapUpTime = now
            }
        } else if (!moved && duration >= LONG_PRESS_MS) {
            onPacket(Packet.RightClick)
        }
    }

    private fun reset() {
        mode = Mode.IDLE
        downPoint = null
        lastMove = null
        moved = false
        lastTwoFingerY = null
    }

    private fun smooth(dx: Float, dy: Float, window: Int): Pair<Float, Float> {
        val w = window.coerceAtLeast(1)
        recentDx.addLast(dx); if (recentDx.size > w) recentDx.removeFirst()
        recentDy.addLast(dy); if (recentDy.size > w) recentDy.removeFirst()
        return recentDx.average().toFloat() to recentDy.average().toFloat()
    }

    private fun accelerate(delta: Float, threshold: Float, multiplier: Float): Float {
        return if (abs(delta) > threshold) delta * multiplier else delta
    }

    companion object {
        private const val TOUCH_SLOP = 4f      // px of wiggle allowed before it counts as a drag
        private const val LONG_PRESS_MS = 500L
        private const val DOUBLE_TAP_MS = 300L
        private const val SCROLL_DIVISOR = 3f
    }
}

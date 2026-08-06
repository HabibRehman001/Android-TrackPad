package com.example.phonetrackpad

import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns raw finger positions into high-level gestures and packets.
 *
 * Double-tap: the first tap does NOT fire a click immediately. A single
 * click is deferred until [DOUBLE_TAP_MS] elapses with no second tap.
 * If a second tap lands in time nearby, the pending click is cancelled
 * and one DoubleClick packet is sent instead — otherwise Linux apps see
 * "click then double-click" (three press events) which feels broken.
 */
class GestureProcessor(private val onPacket: (Packet) -> Unit) {

    private enum class Mode { IDLE, ONE_FINGER, TWO_FINGER }
    private var mode = Mode.IDLE

    private data class Point(val x: Float, val y: Float, val t: Long)

    private var downPoint: Point? = null
    private var lastMove: Point? = null
    private var moved = false
    private var lastTapUpTime = 0L
    private var lastTapUpPoint: Point? = null

    private var lastTwoFingerY: Float? = null
    private var twoFingerDownAt = 0L
    private var twoFingerMoved = false

    private var carryX = 0f
    private var carryY = 0f

    private val recentDx = ArrayDeque<Float>()
    private val recentDy = ArrayDeque<Float>()

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClick: Runnable? = null

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
        cancelPendingClick()
        lastTapUpPoint = null
        mode = Mode.TWO_FINGER
        lastTwoFingerY = null
        twoFingerDownAt = System.currentTimeMillis()
        twoFingerMoved = false
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
        if (!moved) return

        // Dragging cancels a waiting single-click from a previous tap
        cancelPendingClick()
        lastTapUpPoint = null

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
        cancelPendingClick()
        reset()
    }

    private fun resolveOneFingerTap(x: Float, y: Float) {
        val down = downPoint ?: return
        val now = System.currentTimeMillis()
        val duration = now - down.t

        if (!moved && duration < LONG_PRESS_MS) {
            val prevTap = lastTapUpPoint
            val isDouble = prevTap != null &&
                now - lastTapUpTime < DOUBLE_TAP_MS &&
                hypot(x - prevTap.x, y - prevTap.y) < DOUBLE_TAP_SLOP

            if (isDouble) {
                cancelPendingClick()
                onPacket(Packet.DoubleClick)
                lastTapUpPoint = null
                lastTapUpTime = 0L
            } else {
                // Defer single click so a quick second tap can become a double-click
                cancelPendingClick()
                lastTapUpPoint = Point(x, y, now)
                lastTapUpTime = now
                val click = Runnable {
                    pendingClick = null
                    lastTapUpPoint = null
                    onPacket(Packet.Click)
                }
                pendingClick = click
                handler.postDelayed(click, DOUBLE_TAP_MS)
            }
        } else if (!moved && duration >= LONG_PRESS_MS) {
            cancelPendingClick()
            lastTapUpPoint = null
            onPacket(Packet.RightClick)
        }
    }

    private fun cancelPendingClick() {
        pendingClick?.let { handler.removeCallbacks(it) }
        pendingClick = null
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
        private const val TOUCH_SLOP = 6f
        private const val DOUBLE_TAP_SLOP = 48f  // how far apart the two taps may land
        private const val LONG_PRESS_MS = 500L
        private const val DOUBLE_TAP_MS = 450L   // also the single-click defer delay
        private const val SCROLL_DIVISOR = 3f
    }
}

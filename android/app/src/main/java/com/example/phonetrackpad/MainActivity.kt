package com.example.phonetrackpad

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pure View hierarchy for the trackpad surface.
 *
 * Compose's AndroidView pointer interop can steal / cancel gestures after
 * ACTION_DOWN on some OEMs (Oppo/ColorOS, XOS), so the trackpad itself is
 * a raw View. Settings stay a simple View panel.
 */
class MainActivity : ComponentActivity() {

    private val socketManager = SocketManager()
    private val gestureProcessor = GestureProcessor { packet -> socketManager.send(packet) }
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var settingsPanel: View

    private val statusPoll = object : Runnable {
        override fun run() {
            if (::statusText.isInitialized) {
                val ok = socketManager.isConnected
                statusText.text = if (ok) "Connected" else "Connecting… (adb reverse + server)"
                statusText.setTextColor(if (ok) 0xFF8BC34A.toInt() else 0xFFFFB74D.toInt())
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF121212.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val touchpad = TouchpadView(this, gestureProcessor).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            isClickable = true
            isFocusable = true
            keepScreenOn = true
        }
        root.addView(touchpad)

        statusText = TextView(this).apply {
            text = "Connecting…"
            setTextColor(0xFFFFB74D.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )

        val settingsBtn = Button(this).apply {
            text = "Settings"
            setOnClickListener { settingsPanel.visibility = View.VISIBLE }
        }
        root.addView(
            settingsBtn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )

        settingsPanel = buildSettingsPanel().apply { visibility = View.GONE }
        root.addView(
            settingsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusText.updatePadding(top = bars.top + dp(8))
            settingsBtn.updatePadding(top = bars.top)
            (settingsBtn.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
            insets
        }

        setContentView(root)
        socketManager.start()
        uiHandler.post(statusPoll)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(statusPoll)
        socketManager.stop()
        super.onDestroy()
    }

    private fun buildSettingsPanel(): View {
        val dim = View(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setOnClickListener { settingsPanel.visibility = View.GONE }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(20), dp(16), dp(20), dp(20))
            elevation = dp(8).toFloat()
        }

        fun sectionTitle(label: String) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(12))
        }

        fun labeledSeek(
            label: String,
            minV: Int,
            maxV: Int,
            initial: Int,
            format: (Int) -> String,
            onChange: (Int) -> Unit,
        ): View {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = label
                setTextColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                text = format(initial)
                setTextColor(0xFFBDBDBD.toInt())
            }
            row.addView(name)
            row.addView(value)
            val seek = SeekBar(this).apply {
                max = maxV - minV
                progress = (initial - minV).coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p + minV
                        value.text = format(v)
                        if (fromUser) onChange(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            col.addView(row)
            col.addView(seek)
            return col
        }

        val s = SettingsStore.current
        card.addView(sectionTitle("Trackpad settings"))

        card.addView(
            labeledSeek("Sensitivity", 3, 30, (s.sensitivity * 10).toInt(), { "${it / 10f}x" }) { v ->
                SettingsStore.update { it.copy(sensitivity = v / 10f) }
            },
        )
        card.addView(
            labeledSeek("Smoothness", 1, 8, s.smoothing, { if (it <= 1) "Off" else "$it" }) { v ->
                SettingsStore.update { it.copy(smoothing = v) }
            },
        )

        val accelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        accelRow.addView(
            TextView(this).apply {
                text = "Acceleration"
                setTextColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val accelStrength = labeledSeek(
            "Acceleration strength",
            11,
            30,
            (s.accelerationMultiplier * 10).toInt(),
            { "${it / 10f}x" },
        ) { v -> SettingsStore.update { it.copy(accelerationMultiplier = v / 10f) } }
        accelStrength.visibility = if (s.accelerationEnabled) View.VISIBLE else View.GONE
        accelRow.addView(
            Switch(this).apply {
                isChecked = s.accelerationEnabled
                setOnCheckedChangeListener { _, on ->
                    SettingsStore.update { it.copy(accelerationEnabled = on) }
                    accelStrength.visibility = if (on) View.VISIBLE else View.GONE
                }
            },
        )
        card.addView(accelRow)
        card.addView(accelStrength)

        card.addView(
            labeledSeek("Scroll speed", 3, 30, (s.scrollSensitivity * 10).toInt(), { "${it / 10f}x" }) { v ->
                SettingsStore.update { it.copy(scrollSensitivity = v / 10f) }
            },
        )

        val invertRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        invertRow.addView(
            TextView(this).apply {
                text = "Invert scroll"
                setTextColor(0xFFEEEEEE.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        invertRow.addView(
            Switch(this).apply {
                isChecked = s.invertScroll
                setOnCheckedChangeListener { _, on ->
                    SettingsStore.update { it.copy(invertScroll = on) }
                }
            },
        )
        card.addView(invertRow)

        card.addView(
            Button(this).apply {
                text = "Close"
                setOnClickListener { settingsPanel.visibility = View.GONE }
            },
        )

        val scroll = ScrollView(this).apply {
            addView(card)
            setPadding(dp(24), dp(48), dp(24), dp(48))
            isClickable = true
            // Don't close when tapping the card area
            setOnClickListener { /* consume */ }
        }

        return FrameLayout(this).apply {
            addView(
                dim,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
            .toInt()
}

package com.example.phonetrackpad

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed live settings. GestureProcessor reads
 * [current] on every sample so slider changes apply immediately.
 */
object SettingsStore {
    @Volatile
    var current: TrackpadSettings = TrackpadSettings()
        private set

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("trackpad", Context.MODE_PRIVATE)
        current = TrackpadSettings(
            sensitivity = prefs.getFloat("sensitivity", 1.4f),
            smoothing = prefs.getInt("smoothing", 1),
            accelerationEnabled = prefs.getBoolean("accelerationEnabled", false),
            accelerationThreshold = prefs.getFloat("accelerationThreshold", 2.0f),
            accelerationMultiplier = prefs.getFloat("accelerationMultiplier", 1.5f),
            scrollSensitivity = prefs.getFloat("scrollSensitivity", 1.0f),
            invertScroll = prefs.getBoolean("invertScroll", false),
        )
    }

    fun update(transform: (TrackpadSettings) -> TrackpadSettings) {
        val next = transform(current)
        current = next
        prefs.edit()
            .putFloat("sensitivity", next.sensitivity)
            .putInt("smoothing", next.smoothing)
            .putBoolean("accelerationEnabled", next.accelerationEnabled)
            .putFloat("accelerationThreshold", next.accelerationThreshold)
            .putFloat("accelerationMultiplier", next.accelerationMultiplier)
            .putFloat("scrollSensitivity", next.scrollSensitivity)
            .putBoolean("invertScroll", next.invertScroll)
            .apply()
    }
}

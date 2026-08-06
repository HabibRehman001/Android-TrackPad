package com.example.phonetrackpad

/**
 * Tunable "feel" values for the trackpad. Live-read by GestureProcessor
 * on every touch sample via SettingsStore.current.
 */
data class TrackpadSettings(
    val sensitivity: Float = 1.4f,
    val smoothing: Int = 1,
    val accelerationEnabled: Boolean = false,
    val accelerationThreshold: Float = 2.0f,
    val accelerationMultiplier: Float = 1.5f,
    val scrollSensitivity: Float = 1.0f,
    val invertScroll: Boolean = false,
    /** Desktop IP. Use 127.0.0.1 with adb reverse, or your PC's Wi-Fi IP. */
    val desktopHost: String = "127.0.0.1",
)

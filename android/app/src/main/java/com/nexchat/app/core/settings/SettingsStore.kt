package com.nexchat.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class AppPreferences(
    val serverUrl: String,
    val themeMode: ThemeMode,
    val readReceipts: Boolean,
    val typingIndicators: Boolean,
    val sharePresence: Boolean,
    val notificationPreview: Boolean,
    val screenshotProtection: Boolean,
    val nearbyEnabled: Boolean,
    val relayEnabled: Boolean,
    val autoConnect: Boolean,
    val onboardingCompleted: Boolean,
)

class SettingsStore(context: Context, defaultServerUrl: String) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("nexchat_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load(defaultServerUrl))
    val state: StateFlow<AppPreferences> = _state.asStateFlow()

    private fun load(defaultUrl: String): AppPreferences = AppPreferences(
        // The server is fixed at build time. A previously saved address is
        // intentionally ignored so an APK always talks to its own backend.
        serverUrl = defaultUrl.trim().trimEnd('/'),
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name)!!)
        }.getOrDefault(ThemeMode.DARK),
        readReceipts = prefs.getBoolean(KEY_READ_RECEIPTS, true),
        typingIndicators = prefs.getBoolean(KEY_TYPING, true),
        sharePresence = prefs.getBoolean(KEY_PRESENCE, true),
        notificationPreview = prefs.getBoolean(KEY_NOTIFICATION_PREVIEW, false),
        screenshotProtection = prefs.getBoolean(KEY_SCREENSHOT, false),
        nearbyEnabled = prefs.getBoolean(KEY_NEARBY, false),
        relayEnabled = prefs.getBoolean(KEY_RELAY, false),
        autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true),
        onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING, false),
    )

    fun update(transform: (AppPreferences) -> AppPreferences) {
        val updated = transform(_state.value)
        prefs.edit()
            .putString(KEY_THEME, updated.themeMode.name)
            .putBoolean(KEY_READ_RECEIPTS, updated.readReceipts)
            .putBoolean(KEY_TYPING, updated.typingIndicators)
            .putBoolean(KEY_PRESENCE, updated.sharePresence)
            .putBoolean(KEY_NOTIFICATION_PREVIEW, updated.notificationPreview)
            .putBoolean(KEY_SCREENSHOT, updated.screenshotProtection)
            .putBoolean(KEY_NEARBY, updated.nearbyEnabled)
            .putBoolean(KEY_RELAY, updated.relayEnabled)
            .putBoolean(KEY_AUTO_CONNECT, updated.autoConnect)
            .putBoolean(KEY_ONBOARDING, updated.onboardingCompleted)
            .apply()
        _state.value = updated
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_READ_RECEIPTS = "read_receipts"
        const val KEY_TYPING = "typing_indicators"
        const val KEY_PRESENCE = "share_presence"
        const val KEY_NOTIFICATION_PREVIEW = "notification_preview"
        const val KEY_SCREENSHOT = "screenshot_protection"
        const val KEY_NEARBY = "nearby_enabled"
        const val KEY_RELAY = "relay_enabled"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_ONBOARDING = "onboarding_completed"
    }
}

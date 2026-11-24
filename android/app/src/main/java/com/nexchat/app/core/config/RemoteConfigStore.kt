package com.nexchat.app.core.config

import android.content.Context
import android.util.Log
import com.nexchat.app.BuildConfig
import com.nexchat.app.core.network.AnnouncementDto
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.IceServerDto
import com.nexchat.app.core.network.NexJson
import com.nexchat.app.core.security.LogRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

data class RemoteConfigState(
    val loaded: Boolean = false,
    val version: Int = 0,
    val callsEnabled: Boolean = true,
    // Fail closed on a fresh install with no cached config; cached installs
    // keep the value they already fetched (see CachedRemoteConfig below).
    val videoCallsEnabled: Boolean = false,
    val maxCallDurationSeconds: Int = 3600,
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String = "",
    val minAppVersion: String = "1.0.0",
    val announcement: AnnouncementDto? = null,
    val iceServers: List<IceServerDto> = emptyList(),
    val fetchedAt: Long = 0,
)

/**
 * Caches the public /v1/config response so remote flags still apply during a
 * cold start with no network. Startup defaults are fail-closed for video and
 * use the last successfully fetched values for other flags; the server can
 * narrow behaviour as soon as a refresh succeeds.
 */
class RemoteConfigStore(
    context: Context,
    private val api: ApiClient,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nexchat_remote_config", Context.MODE_PRIVATE)
    private var started = false

    private val _state = MutableStateFlow(loadCached())
    val state: StateFlow<RemoteConfigState> = _state.asStateFlow()

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            refresh()
            while (isActive) {
                delay(REFRESH_INTERVAL_MILLIS)
                refresh()
            }
        }
    }

    suspend fun refresh(): Boolean {
        val response = runCatching { api.serverConfig() }.getOrElse { error ->
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "config refresh failed: ${LogRedactor.redact(error.message)}")
            }
            return false
        }
        val remote = response.remote
        val previous = _state.value
        val next = RemoteConfigState(
            loaded = true,
            version = remote?.version ?: previous.version,
            callsEnabled = remote?.callsEnabled ?: previous.callsEnabled,
            videoCallsEnabled = remote?.videoCallsEnabled ?: previous.videoCallsEnabled,
            maxCallDurationSeconds = remote?.maxCallDurationSeconds
                ?: previous.maxCallDurationSeconds,
            maintenanceMode = remote?.maintenanceMode ?: previous.maintenanceMode,
            maintenanceMessage = remote?.maintenanceMessage ?: previous.maintenanceMessage,
            minAppVersion = remote?.minAppVersion ?: previous.minAppVersion,
            announcement = response.announcement,
            iceServers = response.iceServers.ifEmpty { previous.iceServers },
            fetchedAt = System.currentTimeMillis(),
        )
        _state.value = next
        persist(next)
        return true
    }

    fun callsEnabled(): Boolean = _state.value.callsEnabled

    fun videoCallsEnabled(): Boolean = _state.value.videoCallsEnabled

    fun maxCallDurationSeconds(): Int = _state.value.maxCallDurationSeconds.coerceIn(60, 14_400)

    fun maintenanceText(): String = _state.value.maintenanceMessage.trim().ifBlank {
        "NEXCHAT is in maintenance mode. Some features may be unavailable."
    }

    fun isUpdateRequired(): Boolean =
        _state.value.loaded && isVersionBelow(BuildConfig.VERSION_NAME, _state.value.minAppVersion)

    fun isAnnouncementDismissed(announcement: AnnouncementDto): Boolean =
        prefs.getString(KEY_DISMISSED_ANNOUNCEMENT, null) == announcementKey(announcement)

    fun dismissAnnouncement(announcement: AnnouncementDto) {
        prefs.edit()
            .putString(KEY_DISMISSED_ANNOUNCEMENT, announcementKey(announcement))
            .apply()
    }

    private fun loadCached(): RemoteConfigState {
        val raw = prefs.getString(KEY_CACHE, null) ?: return RemoteConfigState()
        val cached = runCatching {
            NexJson.decodeFromString<CachedRemoteConfig>(raw)
        }.getOrNull() ?: return RemoteConfigState()
        return RemoteConfigState(
            loaded = true,
            version = cached.version,
            callsEnabled = cached.callsEnabled,
            videoCallsEnabled = cached.videoCallsEnabled,
            maxCallDurationSeconds = cached.maxCallDurationSeconds,
            maintenanceMode = cached.maintenanceMode,
            maintenanceMessage = cached.maintenanceMessage,
            minAppVersion = cached.minAppVersion,
            announcement = cached.announcement,
            iceServers = cached.iceServers,
            fetchedAt = cached.fetchedAt,
        )
    }

    private fun persist(state: RemoteConfigState) {
        val cached = CachedRemoteConfig(
            version = state.version,
            callsEnabled = state.callsEnabled,
            videoCallsEnabled = state.videoCallsEnabled,
            maxCallDurationSeconds = state.maxCallDurationSeconds,
            maintenanceMode = state.maintenanceMode,
            maintenanceMessage = state.maintenanceMessage,
            minAppVersion = state.minAppVersion,
            announcement = state.announcement,
            iceServers = state.iceServers,
            fetchedAt = state.fetchedAt,
        )
        runCatching {
            prefs.edit().putString(KEY_CACHE, NexJson.encodeToString(cached)).apply()
        }.onFailure { error ->
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "config cache write failed: ${LogRedactor.redact(error.message)}")
            }
        }
    }

    private fun announcementKey(announcement: AnnouncementDto): String =
        "${announcement.title}\u0000${announcement.body}".hashCode().toString()

    private fun isVersionBelow(currentVersion: String, minimumVersion: String): Boolean {
        val minimum = versionParts(minimumVersion)
        if (minimum.isEmpty()) return false
        val current = versionParts(currentVersion)
        val length = maxOf(current.size, minimum.size)
        for (index in 0 until length) {
            val left = current.getOrElse(index) { 0 }
            val right = minimum.getOrElse(index) { 0 }
            if (left != right) return left < right
        }
        return false
    }

    private fun versionParts(value: String): List<Int> =
        value.trim()
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    @Serializable
    private data class CachedRemoteConfig(
        val version: Int = 0,
        val callsEnabled: Boolean = true,
        val videoCallsEnabled: Boolean = false,
        val maxCallDurationSeconds: Int = 3600,
        val maintenanceMode: Boolean = false,
        val maintenanceMessage: String = "",
        val minAppVersion: String = "1.0.0",
        val announcement: AnnouncementDto? = null,
        val iceServers: List<IceServerDto> = emptyList(),
        val fetchedAt: Long = 0,
    )

    companion object {
        private const val TAG = "NEXCHAT_CONFIG"
        private const val KEY_CACHE = "cache"
        private const val KEY_DISMISSED_ANNOUNCEMENT = "dismissed_announcement"
        private const val REFRESH_INTERVAL_MILLIS = 5L * 60L * 1000L
    }
}

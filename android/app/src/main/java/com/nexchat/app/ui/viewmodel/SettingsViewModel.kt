package com.nexchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexchat.app.core.nearby.NearbyRelayManager
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.DeviceSummary
import com.nexchat.app.core.settings.SettingsStore
import com.nexchat.app.core.settings.ThemeMode
import com.nexchat.app.data.AuthRepository
import com.nexchat.app.data.AuthState
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.net.ConnectionState
import com.nexchat.app.net.WebSocketManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val api: ApiClient,
) : ViewModel() {
    val preferences = settings.state

    val profile = auth.state
        .map { (it as? AuthState.LoggedIn)?.profile }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _devices = MutableStateFlow<List<DeviceSummary>>(emptyList())
    val devices: StateFlow<List<DeviceSummary>> = _devices.asStateFlow()

    private val _loadingDevices = MutableStateFlow(false)
    val loadingDevices: StateFlow<Boolean> = _loadingDevices.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun setTheme(mode: ThemeMode) = settings.update { it.copy(themeMode = mode) }

    fun setReadReceipts(enabled: Boolean) = settings.update { it.copy(readReceipts = enabled) }

    fun setTypingIndicators(enabled: Boolean) = settings.update { it.copy(typingIndicators = enabled) }

    fun setNotificationPreview(enabled: Boolean) =
        settings.update { it.copy(notificationPreview = enabled) }

    fun setScreenshotProtection(enabled: Boolean) =
        settings.update { it.copy(screenshotProtection = enabled) }

    fun setSharePresence(enabled: Boolean) {
        settings.update { it.copy(sharePresence = enabled) }
        viewModelScope.launch {
            runCatching { auth.updatePresence(enabled) }
                .onFailure {
                    settings.update { current -> current.copy(sharePresence = !enabled) }
                    _messages.tryEmit(it.message ?: "Could not update presence sharing")
                }
        }
    }

    fun updateDisplayName(name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) {
            _messages.tryEmit("Display name cannot be empty")
            return
        }
        _busy.value = true
        viewModelScope.launch {
            runCatching { auth.updateProfile(cleaned) }
                .onSuccess { _messages.tryEmit("Profile updated") }
                .onFailure { _messages.tryEmit(it.message ?: "Could not update your profile") }
            _busy.value = false
        }
    }

    fun updateAvatar(base64: String?) {
        _busy.value = true
        viewModelScope.launch {
            runCatching { auth.updateAvatar(base64) }
                .onSuccess { _messages.tryEmit("Photo updated") }
                .onFailure { _messages.tryEmit(it.message ?: "Could not update your photo") }
            _busy.value = false
        }
    }

    fun loadDevices() {
        if (_loadingDevices.value) return
        _loadingDevices.value = true
        viewModelScope.launch {
            runCatching { api.devices().devices }
                .onSuccess { _devices.value = it }
                .onFailure { _messages.tryEmit(it.message ?: "Could not load your devices") }
            _loadingDevices.value = false
        }
    }

    fun revokeDevice(deviceId: String) {
        viewModelScope.launch {
            runCatching { api.revokeDevice(deviceId) }
                .onSuccess {
                    _devices.value = _devices.value.filterNot { it.id == deviceId }
                    _messages.tryEmit("Device signed out")
                }
                .onFailure { _messages.tryEmit(it.message ?: "Could not revoke the device") }
        }
    }

    fun logout() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { auth.logout() }
                .onFailure { _messages.tryEmit(it.message ?: "Could not sign out") }
            _busy.value = false
        }
    }

    fun deleteAccount(password: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { auth.deleteAccount(password) }
                .onFailure { _messages.tryEmit(it.message ?: "Could not delete the account") }
            _busy.value = false
        }
    }
}

class DiscoverViewModel(
    private val api: ApiClient,
    auth: AuthRepository,
    websocket: WebSocketManager,
    settings: SettingsStore,
    private val chat: ChatRepository,
    private val nearby: NearbyRelayManager,
) : ViewModel() {
    val profile = auth.state
        .map { (it as? AuthState.LoggedIn)?.profile }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val connection: StateFlow<ConnectionState> = websocket.state

    /** Bluetooth nearby relay state; the UI never reads transport addresses. */
    val nearbyEnabled: StateFlow<Boolean> = nearby.enabled
    val nearbyPeers = nearby.peers

    val serverUrl: String = settings.state.value.serverUrl

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _reachable = MutableStateFlow<Boolean?>(null)
    val reachable: StateFlow<Boolean?> = _reachable.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun checkServer() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            runCatching { api.serverConfig() }
                .onSuccess { _reachable.value = true }
                .onFailure {
                    _reachable.value = false
                    _messages.tryEmit(it.message ?: "Could not reach the server")
                }
            _checking.value = false
        }
    }

    fun syncGroups() = viewModelScope.launch {
        runCatching { chat.syncGroups() }
            .onSuccess { _messages.tryEmit("Group list refreshed") }
            .onFailure { _messages.tryEmit(it.message ?: "Could not sync groups") }
    }

    fun nearbyPermissions(): Array<String> = nearby.requiredPermissions()

    fun enableNearby() = nearby.start()

    fun disableNearby() = nearby.stop()

    fun syncNearby() = nearby.syncNow()
}

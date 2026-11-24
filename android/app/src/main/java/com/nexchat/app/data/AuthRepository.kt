package com.nexchat.app.data

import androidx.room.withTransaction
import com.nexchat.app.core.crypto.SignalCryptoManager
import com.nexchat.app.core.crypto.MAX_SIGNAL_DEVICE_ID
import com.nexchat.app.core.crypto.MIN_SIGNAL_DEVICE_ID
import com.nexchat.app.core.db.NexDatabase
import com.nexchat.app.core.db.ProfileEntity
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.AuthResponse
import com.nexchat.app.core.network.DeviceKeysRequest
import com.nexchat.app.core.network.LoginRequest
import com.nexchat.app.core.network.PreKeyDto
import com.nexchat.app.core.network.RegisterRequest
import com.nexchat.app.core.security.SecureSessionStore
import com.nexchat.app.core.settings.SettingsStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val profile: ProfileEntity) : AuthState
}

class AuthRepository(
    private val db: NexDatabase,
    private val api: ApiClient,
    private val tokens: SecureSessionStore,
    private val crypto: SignalCryptoManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val profileDao = db.profileDao()
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    // One invalidation per signed-in session. Successful auth resets it.
    private val invalidationGuard = AtomicBoolean(false)

    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        val local = profileDao.get()
        if (!tokens.isLoggedIn) {
            if (local != null) wipeLocalAccountData()
            invalidationGuard.set(true)
            _state.value = AuthState.LoggedOut
            return@withContext
        }
        if (local != null) {
            _state.value = AuthState.LoggedIn(local)
        }
        try {
            val me = api.me()
            val identity = crypto.deviceIdentity()
            val signalDeviceId = syncSignalDeviceId(me.device.signalDeviceId)
            val profile = ProfileEntity(
                userId = me.user.id,
                nexId = me.user.nexId,
                username = me.user.username,
                displayName = me.user.displayName,
                avatar = me.user.avatar,
                deviceId = me.device.id,
                signalDeviceId = signalDeviceId,
                registrationId = identity.registrationId,
                createdAt = System.currentTimeMillis(),
            )
            profileDao.upsert(profile)
            invalidationGuard.set(false)
            _state.value = AuthState.LoggedIn(profile)
        } catch (error: Exception) {
            if (local == null) {
                tokens.clearTokens()
                invalidationGuard.set(true)
                _state.value = AuthState.LoggedOut
            }
        }
    }

    private suspend fun resetIfDifferentAccount(username: String) {
        val existing = profileDao.get() ?: return
        if (existing.username.lowercase() != username.trim().lowercase()) {
            wipeLocalAccountData()
        }
    }

    private suspend fun wipeLocalAccountData() = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.profileDao().delete()
            db.contactDao().deleteAll()
            db.messageDao().deleteAll()
            db.conversationDao().deleteAll()
            db.groupDao().deleteAllMembersEverywhere()
            db.groupDao().deleteAll()
            db.outboxDao().deleteAll()
            db.pendingIntentDao().deleteAll()
            db.relayDao().deleteAll()
            db.processedEnvelopeDao().deleteAll()
            db.callHistoryDao().deleteAll()
            db.attachmentDao().deleteAllChunks()
            db.attachmentDao().deleteAll()
            db.signalStateDao().deleteAll()
        }
        crypto.reset()
    }

    suspend fun register(
        username: String,
        password: String,
        displayName: String,
    ) = withContext(Dispatchers.IO) {
        if (profileDao.get() != null) wipeLocalAccountData()
        val local = crypto.deviceIdentity()
        val batch = crypto.newUploadBatch()
        val identity = crypto.identityKeyBase64()
        val device = DeviceKeysRequest(
            deviceId = local.deviceId,
            name = androidDeviceName(),
            registrationId = local.registrationId,
            signalDeviceId = local.signalDeviceId,
            identityKey = identity,
            signedPreKeyId = batch.signedPreKeyId,
            signedPreKeyPublic = batch.signedPreKeyPublic,
            signedPreKeySignature = batch.signedPreKeySignature,
            kyberPreKeyId = batch.kyberPreKeyId,
            kyberPreKeyPublic = batch.kyberPreKeyPublic,
            kyberPreKeySignature = batch.kyberPreKeySignature,
            oneTimePreKeys = batch.oneTimePreKeys.map { PreKeyDto(it.first, it.second) },
        )
        val response = api.register(
            RegisterRequest(
                username = username.trim().lowercase(),
                password = password,
                displayName = displayName.trim(),
                device = device,
            ),
        )
        completeAuth(response)
    }

    suspend fun login(
        username: String,
        password: String,
    ) = withContext(Dispatchers.IO) {
        resetIfDifferentAccount(username)
        val local = crypto.deviceIdentity()
        val batch = crypto.newUploadBatch()
        val identity = crypto.identityKeyBase64()
        val device = DeviceKeysRequest(
            deviceId = local.deviceId,
            name = androidDeviceName(),
            registrationId = local.registrationId,
            signalDeviceId = local.signalDeviceId,
            identityKey = identity,
            signedPreKeyId = batch.signedPreKeyId,
            signedPreKeyPublic = batch.signedPreKeyPublic,
            signedPreKeySignature = batch.signedPreKeySignature,
            kyberPreKeyId = batch.kyberPreKeyId,
            kyberPreKeyPublic = batch.kyberPreKeyPublic,
            kyberPreKeySignature = batch.kyberPreKeySignature,
            oneTimePreKeys = batch.oneTimePreKeys.map { PreKeyDto(it.first, it.second) },
        )
        val response = api.login(
            LoginRequest(
                username = username.trim().lowercase(),
                password = password,
                device = device,
            ),
        )
        completeAuth(response)
    }

    private suspend fun completeAuth(response: AuthResponse) {
        tokens.saveTokens(response.token, response.refreshToken)
        settings.update { it.copy(onboardingCompleted = true) }
        val identity = crypto.deviceIdentity()
        val signalDeviceId = syncSignalDeviceId(response.device.signalDeviceId)
        val profile = ProfileEntity(
            userId = response.user.id,
            nexId = response.user.nexId,
            username = response.user.username,
            displayName = response.user.displayName,
            avatar = response.user.avatar,
            deviceId = response.device.id,
            signalDeviceId = signalDeviceId,
            registrationId = identity.registrationId,
            createdAt = System.currentTimeMillis(),
        )
        profileDao.upsert(profile)
        invalidationGuard.set(false)
        _state.value = AuthState.LoggedIn(profile)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { api.logout(tokens.refreshToken) }
        invalidationGuard.set(true)
        tokens.clearTokens()
        wipeLocalAccountData()
        _state.value = AuthState.LoggedOut
    }

    suspend fun refreshProfile() = withContext(Dispatchers.IO) {
        val me = api.me()
        val current = profileDao.get() ?: return@withContext
        val signalDeviceId = syncSignalDeviceId(me.device.signalDeviceId)
        val profile = current.copy(
            displayName = me.user.displayName,
            avatar = me.user.avatar,
            username = me.user.username,
            deviceId = me.device.id,
            signalDeviceId = signalDeviceId,
        )
        profileDao.upsert(profile)
        _state.value = AuthState.LoggedIn(profile)
    }

    /**
     * The relay's device ID is authoritative. Legacy rows can contain a 32-bit
     * ID that libsignal cannot address, so move to a valid ID instead of
     * writing it back on the next login.
     */
    private suspend fun syncSignalDeviceId(serverId: Int): Int {
        val local = crypto.deviceIdentity()
        if (serverId in MIN_SIGNAL_DEVICE_ID..MAX_SIGNAL_DEVICE_ID) {
            if (local.signalDeviceId != serverId) {
                crypto.adoptSignalDeviceId(serverId)
            }
            return serverId
        }
        val repaired = runCatching { api.setSignalDeviceId(local.signalDeviceId) }.getOrNull()
        if (repaired != null) {
            crypto.adoptSignalDeviceId(repaired.signalDeviceId)
            return repaired.signalDeviceId
        }
        return local.signalDeviceId
    }

    suspend fun updateProfile(displayName: String) = withContext(Dispatchers.IO) {
        val response = api.updateProfile(displayName, null)
        val current = profileDao.get() ?: return@withContext
        val profile = current.copy(
            displayName = response.user.displayName,
            avatar = response.user.avatar,
        )
        profileDao.upsert(profile)
        _state.value = AuthState.LoggedIn(profile)
    }

    suspend fun updateAvatar(avatarBase64: String?) = withContext(Dispatchers.IO) {
        val response = api.updateProfile(null, avatarBase64)
        val current = profileDao.get() ?: return@withContext
        val profile = current.copy(
            displayName = response.user.displayName,
            avatar = response.user.avatar,
        )
        profileDao.upsert(profile)
        _state.value = AuthState.LoggedIn(profile)
    }

    suspend fun updatePresence(share: Boolean) = withContext(Dispatchers.IO) {
        api.updatePrivacy(share)
        settings.update { it.copy(sharePresence = share) }
    }

    suspend fun deleteAccount(password: String) = withContext(Dispatchers.IO) {
        api.deleteAccount(password)
        invalidationGuard.set(true)
        tokens.clearTokens()
        wipeLocalAccountData()
        _state.value = AuthState.LoggedOut
    }

    /**
     * Single entry point for every path that decides a session can no longer
     * be used (HTTP revocation, WebSocket close, missing token, explicit sign
     * out). Concurrent callers collapse into one wipe.
     */
    fun invalidateSession() {
        if (!invalidationGuard.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            tokens.clearTokens()
            wipeLocalAccountData()
            _state.value = AuthState.LoggedOut
        }
    }

    /** Kept for source compatibility with older call sites. */
    fun sessionExpired() = invalidateSession()

    private fun androidDeviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
        val model = android.os.Build.MODEL.orEmpty()
        val name = "$manufacturer $model".trim()
        return if (name.isBlank()) "Android device" else name.take(64)
    }
}

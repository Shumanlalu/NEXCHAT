package com.nexchat.app.core.network

import com.nexchat.app.BuildConfig
import com.nexchat.app.core.security.SecureSessionStore
import com.nexchat.app.core.settings.SettingsStore
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

open class ApiException(
    val statusCode: Int,
    val code: String,
    message: String,
) : IOException(message)

class SessionExpiredException : ApiException(401, "SESSION_EXPIRED", "Your session has expired. Sign in again.")

val NexJson = Json {
    ignoreUnknownKeys = true
    // SecureContent.timestamp must travel with every sealed payload. With
    // encodeDefaults = false the timestamp default would be omitted and the
    // receiver would substitute its own clock, destroying original ordering
    // for messages delivered from the durable queue.
    encodeDefaults = true
    explicitNulls = false
    isLenient = false
}

class ApiClient(
    private val settings: SettingsStore,
    private val tokens: SecureSessionStore,
) {
    private val json = NexJson
    private val refreshMutex = Mutex()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var sessionInvalidationHandler: (() -> Unit)? = null
    private val revocationGuard = AtomicBoolean(false)

    /** Wired once from AppContainer after the auth repository exists. */
    fun setSessionInvalidationHandler(handler: () -> Unit) {
        sessionInvalidationHandler = handler
    }

    /** A fresh sign-in makes the next revocation dispatchable again. */
    fun resetSessionRevocation() {
        revocationGuard.set(false)
    }

    /** Invokes the central invalidation path at most once per session. */
    fun dispatchSessionRevocation() {
        if (revocationGuard.compareAndSet(false, true)) {
            sessionInvalidationHandler?.invoke()
        }
    }

    private fun errorCode(body: String): String? =
        runCatching { json.decodeFromString<ApiErrorBody>(body).error.code }.getOrNull()

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun serverUrl(): String {
        val raw = settings.state.value.serverUrl.trim().trimEnd('/')
        if (raw.isBlank()) {
            throw ApiException(0, "NO_SERVER", "This build has no server configured")
        }
        val validScheme = raw.startsWith("https://") ||
            (BuildConfig.ALLOW_CLEARTEXT && (raw.startsWith("http://10.0.2.2") || raw.startsWith("http://localhost") || raw.startsWith("http://127.0.0.1")))
        if (!validScheme) {
            throw ApiException(0, "INSECURE_SERVER", "The server URL must use HTTPS")
        }
        return raw
    }

    fun websocketUrl(): String {
        val base = serverUrl()
        return when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}/v1/ws"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}/v1/ws"
            else -> throw ApiException(0, "INVALID_SERVER", "Invalid server URL")
        }
    }

    suspend fun register(request: RegisterRequest): AuthResponse =
        decode(perform("POST", "/v1/auth/register", json.encodeToString(request), authenticated = false))

    suspend fun login(request: LoginRequest): AuthResponse =
        decode(perform("POST", "/v1/auth/login", json.encodeToString(request), authenticated = false))

    suspend fun refresh(refreshToken: String): RefreshResponse =
        decode(perform("POST", "/v1/auth/refresh", json.encodeToString(RefreshRequest(refreshToken)), authenticated = false))

    suspend fun refreshSession(): String = refreshMutex.withLock {
        val refreshToken = tokens.refreshToken
        if (refreshToken.isNullOrBlank()) {
            tokens.clearTokens()
            dispatchSessionRevocation()
            throw SessionExpiredException()
        }
        val response = try {
            refresh(refreshToken)
        } catch (error: Exception) {
            tokens.clearTokens()
            dispatchSessionRevocation()
            throw error
        }
        tokens.saveTokens(response.token, response.refreshToken)
        response.token
    }

    fun currentAccessToken(): String? = tokens.accessToken

    suspend fun logout(refreshToken: String?) = perform(
        "POST",
        "/v1/auth/logout",
        if (refreshToken == null) "{}" else json.encodeToString(RefreshRequest(refreshToken)),
        authenticated = true,
    )

    suspend fun me(): MeResponse = decode(authenticated("GET", "/v1/me"))

    suspend fun updateProfile(displayName: String?, avatarBase64: String?): ProfileResponse {
        val body = UpdateProfileRequest(displayName = displayName, avatar = avatarBase64)
        return decode(authenticated("PATCH", "/v1/me", json.encodeToString(body)))
    }

    suspend fun updatePrivacy(sharePresence: Boolean): PrivacyResponse =
        decode(
            authenticated(
                "PATCH",
                "/v1/me/privacy",
                json.encodeToString(PrivacyRequest(sharePresence)),
            ),
        )

    suspend fun searchUsers(query: String): UsersResponse =
        decode(authenticated("GET", "/v1/users/search?q=${encodeQuery(query)}"))

    suspend fun getUser(nexId: String): PublicUserResponse =
        decode(authenticated("GET", "/v1/users/${encodePath(nexId)}"))

    suspend fun presence(nexId: String): PresenceResponse =
        decode(authenticated("GET", "/v1/presence/${encodePath(nexId)}"))

    suspend fun getKeyDevices(nexId: String): KeyDevicesResponse =
        decode(authenticated("GET", "/v1/keys/${encodePath(nexId)}/devices"))

    suspend fun getKeyBundles(
        nexId: String,
        signalDeviceIds: Set<Int>? = null,
    ): KeyBundleResponse {
        val filter = signalDeviceIds
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?.let { "?devices=${encodeQuery(it)}" }
            .orEmpty()
        return decode(authenticated("GET", "/v1/keys/${encodePath(nexId)}$filter"))
    }

    suspend fun rotateKeys(request: RotateKeysRequest) {
        authenticated("POST", "/v1/devices/keys", json.encodeToString(request))
    }

    suspend fun preKeyCount(): PreKeyCountResponse =
        decode(authenticated("GET", "/v1/devices/keys/count"))

    suspend fun setSignalDeviceId(signalDeviceId: Int? = null): SignalDeviceIdResponse =
        decode(
            authenticated(
                "POST",
                "/v1/devices/signal-id",
                json.encodeToString(SignalDeviceIdRequest(signalDeviceId)),
            ),
        )

    suspend fun devices(): DevicesResponse = decode(authenticated("GET", "/v1/devices"))

    suspend fun revokeDevice(deviceId: String) {
        authenticated("DELETE", "/v1/devices/${encodePath(deviceId)}")
    }

    suspend fun pendingEnvelopes(): PendingResponse =
        decode(authenticated("GET", "/v1/messages/pending"))

    /**
     * Long-poll variant of [pendingEnvelopes]. The relay holds the request for
     * up to [timeoutMs] and returns the moment an envelope exists for this
     * device. This keeps call signaling effectively instant even when the
     * WebSocket push landed on a different serverless instance.
     */
    suspend fun waitForEnvelopes(timeoutMs: Int = 20_000): PendingResponse =
        decode(
            authenticated(
                "GET",
                "/v1/messages/wait?timeoutMs=${timeoutMs.coerceIn(1_000, 25_000)}",
            ),
        )

    suspend fun sendEnvelopes(messages: List<OutgoingEnvelope>): SendMessagesResponse =
        decode(
            authenticated(
                "POST",
                "/v1/messages",
                json.encodeToString(SendMessagesRequest(messages)),
            ),
        )

    suspend fun ackEnvelopes(ids: List<String>): AckResponse =
        decode(
            authenticated(
                "POST",
                "/v1/messages/ack",
                json.encodeToString(AckRequest(ids)),
            ),
        )

    suspend fun deliveryReceipts(): DeliveryReceiptsResponse =
        decode(authenticated("GET", "/v1/messages/receipts"))

    suspend fun ackDeliveryReceipts(ids: List<String>): AckResponse =
        decode(
            authenticated(
                "POST",
                "/v1/messages/receipts/ack",
                json.encodeToString(ReceiptAckRequest(ids)),
            ),
        )

    suspend fun createGroup(memberNexIds: List<String>): GroupResponse =
        decode(
            authenticated(
                "POST",
                "/v1/groups",
                json.encodeToString(CreateGroupRequest(memberNexIds)),
            ),
        )

    suspend fun getGroups(): GroupsResponse = decode(authenticated("GET", "/v1/groups"))

    suspend fun getGroup(groupId: String): GroupResponse =
        decode(authenticated("GET", "/v1/groups/${encodePath(groupId)}"))

    suspend fun addGroupMembers(groupId: String, memberNexIds: List<String>): GroupResponse =
        decode(
            authenticated(
                "POST",
                "/v1/groups/${encodePath(groupId)}/members",
                json.encodeToString(AddMembersRequest(memberNexIds)),
            ),
        )

    suspend fun removeGroupMember(groupId: String, userId: String): GroupResponse =
        decode(authenticated("DELETE", "/v1/groups/${encodePath(groupId)}/members/${encodePath(userId)}"))

    suspend fun leaveGroup(groupId: String) {
        authenticated("POST", "/v1/groups/${encodePath(groupId)}/leave", "{}")
    }

    suspend fun deleteGroup(groupId: String) {
        authenticated("DELETE", "/v1/groups/${encodePath(groupId)}")
    }

    suspend fun checkUsername(username: String): CheckUsernameResponse =
        decode(
            perform(
                "POST",
                "/v1/auth/check-username",
                json.encodeToString(CheckUsernameRequest(username)),
                authenticated = false,
            ),
        )

    suspend fun serverConfig(): ServerConfigResponse = decode(perform("GET", "/v1/config", null, false))

    suspend fun analyticsHeartbeat(): HeartbeatResponse =
        decode(
            authenticated(
                "POST",
                "/v1/analytics/heartbeat",
                json.encodeToString(HeartbeatRequest(appVersion = BuildConfig.VERSION_NAME)),
            ),
        )

    suspend fun analyticsEvents(events: List<AnalyticsEventDto>) {
        authenticated(
            "POST",
            "/v1/analytics/events",
            json.encodeToString(AnalyticsEventsRequest(events)),
        )
    }

    suspend fun analyticsCrash(request: CrashReportRequest) {
        authenticated("POST", "/v1/analytics/crashes", json.encodeToString(request))
    }

    suspend fun analyticsCall(request: CallReportRequest) {
        authenticated("POST", "/v1/analytics/calls", json.encodeToString(request))
    }

    suspend fun deleteAccount(password: String) {
        authenticated("DELETE", "/v1/account", json.encodeToString(mapOf("password" to password)))
    }

    suspend fun report(targetNexId: String, reason: String) {
        authenticated(
            "POST",
            "/v1/reports",
            json.encodeToString(mapOf("targetNexId" to targetNexId, "reason" to reason)),
        )
    }

    private suspend fun authenticated(
        method: String,
        path: String,
        body: String? = null,
    ): String {
        val accessBefore = tokens.accessToken
        var response = perform(method, path, body, authenticated = true, accessOverride = accessBefore)
        // Inspect the relay's first error before spending the refresh token.
        // Policy denials and bad credentials are not session revocations.
        if (response.first == 401 || response.first == 403) {
            val code = errorCode(response.second)
            if (SessionRevocation.isRevocation(response.first, code)) {
                dispatchSessionRevocation()
                throw SessionExpiredException()
            }
        }
        if (response.first == 401) {
            refreshTokensIfNeeded(accessBefore)
            response = perform(method, path, body, authenticated = true)
        }
        return requireSuccess(response)
    }

    private suspend fun perform(
        method: String,
        path: String,
        body: String?,
        authenticated: Boolean,
        accessOverride: String? = null,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val url = serverUrl() + path
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "NEXCHAT-Android/${BuildConfig.VERSION_NAME}")
        val access = accessOverride ?: tokens.accessToken
        if (authenticated && !access.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $access")
        }
        val requestBody = when (method) {
            "GET" -> null
            "DELETE" -> body?.toRequestBody(mediaType)
            else -> (body ?: "{}").toRequestBody(mediaType)
        }
        builder.method(method, requestBody)
        httpClient.newCall(builder.build()).execute().use { response ->
            response.code to (response.body?.string() ?: "")
        }
    }

    private suspend fun refreshTokensIfNeeded(accessBefore: String?): Unit = refreshMutex.withLock {
        if (tokens.accessToken != null && tokens.accessToken != accessBefore) {
            return@withLock
        }
        val refreshToken = tokens.refreshToken
        if (refreshToken.isNullOrBlank()) {
            tokens.clearTokens()
            dispatchSessionRevocation()
            throw SessionExpiredException()
        }
        val (status, body) = perform(
            "POST",
            "/v1/auth/refresh",
            json.encodeToString(RefreshRequest(refreshToken)),
            authenticated = false,
        )
        if (status !in 200..299) {
            tokens.clearTokens()
            dispatchSessionRevocation()
            throw SessionExpiredException()
        }
        val response = try {
            decode<RefreshResponse>(status to body)
        } catch (error: Exception) {
            tokens.clearTokens()
            dispatchSessionRevocation()
            throw SessionExpiredException()
        }
        tokens.saveTokens(response.token, response.refreshToken)
    }

    private fun requireSuccess(response: Pair<Int, String>): String {
        if (response.first in 200..299) return response.second.ifBlank { "{}" }
        val parsed = runCatching { json.decodeFromString<ApiErrorBody>(response.second) }.getOrNull()
        if (SessionRevocation.isRevocation(response.first, parsed?.error?.code)) {
            dispatchSessionRevocation()
            throw SessionExpiredException()
        }
        if (response.first == 401) {
            throw SessionExpiredException()
        }
        throw ApiException(
            response.first,
            parsed?.error?.code ?: "HTTP_${response.first}",
            parsed?.error?.message ?: "The server rejected the request (${response.first})",
        )
    }

    private inline fun <reified T> decode(raw: Pair<Int, String>): T =
        json.decodeFromString(requireSuccess(raw))

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

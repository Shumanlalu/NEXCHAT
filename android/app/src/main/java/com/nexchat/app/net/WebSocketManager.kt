package com.nexchat.app.net

import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.ApiException
import com.nexchat.app.core.network.EnvelopeDto
import com.nexchat.app.core.network.NexJson
import com.nexchat.app.core.network.SessionExpiredException
import com.nexchat.app.data.AuthRepository
import com.nexchat.app.data.ChatRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Serializable
private data class WsEvent(
    val type: String,
    val envelope: EnvelopeDto? = null,
    val clientId: String? = null,
    val envelopeId: String? = null,
    val recipientDeviceId: String? = null,
    val deliveredAt: String? = null,
    val deviceId: String? = null,
)

enum class ConnectionState { STOPPED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Authenticated WSS client. The socket carries only opaque Signal ciphertext and
 * delivery metadata. Reconnect uses exponential backoff and refreshes the access
 * token once when the server reports an expired session.
 */
class WebSocketManager(
    private val api: ApiClient,
    private val chat: ChatRepository,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
) {
    private val running = AtomicBoolean(false)
    private val _state = MutableStateFlow(ConnectionState.STOPPED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    @Volatile
    private var socket: WebSocket? = null
    private var loop: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive && running.get()) {
                _state.value = if (backoff > 1_000L) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
                val closed = connectOnce()
                if (!running.get()) break
                closed.await()
                _state.value = ConnectionState.RECONNECTING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
            _state.value = ConnectionState.STOPPED
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        running.set(false)
        socket?.close(1000, "client stopped")
        socket = null
        loop?.cancel()
        loop = null
        _state.value = ConnectionState.STOPPED
    }

    private fun connectOnce(): CompletableDeferred<Unit> {
        val closed = CompletableDeferred<Unit>()
        val token = api.currentAccessToken()
        if (token.isNullOrBlank()) {
            closed.complete(Unit)
            auth.sessionExpired()
            return closed
        }
        val request = try {
            Request.Builder()
                .url(api.websocketUrl())
                .header("Authorization", "Bearer $token")
                .build()
        } catch (error: Exception) {
            closed.complete(Unit)
            return closed
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                _state.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { NexJson.decodeFromString<WsEvent>(text) }.getOrNull() ?: return
                when (event.type) {
                    "ready" -> scope.launch { runCatching { chat.onConnected() } }
                    "message" -> event.envelope?.let { envelope ->
                        scope.launch {
                            runCatching { chat.receiveEnvelope(envelope, acknowledge = true) }
                        }
                    }
                    "delivered" -> event.clientId?.let { clientId ->
                        scope.launch { chat.markDelivered(clientId) }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (code == 4401 || code == 4403) {
                    scope.launch {
                        val refreshed = runCatching { api.refreshSession() }.isSuccess
                        if (!refreshed) auth.sessionExpired()
                    }
                }
                if (!closed.isCompleted) closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                _state.value = ConnectionState.RECONNECTING
                if (!closed.isCompleted) closed.complete(Unit)
            }
        }
        return try {
            api.httpClient.newWebSocket(request, listener)
            closed
        } catch (error: Exception) {
            closed.complete(Unit)
            closed
        }
    }
}

package com.nexchat.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
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
    context: Context,
) {
    private val running = AtomicBoolean(false)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(ConnectionState.STOPPED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    @Volatile
    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var poller: Job? = null
    private var callPoller: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastNetworkRestart = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // The relay may serve sockets from more than one instance. A long poll
        // closes the gap: the server holds the request open and answers the
        // instant ciphertext exists, so a call offer never waits for a fixed
        // five-second poll tick. The WebSocket remains the primary path.
        poller = scope.launch {
            var failures = 0
            while (isActive && running.get()) {
                val result = runCatching { chat.waitForPending(LONG_POLL_TIMEOUT_MILLIS) }
                if (result.isSuccess) {
                    failures = 0
                    runCatching { chat.fetchReceipts() }
                    runCatching { chat.flushOutbox() }
                    runCatching { chat.flushCallSignals() }
                    continue
                }
                val error = result.exceptionOrNull()
                if (error is ApiException && error.statusCode == 404) {
                    // Older relay without the long-poll route: keep working,
                    // just at the previous cadence.
                    runCatching { chat.pollPending() }
                    delay(FALLBACK_POLL_INTERVAL_MILLIS)
                    continue
                }
                failures = (failures + 1).coerceAtMost(2)
                // A cold serverless instance can fail one long poll. Waiting
                // 3-5 seconds here is what made incoming calls appear minutes
                // late; never back off this path beyond two seconds.
                delay(500L * failures)
            }
        }
        // SDP/ICE retries are useless after the ring/connect window, so flush
        // this queue on a short fixed cadence while a session is signed in.
        callPoller = scope.launch {
            while (isActive && running.get()) {
                runCatching { chat.flushCallSignals() }
                delay(CALL_SIGNAL_FLUSH_INTERVAL_MILLIS)
            }
        }
        registerNetworkCallback()
        startConnectionLoop()
    }

    private fun startConnectionLoop() {
        loop?.cancel()
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive && running.get()) {
                _state.value =
                    if (backoff > 1_000L) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
                val opened = AtomicBoolean(false)
                val closed = connectOnce { opened.set(true) }
                if (!running.get()) break
                closed.await()
                if (!isActive) break
                // A socket that actually opened proves the network path works.
                // Start the next retry from the short delay instead of keeping
                // the large backoff accumulated during an earlier outage.
                if (opened.get()) backoff = 1_000L
                _state.value = ConnectionState.RECONNECTING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
            if (running.get()) _state.value = ConnectionState.STOPPED
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        running.set(false)
        unregisterNetworkCallback()
        socket?.close(1000, "client stopped")
        socket = null
        loop?.cancel()
        loop = null
        poller?.cancel()
        poller = null
        callPoller?.cancel()
        callPoller = null
        _state.value = ConnectionState.STOPPED
    }

    /**
     * A Wi-Fi/cellular handoff leaves the old socket half-open for a long time.
     * Reconnect as soon as Android reports a usable network instead of waiting
     * for TCP timeouts or the 20-second polling fallback.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null || connectivity == null) return
        lastNetworkRestart = SystemClock.elapsedRealtime()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reconnectNow()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    reconnectNow()
                }
            }

            override fun onLost(network: Network) = reconnectNow()
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity?.registerNetworkCallback(request, callback) }
            .onFailure { networkCallback = null }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun reconnectNow() {
        if (!running.get()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            // Network callbacks can arrive in bursts and the initial registration
            // immediately reports the current network.
            if (now - lastNetworkRestart < 2_000L) return
            lastNetworkRestart = now
        }
        socket?.cancel()
        socket = null
        startConnectionLoop()
    }

    private fun connectOnce(onOpened: () -> Unit): CompletableDeferred<Unit> {
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
                onOpened()
                // Drain ciphertext and intents that accumulated while offline
                // immediately instead of waiting for the next poll tick.
                scope.launch { runCatching { chat.flushOutbox() } }
                scope.launch { runCatching { chat.flushCallSignals() } }
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
                        scope.launch {
                            val at = event.deliveredAt?.let {
                                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
                            } ?: System.currentTimeMillis()
                            chat.markDelivered(clientId, at)
                        }
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

    private companion object {
        const val LONG_POLL_TIMEOUT_MILLIS = 20_000
        const val CALL_SIGNAL_FLUSH_INTERVAL_MILLIS = 1_000L
        const val FALLBACK_POLL_INTERVAL_MILLIS = 3_000L
    }
}

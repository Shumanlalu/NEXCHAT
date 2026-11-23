package com.nexchat.app.core.telemetry

import android.content.Context
import android.os.Process
import android.util.Log
import com.nexchat.app.BuildConfig
import com.nexchat.app.core.network.AnalyticsEventDto
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.CallReportRequest
import com.nexchat.app.core.network.CrashReportRequest
import com.nexchat.app.core.network.NexJson
import com.nexchat.app.data.AuthRepository
import com.nexchat.app.data.AuthState
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Best-effort operational telemetry. Nothing here carries message text, keys,
 * call media, tokens or passwords. Failures are swallowed so analytics can
 * never break messaging, calls or sign-in.
 */
class Telemetry(
    context: Context,
    private val api: ApiClient,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nexchat_telemetry", Context.MODE_PRIVATE)
    private val queueMutex = Mutex()
    private val flushMutex = Mutex()

    @Volatile
    private var started = false

    @Volatile
    private var lastHeartbeatAt = 0L

    fun start() {
        if (started) return
        started = true
        scope.launch {
            auth.state.collectLatest { state ->
                if (state is AuthState.LoggedIn) {
                    event("session_start")
                    flush()
                }
            }
        }
        scope.launch {
            while (isActive) {
                if (auth.state.value is AuthState.LoggedIn &&
                    System.currentTimeMillis() - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MILLIS
                ) {
                    if (runCatching { api.analyticsHeartbeat() }.isSuccess) {
                        lastHeartbeatAt = System.currentTimeMillis()
                    }
                    flushQueue()
                }
                delay(HEARTBEAT_CHECK_MILLIS)
            }
        }
        event("app_open")
    }

    fun event(type: String, metadata: Map<String, String>? = null) {
        scope.launch(Dispatchers.IO) {
            queueMutex.withLock {
                val queue = readQueue()
                val next = (queue.events + AnalyticsEventDto(
                    type = type,
                    appVersion = BuildConfig.VERSION_NAME,
                    platform = "android",
                    metadata = metadata,
                )).takeLast(MAX_QUEUED_EVENTS)
                writeQueue(queue.copy(events = next))
            }
            if (auth.state.value is AuthState.LoggedIn) flushQueue()
        }
    }

    fun reportCall(
        callId: String,
        role: String,
        peerNexId: String,
        status: String,
        startedAt: Long,
        mode: String = "audio",
        connectedAt: Long? = null,
        endedAt: Long? = null,
        durationSeconds: Int? = null,
        failureReason: String? = null,
    ) {
        if (auth.state.value !is AuthState.LoggedIn) return
        val request = CallReportRequest(
            callId = callId,
            role = role,
            peerNexId = peerNexId,
            mode = if (mode == "video") "video" else "audio",
            status = status,
            startedAt = iso(startedAt),
            connectedAt = iso(connectedAt),
            endedAt = iso(endedAt),
            durationSeconds = durationSeconds,
            failureReason = failureReason,
            appVersion = BuildConfig.VERSION_NAME,
        )
        scope.launch(Dispatchers.IO) {
            runCatching { api.analyticsCall(request) }
                .onFailure { Log.w(TAG, "call telemetry failed: ${it.message}") }
        }
    }

    fun flush() {
        scope.launch(Dispatchers.IO) { flushQueue() }
    }

    private suspend fun flushQueue() = withContext(Dispatchers.IO) {
        if (auth.state.value !is AuthState.LoggedIn) return@withContext
        flushMutex.withLock {
            var queue = queueMutex.withLock { readQueue() }
            var failed = false

            while (!failed && queue.events.isNotEmpty()) {
                val batch = queue.events.take(MAX_EVENTS_PER_REQUEST)
                val result = runCatching { api.analyticsEvents(batch) }
                if (result.isFailure) {
                    Log.w(TAG, "event batch failed: ${result.exceptionOrNull()?.message}")
                    failed = true
                    break
                }
                queue = queueMutex.withLock {
                    val latest = readQueue()
                    val updated = latest.copy(events = latest.events.drop(batch.size))
                    writeQueue(updated)
                    updated
                }
            }

            while (!failed && queue.crashes.isNotEmpty()) {
                val crash = queue.crashes.first()
                val result = runCatching {
                    api.analyticsCrash(
                        CrashReportRequest(
                            message = crash.message,
                            stack = crash.stack,
                            appVersion = BuildConfig.VERSION_NAME,
                            platform = "android",
                            context = crash.context,
                        ),
                    )
                }
                if (result.isFailure) {
                    Log.w(TAG, "crash report failed: ${result.exceptionOrNull()?.message}")
                    break
                }
                queue = queueMutex.withLock {
                    val latest = readQueue()
                    val updated = latest.copy(crashes = latest.crashes.drop(1))
                    writeQueue(updated)
                    updated
                }
            }
        }
    }

    private fun readQueue(): TelemetryQueue {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return TelemetryQueue()
        return runCatching { NexJson.decodeFromString<TelemetryQueue>(raw) }
            .getOrElse { TelemetryQueue() }
    }

    private fun writeQueue(queue: TelemetryQueue) {
        runCatching {
            prefs.edit().putString(KEY_QUEUE, NexJson.encodeToString(queue)).apply()
        }.onFailure { Log.w(TAG, "telemetry queue write failed: ${it.message}") }
    }

    private fun iso(epochMillis: Long?): String? =
        epochMillis?.let { Instant.ofEpochMilli(it).toString() }

    @Serializable
    private data class TelemetryQueue(
        val events: List<AnalyticsEventDto> = emptyList(),
        val crashes: List<CrashEntry> = emptyList(),
    )

    @Serializable
    private data class CrashEntry(
        val message: String,
        val stack: String? = null,
        val context: Map<String, String>? = null,
        val at: Long = 0,
    )

    companion object {
        private const val TAG = "NEXCHAT_TELEMETRY"
        private const val PREFS = "nexchat_telemetry"
        private const val KEY_QUEUE = "queue_v1"
        private const val MAX_QUEUED_EVENTS = 200
        private const val MAX_EVENTS_PER_REQUEST = 25
        private const val HEARTBEAT_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
        private const val HEARTBEAT_CHECK_MILLIS = 15L * 60L * 1000L

        @Volatile
        private var crashHandlerInstalled = false

        /**
         * Saves the crash synchronously and then delegates to the platform
         * handler. The next signed-in launch uploads it, so reporting does not
         * depend on the dying process completing an HTTP request.
         */
        fun installCrashHandler(context: Context) {
            if (crashHandlerInstalled) return
            synchronized(this) {
                if (crashHandlerInstalled) return
                crashHandlerInstalled = true
                val appContext = context.applicationContext
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    runCatching { persistCrash(appContext, thread, error) }
                        .onFailure { Log.w(TAG, "crash persistence failed: ${it.message}") }
                    if (previous != null) {
                        previous.uncaughtException(thread, error)
                    } else {
                        Process.killProcess(Process.myPid())
                    }
                }
            }
        }

        private fun persistCrash(context: Context, thread: Thread, error: Throwable) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_QUEUE, null)
                ?.let { runCatching { NexJson.decodeFromString<TelemetryQueue>(it) }.getOrNull() }
                ?: TelemetryQueue()
            val entry = CrashEntry(
                message = (error.message ?: error.javaClass.name).take(4000),
                stack = error.stackTraceToString().take(16_000),
                context = mapOf(
                    "thread" to thread.name,
                    "appVersion" to BuildConfig.VERSION_NAME,
                ),
                at = System.currentTimeMillis(),
            )
            val updated = existing.copy(crashes = (existing.crashes + entry).takeLast(10))
            prefs.edit().putString(KEY_QUEUE, NexJson.encodeToString(updated)).commit()
        }
    }
}

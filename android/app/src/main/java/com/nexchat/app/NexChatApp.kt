package com.nexchat.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nexchat.app.call.CallManager
import com.nexchat.app.core.crypto.NexSignalStore
import com.nexchat.app.core.crypto.SignalCryptoManager
import com.nexchat.app.core.db.DatabaseProvider
import com.nexchat.app.core.db.NexDatabase
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.NexJson
import com.nexchat.app.core.security.SecureSessionStore
import com.nexchat.app.core.settings.SettingsStore
import com.nexchat.app.data.AuthRepository
import com.nexchat.app.data.AuthState
import com.nexchat.app.data.ChatEvent
import com.nexchat.app.data.ChatRepository
import com.nexchat.app.data.ContactRepository
import com.nexchat.app.net.WebSocketManager
import com.nexchat.app.service.CleanupWorker
import com.nexchat.app.service.NotificationHelper
import com.nexchat.app.service.OutboxWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, BuildConfig.DEFAULT_API_BASE_URL)
    val secure = SecureSessionStore(app)
    val api = ApiClient(settings, secure)
    val notifications = NotificationHelper(app)

    val db: NexDatabase by lazy {
        DatabaseProvider.get(app, secure.getOrCreateDatabasePassphrase())
    }

    val signalStore: NexSignalStore by lazy { NexSignalStore(db) }
    val crypto: SignalCryptoManager by lazy { SignalCryptoManager(signalStore, NexJson) }
    val auth: AuthRepository by lazy { AuthRepository(db, api, secure, crypto, settings, scope) }
    val chat: ChatRepository by lazy { ChatRepository(db, api, crypto, settings) }
    val contacts: ContactRepository by lazy { ContactRepository(db, api, crypto) }
    val call: CallManager by lazy { CallManager(app, scope, chat, api, notifications) }
    val websocket: WebSocketManager by lazy { WebSocketManager(api, chat, auth, scope, app) }

    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError.asStateFlow()
    private var wired = false

    fun bootstrap() {
        wire()
        retryBootstrap()
        scheduleCleanup()
        scheduleOutbox()
    }

    fun retryBootstrap() {
        _startupError.value = null
        scope.launch {
            runCatching { auth.bootstrap() }.onFailure { error ->
                _startupError.value = error.message
                    ?: "The encrypted local store could not be opened on this device."
            }
        }
    }

    private fun wire() {
        if (wired) return
        wired = true
        scope.launch {
            auth.state.collectLatest { state ->
                if (state is AuthState.LoggedIn) {
                    websocket.start()
                    // Repair one-time/Kyber key exhaustion after upgrades and
                    // reconnects so contacts can always fetch a usable bundle.
                    scope.launch { runCatching { chat.ensurePublishedKeys() } }
                    // Drain anything composed while the process was offline.
                    scope.launch { runCatching { chat.flushOutbox() } }
                } else {
                    websocket.stop()
                }
            }
        }
        scope.launch {
            chat.events.collect { event ->
                when (event) {
                    is ChatEvent.Incoming -> {
                        if (chat.activeConversation.value != event.event.conversation.id) {
                            notifications.showMessage(
                                conversation = event.event.conversation,
                                message = event.event.message,
                                preview = settings.state.value.notificationPreview,
                            )
                        }
                    }
                    is ChatEvent.Security -> {
                        notifications.showSecurity(event.nexId, event.message)
                    }
                    is ChatEvent.Call -> call.onSignal(event.event)
                    else -> Unit
                }
            }
        }
    }

    private fun scheduleCleanup() {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            CleanupWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * WorkManager's floor is 15 minutes. This is intentionally a slow safety
     * net: the foreground service retries every few seconds whenever the
     * process is alive, and this guarantees progress after a process kill.
     */
    private fun scheduleOutbox() {
        val request = PeriodicWorkRequestBuilder<OutboxWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            OutboxWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun notificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.CHANNEL_MESSAGES,
                app.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "End-to-end encrypted messages"
                setShowBadge(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.CHANNEL_CALLS,
                app.getString(R.string.notification_channel_calls),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming end-to-end encrypted voice calls"
                setShowBadge(true)
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build(),
                )
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.CHANNEL_SERVICE,
                app.getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the encrypted message connection open"
                setShowBadge(false)
            },
        )
    }
}

class NexChatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notificationChannels()
        container.bootstrap()
    }

    companion object {
        fun from(context: Context): NexChatApp = context.applicationContext as NexChatApp
    }
}

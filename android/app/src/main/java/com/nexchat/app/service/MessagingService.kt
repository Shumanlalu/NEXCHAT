package com.nexchat.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.nexchat.app.NexChatApp

class MessagingService : Service() {
    private val container by lazy { NexChatApp.from(this).container }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            container.notifications.serviceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        // The socket poller drives automatic outbox retry, delivery-receipt
        // sync and incoming-envelope fetch every few seconds while NEXCHAT is
        // running. WorkManager covers the process-killed case separately.
        container.websocket.start()
        // Permission denial must be harmless: the online socket stays alive.
        runCatching { container.nearby.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        container.websocket.start()
        runCatching { container.nearby.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        container.websocket.stop()
        runCatching { container.nearby.stop() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

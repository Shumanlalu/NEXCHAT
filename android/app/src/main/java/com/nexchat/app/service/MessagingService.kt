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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        container.websocket.start()
        return START_STICKY
    }

    override fun onDestroy() {
        container.websocket.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

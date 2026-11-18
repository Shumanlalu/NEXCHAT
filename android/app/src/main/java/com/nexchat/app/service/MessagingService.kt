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
        // Sending is online-only, so this service never retries the outbox.
        // It keeps the authenticated socket (and the periodic receive poll)
        // alive while NEXCHAT is running.
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

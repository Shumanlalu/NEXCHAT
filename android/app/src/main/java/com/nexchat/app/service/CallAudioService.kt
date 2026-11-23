package com.nexchat.app.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nexchat.app.NexChatApp

/**
 * Keeps microphone (and, for video calls, camera) access legal while a call is
 * active and the app is not in the foreground. Android requires a foreground
 * service with the matching type; the messaging service deliberately does not
 * declare either type because it must not hold them outside a call.
 */
class CallAudioService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val type = if (video) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(
            this,
            NotificationHelper.CALL_SERVICE_NOTIFICATION_ID,
            NexChatApp.from(this).container.notifications.callServiceNotification(video),
            type,
        )
        // A restarted service without the in-memory PeerConnection is useless;
        // the call manager will start a fresh one for the next call.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_VIDEO = "video"
    }
}

package com.nexchat.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nexchat.app.MainActivity
import com.nexchat.app.R
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.core.db.MessageEntity
import kotlin.math.absoluteValue

class NotificationHelper(private val context: Context) {
    fun showMessage(conversation: ConversationEntity, message: MessageEntity, preview: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversation.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            conversation.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (preview) conversation.title else "New encrypted message"
        val body = if (preview) {
            message.body.take(180)
        } else {
            "Open NEXCHAT to read"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(conversation.id)
            .setOnlyAlertOnce(false)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(conversation.id.hashCode().absoluteValue, notification)
        }
    }

    fun showSecurity(nexId: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            nexId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Security check required")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(("security:" + nexId).hashCode().absoluteValue, notification)
        }
    }

    fun serviceNotification(): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NEXCHAT is connected")
            .setContentText("End-to-end encrypted messages are ready")
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        const val CHANNEL_MESSAGES = "nexchat_messages_v1"
        const val CHANNEL_SERVICE = "nexchat_service_v1"
        const val CHANNEL_CALLS = "nexchat_calls_v1"
        const val CHANNEL_NEARBY = "nexchat_nearby_v1"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val SERVICE_NOTIFICATION_ID = 4801
    }
}

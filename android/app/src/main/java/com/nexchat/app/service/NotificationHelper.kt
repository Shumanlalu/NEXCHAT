package com.nexchat.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nexchat.app.MainActivity
import com.nexchat.app.R
import com.nexchat.app.call.CallActionReceiver
import com.nexchat.app.call.CallActivity
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

    fun showIncomingCall(callId: String, peerName: String) {
        val fullIntent = Intent(context, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            putExtra(CallActivity.EXTRA_AUTO_ACCEPT, false)
        }
        val fullScreen = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            fullIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The Answer action must launch the activity directly. Starting an
        // activity from a broadcast receiver is blocked by background-activity
        // limits on newer Android releases, while a notification action is
        // allowed to open its UI. CallActivity then requests the microphone
        // permission before accepting the call.
        val answer = PendingIntent.getActivity(
            context,
            ("answer:$callId").hashCode(),
            Intent(context, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(CallActivity.EXTRA_CALL_ID, callId)
                putExtra(CallActivity.EXTRA_AUTO_ACCEPT, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context,
            ("decline:$callId").hashCode(),
            Intent(context, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_DECLINE
                putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val call = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peerName.ifBlank { "Incoming call" })
            .setContentText("Incoming end-to-end encrypted voice call")
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", answer)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, call)
        }
    }

    fun cancelIncomingCall() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)
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
        const val CHANNEL_CALLS = "nexchat_calls_v1"
        const val CHANNEL_SERVICE = "nexchat_service_v1"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val SERVICE_NOTIFICATION_ID = 4801
        const val CALL_NOTIFICATION_ID = 4802
    }
}

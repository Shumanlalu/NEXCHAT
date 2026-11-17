package com.nexchat.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nexchat.app.NexChatApp

/**
 * Handles the Answer/Decline actions on the incoming-call notification even
 * when the Compose UI is not currently in front.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        val container = NexChatApp.from(context).container
        when (intent.action) {
            ACTION_ACCEPT -> {
                container.notifications.cancelIncomingCall()
                val activity = Intent(context, CallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(CallActivity.EXTRA_CALL_ID, callId)
                    putExtra(CallActivity.EXTRA_AUTO_ACCEPT, true)
                }
                runCatching { context.startActivity(activity) }
            }
            ACTION_DECLINE -> {
                container.call.reject(callId)
                container.notifications.cancelIncomingCall()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.nexchat.app.call.ACCEPT"
        const val ACTION_DECLINE = "com.nexchat.app.call.DECLINE"
        const val EXTRA_CALL_ID = "call_id"
    }
}

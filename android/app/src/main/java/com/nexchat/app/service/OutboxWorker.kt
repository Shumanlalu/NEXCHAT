package com.nexchat.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexchat.app.NexChatApp
import com.nexchat.app.data.AuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best-effort background delivery while the process is not running. The
 * foreground [MessagingService] handles reconnects in real time; WorkManager
 * only guarantees that queued ciphertext and pending intents are flushed
 * periodically, even if the app was killed before the network returned.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = NexChatApp.from(applicationContext).container
        if (container.auth.state.value !is AuthState.LoggedIn) return@withContext Result.success()
        // Best-effort relay sync on both sides of the normal online flush.
        // These runCatching calls never influence the worker result, which is
        // still decided solely by the existing online flush.
        runCatching { if (container.nearby.enabled.value) container.nearby.syncNow() }
        val result = runCatching { container.chat.onConnected() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
        runCatching { if (container.nearby.enabled.value) container.nearby.syncNow() }
        result
    }

    companion object {
        const val NAME = "nexchat-outbox"
    }
}

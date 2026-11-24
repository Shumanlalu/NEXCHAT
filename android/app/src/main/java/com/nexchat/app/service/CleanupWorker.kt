package com.nexchat.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexchat.app.NexChatApp
import com.nexchat.app.data.ATTACHMENT_BUFFER_TTL_MILLIS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val db = NexChatApp.from(applicationContext).container.db
            val now = System.currentTimeMillis()
            db.processedEnvelopeDao().purge(now - 30L * 24 * 3600 * 1000)
            db.outboxDao().purgeOlderThan(now - 7L * 24 * 3600 * 1000)
            db.pendingIntentDao().purgeExpired(now)
            db.pendingIntentDao().purgeOlderThan(now - 7L * 24 * 3600 * 1000)
            db.relayDao().purgeExpired(now)
            db.attachmentDao().deleteOrphanChunksOlderThan(now - ATTACHMENT_BUFFER_TTL_MILLIS)
            db.callOutboxDao().deleteExpired(now)
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val NAME = "nexchat-cleanup"
    }
}

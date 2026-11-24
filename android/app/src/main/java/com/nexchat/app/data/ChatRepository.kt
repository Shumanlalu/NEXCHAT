package com.nexchat.app.data

import android.util.Base64
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.nexchat.app.core.crypto.ContentKind
import com.nexchat.app.core.crypto.CryptoException
import com.nexchat.app.core.crypto.DuplicateEnvelopeException
import com.nexchat.app.core.crypto.GroupMemberInfo
import com.nexchat.app.core.crypto.IdentityChangedException
import com.nexchat.app.core.crypto.SecureContent
import com.nexchat.app.core.crypto.SignalCryptoManager
import com.nexchat.app.core.crypto.canonicalSignalDeviceId
import com.nexchat.app.core.db.AttachmentChunkEntity
import com.nexchat.app.core.db.AttachmentEntity
import com.nexchat.app.core.db.AttachmentState
import com.nexchat.app.core.db.CallHistoryEntity
import com.nexchat.app.core.db.CallOutboxEntity
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.core.db.GroupEntity
import com.nexchat.app.core.db.GroupMemberEntity
import com.nexchat.app.core.db.MessageEntity
import com.nexchat.app.core.db.MessageKind
import com.nexchat.app.core.db.MessageStatus
import com.nexchat.app.core.db.NexDatabase
import com.nexchat.app.core.db.OutboxEntity
import com.nexchat.app.core.db.PendingIntentEntity
import com.nexchat.app.core.db.ProcessedEnvelopeEntity
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.ApiException
import com.nexchat.app.core.network.EnvelopeDto
import com.nexchat.app.core.network.GroupDto
import com.nexchat.app.core.network.KeyBundle
import com.nexchat.app.core.network.KeyDeviceDto
import com.nexchat.app.core.network.OutgoingEnvelope
import com.nexchat.app.core.network.PreKeyDto
import com.nexchat.app.core.network.RotateKeysRequest
import com.nexchat.app.core.network.SessionExpiredException
import com.nexchat.app.core.settings.GroupMetadataWatermark
import com.nexchat.app.core.settings.GroupMetadataWatermarkStore
import com.nexchat.app.core.settings.SettingsStore
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A file selected by the user, read into memory by the UI before encryption. */
data class AttachmentDraft(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class TypingEvent(val conversationId: String, val fromNexId: String, val active: Boolean)

data class IncomingMessageEvent(val conversation: ConversationEntity, val message: MessageEntity)

sealed interface CallSignalEvent {
    val conversationId: String
    val callId: String
    val fromNexId: String
    val fromSignalDeviceId: Int
    val sentAt: Long

    data class Offer(
        override val conversationId: String,
        override val fromNexId: String,
        override val fromSignalDeviceId: Int,
        override val callId: String,
        override val sentAt: Long,
        val displayName: String?,
        val sdp: String,
        val video: Boolean = false,
    ) : CallSignalEvent
    data class Answer(
        override val conversationId: String,
        override val fromNexId: String,
        override val fromSignalDeviceId: Int,
        override val callId: String,
        override val sentAt: Long,
        val sdp: String,
        val video: Boolean = false,
    ) : CallSignalEvent
    data class Ice(
        override val conversationId: String,
        override val fromNexId: String,
        override val fromSignalDeviceId: Int,
        override val callId: String,
        override val sentAt: Long,
        val candidate: String,
        val sdpMid: String?,
        val index: Int?,
    ) : CallSignalEvent
    data class Ended(
        override val conversationId: String,
        override val fromNexId: String,
        override val fromSignalDeviceId: Int,
        override val callId: String,
        override val sentAt: Long,
        val reason: String?,
    ) : CallSignalEvent
    data class Rejected(
        override val conversationId: String,
        override val fromNexId: String,
        override val fromSignalDeviceId: Int,
        override val callId: String,
        override val sentAt: Long,
        val reason: String?,
    ) : CallSignalEvent
}

sealed interface ChatEvent {
    data class Typing(val event: TypingEvent) : ChatEvent
    data class Incoming(val event: IncomingMessageEvent) : ChatEvent
    data class Call(val event: CallSignalEvent) : ChatEvent
    data class Security(val nexId: String, val message: String) : ChatEvent
    data class Warning(val conversationId: String, val message: String) : ChatEvent
    data class Delivery(val messageId: String) : ChatEvent
}

private data class Target(val nexId: String, val signalDeviceId: Int, val bundle: KeyBundle?)

private class CallSendPlan(
    val row: CallOutboxEntity,
    val content: SecureContent,
    val targets: MutableList<Target> = mutableListOf(),
)

class ChatRepository(
    private val db: NexDatabase,
    private val api: ApiClient,
    private val crypto: SignalCryptoManager,
    private val settings: SettingsStore,
    private val metadataWatermarks: GroupMetadataWatermarkStore,
    // Additive: existing online-only callers and tests keep compiling unchanged.
    private val nearby: com.nexchat.app.core.nearby.NearbyRelayManager? = null,
) {
    private val profileDao = db.profileDao()
    private val contactDao = db.contactDao()
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()
    private val groupDao = db.groupDao()
    private val outboxDao = db.outboxDao()
    private val intentDao = db.pendingIntentDao()
    private val callOutboxDao = db.callOutboxDao()
    private val processedDao = db.processedEnvelopeDao()
    private val callHistoryDao = db.callHistoryDao()
    private val attachmentDao = db.attachmentDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val sendMutex = Mutex()
    private val callSendMutex = Mutex()
    private val receiveMutex = Mutex()
    private val replenishMutex = Mutex()
    @Volatile private var lastPreKeyCheckAt = 0L
    private val deviceCacheMutex = Mutex()
    private val deviceCache = mutableMapOf<String, Pair<Long, List<KeyDeviceDto>>>()
    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()
    private val _activeConversation = MutableStateFlow<String?>(null)
    val activeConversation = _activeConversation.asStateFlow()

    fun conversations(archived: Boolean = false): Flow<List<ConversationEntity>> =
        conversationDao.observe(archived)

    fun conversation(id: String): Flow<ConversationEntity?> = conversationDao.observeOne(id)

    fun messages(conversationId: String): Flow<PagingData<MessageEntity>> = Pager(
        config = PagingConfig(pageSize = 40, initialLoadSize = 60, enablePlaceholders = false, prefetchDistance = 20),
        pagingSourceFactory = { messageDao.pagingSource(conversationId) },
    ).flow

    fun blockedContacts(): Flow<List<ContactEntity>> = contactDao.observeBlocked()

    fun groups(): Flow<List<GroupEntity>> = groupDao.observeGroups()

    fun groupMembers(groupId: String): Flow<List<GroupMemberEntity>> = groupDao.observeMembers(groupId)

    suspend fun group(groupId: String): GroupEntity? = groupDao.get(groupId)

    suspend fun getConversation(id: String): ConversationEntity? = conversationDao.get(id)

    fun setActiveConversation(id: String?) {
        _activeConversation.value = id
    }

    suspend fun openDirectConversation(nexId: String, displayName: String? = null): ConversationEntity =
        withContext(Dispatchers.IO) {
            val contact = contactDao.get(nexId)
            val title = displayName ?: contact?.displayName ?: nexId
            directConversation(nexId, title, contact?.avatar)
        }

    suspend fun openGroupConversation(groupId: String): ConversationEntity = withContext(Dispatchers.IO) {
        val group = groupDao.get(groupId)
        val existing = conversationDao.getByGroup(groupId)
        if (existing != null) return@withContext existing
        val now = System.currentTimeMillis()
        ConversationEntity(
            id = "group:$groupId",
            type = "GROUP",
            peerNexId = null,
            groupId = groupId,
            title = group?.title ?: "Group chat",
            avatar = group?.avatar,
            lastMessagePreview = null,
            lastMessageAt = now,
            createdAt = now,
            updatedAt = now,
        ).also { conversationDao.upsert(it) }
    }

    suspend fun sendText(conversationId: String, text: String, replyTo: MessageEntity? = null) =
        withContext(Dispatchers.IO) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext
            val conversation = conversationDao.get(conversationId)
                ?: throw CryptoException("This conversation no longer exists")
            if (conversation.type == "DM") {
                val peer = conversation.peerNexId
                if (peer != null && contactDao.get(peer)?.blocked == true) {
                    throw CryptoException("Unblock this contact before sending messages")
                }
            }
            val profile = profileDao.get() ?: throw CryptoException("Your local profile is missing")
            val clientId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val message = MessageEntity(
                id = clientId,
                conversationId = conversationId,
                senderNexId = profile.nexId,
                senderDisplayName = profile.displayName,
                outgoing = true,
                body = trimmed,
                kind = MessageKind.TEXT,
                status = MessageStatus.PENDING,
                sentAt = now,
                replyToId = replyTo?.id,
                replyToPreview = replyTo?.body?.take(160),
            )
            val content = baseContent(ContentKind.TEXT, clientId, profile.toSenderInfo()).copy(
                text = trimmed,
                replyToId = replyTo?.id,
                replyPreview = replyTo?.body?.take(160),
            )
            // Persist the bubble and the send intent atomically. If the app
            // dies or there is no connection, this exact message is retried
            // automatically and never creates a second local bubble.
            db.withTransaction {
                messageDao.upsert(message)
                conversationDao.touch(
                    conversationId,
                    previewForMessage(trimmed, conversation.type == "GROUP"),
                    now,
                )
                enqueueIntent(conversation, content)
            }
            try {
                flushOutbox()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The intent and message row remain queued. The bubble keeps
                // its PENDING state and shows the reason in its error line.
            }
        }

    /**
     * Sends a file as a set of independently encrypted, durable envelopes.
     *
     * The file is split so every Signal ciphertext stays below the relay's
     * envelope limit. The manifest carries the name, size and SHA-256; chunks
     * carry raw bytes as base64 inside the encrypted content. Plaintext lives
     * only in SQLCipher until the user opens or saves the attachment.
     */
    suspend fun sendAttachment(
        conversationId: String,
        draft: AttachmentDraft,
        replyTo: MessageEntity? = null,
    ) = withContext(Dispatchers.IO) {
        if (draft.bytes.isEmpty()) throw CryptoException("The selected file is empty")
        if (draft.bytes.size > MAX_ATTACHMENT_BYTES) {
            throw CryptoException("Attachments must be ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB or smaller")
        }
        val conversation = conversationDao.get(conversationId)
            ?: throw CryptoException("This conversation no longer exists")
        if (conversation.type == "DM") {
            val peer = conversation.peerNexId
            if (peer != null && contactDao.get(peer)?.blocked == true) {
                throw CryptoException("Unblock this contact before sending files")
            }
        }
        val profile = profileDao.get() ?: throw CryptoException("Your local profile is missing")
        val fileName = draft.fileName.ifBlank { "attachment" }.take(180)
        val mimeType = draft.mimeType.ifBlank { "application/octet-stream" }
        val attachmentId = UUID.randomUUID().toString()
        val digest = sha256(draft.bytes)
        val now = System.currentTimeMillis()
        val chunkCount = (draft.bytes.size + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES
        val body = attachmentBody(fileName, AttachmentState.SENDING, 0, chunkCount)
        val message = MessageEntity(
            id = attachmentId,
            conversationId = conversationId,
            senderNexId = profile.nexId,
            senderDisplayName = profile.displayName,
            outgoing = true,
            body = body,
            kind = MessageKind.ATTACHMENT,
            status = MessageStatus.PENDING,
            sentAt = now,
            replyToId = replyTo?.id,
            replyToPreview = replyTo?.body?.take(160),
        )
        val attachment = AttachmentEntity(
            id = attachmentId,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = draft.bytes.size.toLong(),
            sha256 = digest,
            chunkCount = chunkCount,
            state = AttachmentState.SENDING,
            createdAt = now,
        )
        val manifest = baseContent(
            ContentKind.ATTACHMENT_MANIFEST,
            attachmentId,
            profile.toSenderInfo(),
        ).copy(
            attachmentId = attachmentId,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = draft.bytes.size.toLong(),
            sha256 = digest,
            chunkCount = chunkCount,
            text = body,
            replyToId = replyTo?.id,
            replyPreview = replyTo?.body?.take(160),
        )
        val chunks = mutableListOf<AttachmentChunkEntity>()
        val chunkContents = mutableListOf<SecureContent>()
        for (index in 0 until chunkCount) {
            val start = index * ATTACHMENT_CHUNK_BYTES
            val bytes = draft.bytes.copyOfRange(start, minOf(start + ATTACHMENT_CHUNK_BYTES, draft.bytes.size))
            val clientId = UUID.randomUUID().toString()
            chunks += AttachmentChunkEntity(attachmentId, index, clientId, bytes, now)
            chunkContents += baseContent(
                ContentKind.ATTACHMENT_CHUNK,
                clientId,
                profile.toSenderInfo(),
            ).copy(
                attachmentId = attachmentId,
                chunkIndex = index,
                chunkCount = chunkCount,
                chunkData = Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        }
        db.withTransaction {
            messageDao.upsert(message)
            attachmentDao.upsert(attachment)
            chunks.forEach { attachmentDao.insertChunk(it) }
            conversationDao.touch(
                conversationId,
                previewForMessage(body, conversation.type == "GROUP"),
                now,
            )
            enqueueIntent(conversation, manifest)
            chunkContents.forEach { enqueueIntent(conversation, it) }
        }
        try {
            flushOutbox()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The intents and plaintext chunks are durable in SQLCipher; the
            // normal outbox retry loop will finish the transfer later.
        }
    }

    /** Reassembles a completed attachment for the local file-picker/open flow. */
    suspend fun loadAttachment(id: String): Pair<AttachmentEntity, ByteArray>? =
        withContext(Dispatchers.IO) {
            val attachment = attachmentDao.get(id) ?: return@withContext null
            if (attachment.state != AttachmentState.READY) return@withContext null
            val chunks = attachmentDao.chunks(id)
            if (chunks.size != attachment.chunkCount) return@withContext null
            val out = ByteArray(attachment.fileSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            var offset = 0
            for (chunk in chunks) {
                val size = minOf(chunk.data.size, out.size - offset)
                if (size <= 0) return@withContext null
                System.arraycopy(chunk.data, 0, out, offset, size)
                offset += size
            }
            if (offset != out.size || sha256(out) != attachment.sha256) return@withContext null
            attachment to out
        }

    suspend fun editMessage(conversationId: String, messageId: String, text: String) =
        withContext(Dispatchers.IO) {
            val message = messageDao.get(messageId) ?: return@withContext
            if (!message.outgoing) return@withContext
            val profile = profileDao.get() ?: return@withContext
            if (message.conversationId != conversationId || message.senderNexId != profile.nexId) {
                return@withContext
            }
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext
            val now = System.currentTimeMillis()
            messageDao.applyEdit(messageId, trimmed, now, conversationId, profile.nexId, outgoing = true)
            val conversation = conversationDao.get(conversationId) ?: return@withContext
            val content = baseContent(ContentKind.EDIT, UUID.randomUUID().toString(), profile.toSenderInfo()).copy(
                targetClientId = messageId,
                text = trimmed,
            )
            sendControl(conversation, content)
        }

    suspend fun deleteMessage(conversationId: String, messageId: String) = withContext(Dispatchers.IO) {
        val message = messageDao.get(messageId) ?: return@withContext
        if (message.conversationId != conversationId) return@withContext
        val profile = profileDao.get() ?: return@withContext
        if (!message.outgoing) {
            messageDao.deleteLocally(messageId)
            return@withContext
        }
        if (message.senderNexId != profile.nexId) return@withContext
        val now = System.currentTimeMillis()
        if (message.kind == MessageKind.ATTACHMENT) {
            attachmentDao.deleteChunks(messageId)
            attachmentDao.delete(messageId)
        }
        messageDao.applyDeleteForEveryone(messageId, now, conversationId, profile.nexId, outgoing = true)
        val conversation = conversationDao.get(conversationId) ?: return@withContext
        val content = baseContent(ContentKind.DELETE, UUID.randomUUID().toString(), profile.toSenderInfo()).copy(
            targetClientId = messageId,
        )
        sendControl(conversation, content)
    }

    suspend fun react(conversationId: String, messageId: String, emoji: String) = withContext(Dispatchers.IO) {
        val message = messageDao.get(messageId) ?: return@withContext
        val profile = profileDao.get() ?: return@withContext
        val reactions = parseReactions(message.reactionsJson).toMutableMap()
        val current = reactions[emoji].orEmpty().toMutableList()
        if (!current.contains(profile.nexId)) current += profile.nexId
        reactions[emoji] = current
        messageDao.setReactions(messageId, json.encodeToString(reactionsSerializer, reactions))
        val conversation = conversationDao.get(conversationId) ?: return@withContext
        val content = baseContent(ContentKind.REACTION, UUID.randomUUID().toString(), profile.toSenderInfo()).copy(
            targetClientId = messageId,
            emoji = emoji,
        )
        sendControl(conversation, content)
    }

    suspend fun markConversationRead(conversationId: String) = withContext(Dispatchers.IO) {
        conversationDao.clearUnread(conversationId, System.currentTimeMillis())
        if (!settings.state.value.readReceipts) return@withContext
        val conversation = conversationDao.get(conversationId) ?: return@withContext
        val outgoing = messageDao.unreadOutgoing(conversationId, 100)
        if (outgoing.isEmpty()) return@withContext
        val profile = profileDao.get() ?: return@withContext
        val content = baseContent(ContentKind.READ, UUID.randomUUID().toString(), profile.toSenderInfo()).copy(
            readClientIds = outgoing.map { it.id },
        )
        // Read receipts are part of the conversation state, not ephemeral
        // presence. Keep them queued until the relay accepts them.
        runCatching { sendControl(conversation, content) }
    }

    suspend fun setTyping(conversationId: String, active: Boolean) = withContext(Dispatchers.IO) {
        if (!settings.state.value.typingIndicators) return@withContext
        val conversation = conversationDao.get(conversationId) ?: return@withContext
        val profile = profileDao.get() ?: return@withContext
        val content = baseContent(ContentKind.TYPING, UUID.randomUUID().toString(), profile.toSenderInfo()).copy(
            text = if (active) "1" else "0",
        )
        runCatching { sendEphemeral(conversation, content) }
    }

    suspend fun sendCallSignal(conversationId: String, content: SecureContent, durable: Boolean = true) =
        withContext(Dispatchers.IO) {
            val conversation = conversationDao.get(conversationId) ?: return@withContext
            // Calling is an explicit 1:1 feature. Never fan a call out to a
            // group or to a contact who has been blocked.
            if (conversation.type != "DM") return@withContext
            val peer = conversation.peerNexId ?: return@withContext
            if (contactDao.get(peer)?.blocked == true) return@withContext
            if (content.callId.isNullOrBlank()) return@withContext
            val profile = profileDao.get() ?: return@withContext
            val filled = content.copy(
                clientId = content.clientId ?: UUID.randomUUID().toString(),
                senderNexId = profile.nexId,
                senderUsername = profile.username,
                senderDisplayName = profile.displayName,
                senderAvatar = profile.avatar,
            )
            if (durable) sendControl(conversation, filled) else runCatching { sendEphemeral(conversation, filled) }
        }

    /**
     * Queues an SDP/ICE signal on the dedicated call queue and attempts it
     * immediately. Unlike ordinary messages, a call signal is worthless after
     * the ring/connect timeout, so it must never inherit the message outbox's
     * five-second-to-fifteen-minute retry schedule.
     *
     * Persisting the sealed signal (rather than only firing it inline) covers a
     * brief network handoff without keeping the caller staring at
     * "Connecting" for minutes.
     */
    suspend fun sendCallSignalFast(
        conversationId: String,
        content: SecureContent,
        targetSignalDeviceId: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val conversation = conversationDao.get(conversationId) ?: return@withContext
        if (conversation.type != "DM") return@withContext
        val peer = conversation.peerNexId ?: return@withContext
        if (contactDao.get(peer)?.blocked == true) return@withContext
        if (content.callId.isNullOrBlank()) return@withContext
        val profile = profileDao.get() ?: return@withContext
        val now = System.currentTimeMillis()
        val filled = content.copy(
            clientId = content.clientId ?: UUID.randomUUID().toString(),
            senderNexId = profile.nexId,
            senderUsername = profile.username,
            senderDisplayName = profile.displayName,
            senderAvatar = profile.avatar,
            timestamp = if (content.timestamp > 0) content.timestamp else now,
        )
        val ttl = when (filled.kind) {
            ContentKind.CALL_HANGUP,
            ContentKind.CALL_REJECT,
            -> TERMINAL_CALL_SIGNAL_TTL_MILLIS
            else -> CALL_SIGNAL_TTL_MILLIS
        }
        callOutboxDao.upsert(
            CallOutboxEntity(
                id = UUID.randomUUID().toString(),
                callId = filled.callId!!,
                kind = filled.kind,
                conversationId = conversationId,
                contentJson = json.encodeToString(SecureContent.serializer(), filled),
                targetSignalDeviceId = targetSignalDeviceId,
                createdAt = now,
                expiresAt = now + ttl,
            ),
        )
        flushCallSignals()
    }

    /**
     * Materializes and sends due call signals using a one-second retry cadence.
     * The queue is bounded by its TTL, so a stale offer/ICE candidate can never
     * ring a phone minutes later.
     */
    suspend fun flushCallSignals(): Boolean = withContext(Dispatchers.IO) {
        callSendMutex.withLock {
            val now = System.currentTimeMillis()
            callOutboxDao.deleteExpired(now)
            val pending = callOutboxDao.due(now, MAX_CALL_SIGNAL_BATCH)
            if (pending.isEmpty()) return@withLock true
            val plans = mutableListOf<CallSendPlan>()
            val envelopes = mutableListOf<OutgoingEnvelope>()
            for (row in pending) {
                val content = runCatching {
                    json.decodeFromString(SecureContent.serializer(), row.contentJson)
                }.getOrNull()
                val conversation = conversationDao.get(row.conversationId)
                val peer = conversation?.peerNexId
                if (content == null || peer == null) {
                    callOutboxDao.delete(row.id)
                    continue
                }
                val targets = try {
                    resolveTargets(peer)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    scheduleCallRetry(listOf(row), error, now)
                    continue
                }
                val selected = row.targetSignalDeviceId?.let { wanted ->
                    val canonical = canonicalSignalDeviceId(wanted)
                    targets.filter { canonicalSignalDeviceId(it.signalDeviceId) == canonical }
                        .ifEmpty { targets }
                } ?: targets
                val plan = CallSendPlan(row, content)
                for (target in selected) {
                    try {
                        val payload = crypto.encrypt(content, target.nexId, target.signalDeviceId, target.bundle)
                        plan.targets += target
                        envelopes += OutgoingEnvelope(
                            recipientNexId = target.nexId,
                            recipientSignalDeviceId = target.signalDeviceId,
                            clientId = content.clientId ?: row.id,
                            ciphertext = payload.ciphertext,
                            cipherType = payload.cipherType,
                            ephemeral = false,
                            // Priority lets the relay serve call SDP/ICE before
                            // older, undecryptable message envelopes. Keep the
                            // 10s floor so older relays still accept the TTL.
                            priority = true,
                            expiresInSeconds = (((row.expiresAt - now) / 1000L)
                                .coerceIn(10L, MAX_ENVELOPE_TTL_SECONDS)).toInt(),
                            groupId = null,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        scheduleCallRetry(listOf(row), error, now)
                    }
                }
                if (plan.targets.isNotEmpty()) plans += plan
            }
            if (envelopes.isEmpty()) return@withLock true

            val response = try {
                api.sendEnvelopes(envelopes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error is SessionExpiredException) throw error
                scheduleCallRetry(plans.map { it.row }, error, now)
                return@withLock false
            }

            val accepted = response.accepted.map {
                it.clientId to canonicalSignalDeviceId(it.recipientSignalDeviceId)
            }.toSet()
            val skipped = response.skipped.associateBy {
                it.clientId to canonicalSignalDeviceId(it.recipientSignalDeviceId ?: 0)
            }
            val retryRows = mutableListOf<CallOutboxEntity>()
            for (plan in plans) {
                var retry = false
                var drop = false
                for (target in plan.targets) {
                    val key = (plan.content.clientId ?: plan.row.id) to
                        canonicalSignalDeviceId(target.signalDeviceId)
                    if (key in accepted) continue
                    when (skipped[key]?.reason) {
                        "DEVICE_NOT_ACTIVE" -> {
                            invalidateDeviceCache(target.nexId)
                            retry = true
                        }
                        "RECIPIENT_NOT_FOUND" -> drop = true
                        else -> retry = true
                    }
                }
                when {
                    drop -> callOutboxDao.delete(plan.row.id)
                    retry -> retryRows += plan.row
                    else -> callOutboxDao.delete(plan.row.id)
                }
            }
            if (retryRows.isNotEmpty()) {
                scheduleCallRetry(retryRows, null, now)
                return@withLock false
            }
            true
        }
    }

    private suspend fun scheduleCallRetry(
        rows: List<CallOutboxEntity>,
        error: Throwable?,
        now: Long,
    ) {
        val message = error?.let { sendFailureMessage(it) }
        for (row in rows) {
            if (row.expiresAt <= now) {
                callOutboxDao.delete(row.id)
                continue
            }
            callOutboxDao.markAttempt(
                row.id,
                now,
                now + callRetryDelay(row.attempts + 1),
                message,
            )
        }
    }

    private fun callRetryDelay(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, 5)
        return (CALL_RETRY_BASE_MILLIS shl exponent).coerceAtMost(CALL_RETRY_MAX_MILLIS)
    }

    suspend fun clearCallSignals(callId: String) = withContext(Dispatchers.IO) {
        // Terminal signals are deliberately preserved until their short TTL
        // expires. A concurrent finishCall() must not delete a REJECT/HANGUP
        // row before its detached sender has had a chance to flush it.
        callOutboxDao.deleteForCallExceptTerminal(callId)
    }

    /**
     * Materializes queued intents, then sends every due encrypted envelope.
     *
     * Delivery is at-least-once. A transient failure only pushes the next
     * attempt into the future: the encrypted row stays inside SQLCipher and the
     * message keeps its PENDING state. The relay deduplicates
     * (sender device, recipient device, client ID), so retrying after a lost
     * response can never deliver the same message twice.
     */
    suspend fun flushOutbox(): Boolean = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val now = System.currentTimeMillis()
            purgeExpiredQueues(now)
            materializeIntents(now)
            dispatchOutbox(now)
        }
    }

    /**
     * Fetches durable delivery receipts. The recipient may have acknowledged an
     * envelope while this device was offline, in which case the WebSocket event
     * is long gone and only this endpoint can advance the status to DELIVERED.
     */
    suspend fun fetchReceipts() = withContext(Dispatchers.IO) {
        val response = api.deliveryReceipts()
        if (response.receipts.isEmpty()) return@withContext
        val deliveredAt = mutableMapOf<String, Long>()
        for (receipt in response.receipts) {
            val at = parseTimestamp(receipt.deliveredAt) ?: System.currentTimeMillis()
            deliveredAt.merge(receipt.clientId, at, ::maxOf)
        }
        for ((clientId, at) in deliveredAt) {
            messageDao.markDelivered(clientId, at)
            _events.tryEmit(ChatEvent.Delivery(clientId))
        }
        runCatching { api.ackDeliveryReceipts(response.receipts.map { it.id }) }
    }

    private suspend fun purgeExpiredQueues(now: Long) {
        for (row in outboxDao.expired(now)) {
            outboxDao.delete(row.id)
            failIfNothingQueued(row.clientId, "This message expired before it could be sent")
        }
        for (intent in intentDao.expired(now)) {
            intentDao.delete(intent.id)
            failIfNothingQueued(intent.clientId, "This message expired before it could be sent")
        }
    }

    private suspend fun materializeIntents(now: Long) {
        for (intent in intentDao.due(now, MAX_SEND_BATCH)) {
            if (intent.materializedAt != null) continue
            val content = runCatching {
                json.decodeFromString(SecureContent.serializer(), intent.contentJson)
            }.getOrNull()
            if (content == null) {
                intentDao.delete(intent.id)
                failIfNothingQueued(intent.clientId, "This queued message could not be restored")
                continue
            }
            val conversation = conversationDao.get(intent.conversationId)
            if (conversation == null) {
                intentDao.delete(intent.id)
                failIfNothingQueued(intent.clientId, "The conversation for this message no longer exists")
                continue
            }
            try {
                val targets = resolveConversationTargets(conversation)
                if (targets.isEmpty()) {
                    scheduleIntent(intent, "No active encrypted device is available yet", now)
                    continue
                }
                // Deterministic row IDs make this idempotent. If the process
                // dies after encryption but before the intent is marked, the
                // retry replaces the same outbox rows instead of duplicating.
                buildOutboxRows(intent, content, targets, now).forEach { outboxDao.upsert(it) }
                intentDao.markMaterialized(intent.id, now)
                messageDao.clearError(intent.clientId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IdentityChangedException) {
                conversation.peerNexId?.let { contactDao.setVerified(it, null) }
                intentDao.delete(intent.id)
                failIfNothingQueued(intent.clientId, friendlyError(error))
                _events.tryEmit(
                    ChatEvent.Security(
                        conversation.peerNexId ?: intent.clientId,
                        "The safety number for this contact changed. Verify it, then reset the security session from the contact screen.",
                    ),
                )
            } catch (error: Exception) {
                if (isPermanentSendError(error)) {
                    intentDao.delete(intent.id)
                    failIfNothingQueued(intent.clientId, friendlyError(error))
                } else {
                    scheduleIntent(intent, friendlyError(error), now)
                }
            }
        }
    }

    private suspend fun buildOutboxRows(
        intent: PendingIntentEntity,
        content: SecureContent,
        targets: List<Target>,
        now: Long,
    ): List<OutboxEntity> {
        val ttl = ttlFor(content.kind)
        val rows = mutableListOf<OutboxEntity>()
        for (target in targets) {
            val payload = crypto.encrypt(content, target.nexId, target.signalDeviceId, target.bundle)
            rows += OutboxEntity(
                id = "${intent.id}:${target.nexId}:${target.signalDeviceId}",
                clientId = intent.clientId,
                conversationId = intent.conversationId,
                recipientNexId = target.nexId,
                recipientSignalDeviceId = target.signalDeviceId,
                groupId = content.groupId,
                cipherType = payload.cipherType,
                ciphertext = payload.ciphertext,
                ephemeral = false,
                createdAt = now,
                expiresAt = now + ttl,
            )
        }
        return rows
    }

    private suspend fun dispatchOutbox(now: Long): Boolean {
        val pending = outboxDao.due(now, MAX_SEND_BATCH)
        if (pending.isEmpty()) {
            cleanupMaterializedIntents()
            return true
        }
        val outgoing = pending.map {
            OutgoingEnvelope(
                recipientNexId = it.recipientNexId,
                recipientSignalDeviceId = it.recipientSignalDeviceId,
                clientId = it.clientId,
                ciphertext = it.ciphertext,
                cipherType = it.cipherType,
                ephemeral = it.ephemeral,
                expiresInSeconds = (((it.expiresAt - now) / 1000L)
                    .coerceIn(10L, MAX_ENVELOPE_TTL_SECONDS)).toInt(),
                groupId = it.groupId,
            )
        }
        val response = try {
            api.sendEnvelopes(outgoing)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Online delivery is always attempted first. Only a transient
            // outage is eligible for nearby relay handoff: a permanent
            // rejection or an expired session keeps the existing failure path.
            if (!isPermanentDeliveryError(error) && error !is SessionExpiredException && nearby != null) {
                val localNexId = profileDao.get()?.nexId
                if (localNexId != null) {
                    val accepted = try {
                        nearby.enqueue(outgoing, localNexId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    if (accepted.isNotEmpty()) {
                        val acceptedKeys = accepted.map {
                            Triple(
                                it.clientId,
                                it.recipientNexId,
                                canonicalSignalDeviceId(it.recipientSignalDeviceId),
                            )
                        }.toSet()
                        for (row in pending) {
                            val key = Triple(
                                row.clientId,
                                row.recipientNexId,
                                canonicalSignalDeviceId(row.recipientSignalDeviceId),
                            )
                            if (key in acceptedKeys) outboxDao.delete(row.id)
                        }
                        cleanupMaterializedIntents()
                        for (clientId in accepted.map { it.clientId }.distinct()) {
                            // Acceptance is not delivery. Mark the bubble SENT
                            // only once this client has no ciphertext queued.
                            if (outboxDao.countForClient(clientId) == 0) {
                                messageDao.markSent(clientId)
                            }
                        }
                        // Intentionally no ChatEvent.Delivery here: only an
                        // explicit receipt (online or nearby) may claim that.
                        return true
                    }
                }
            }
            if (isPermanentDeliveryError(error)) {
                // The relay refused the payload itself (not an outage), so
                // retrying the identical ciphertext can never succeed.
                for (clientId in pending.map { it.clientId }.distinct()) {
                    outboxDao.deleteForClient(clientId)
                    intentDao.deleteForClient(clientId)
                    failMessage(clientId, sendFailureMessage(error))
                }
            } else {
                scheduleOutboxRetry(pending, error, now)
            }
            if (error is SessionExpiredException) throw error
            return false
        }

        val acceptedKeys = response.accepted.associateBy {
            it.clientId to canonicalSignalDeviceId(it.recipientSignalDeviceId)
        }
        val skippedKeys = response.skipped.mapNotNull { skip ->
            skip.recipientSignalDeviceId?.let {
                skip.clientId to canonicalSignalDeviceId(it)
            }
        }.toSet()
        val affected = linkedSetOf<String>()

        for (row in pending) {
            affected += row.clientId
            val accepted = acceptedKeys[
                row.clientId to canonicalSignalDeviceId(row.recipientSignalDeviceId),
            ] ?: continue
            outboxDao.delete(row.id)
            parseTimestamp(accepted.deliveredAt)?.let { deliveredAt ->
                messageDao.markDelivered(row.clientId, deliveredAt)
            }
            val chunk = attachmentDao.chunkByClientId(row.clientId)
            if (chunk != null) {
                attachmentDao.markChunkSent(row.clientId)
                if (attachmentDao.unsentChunkCount(chunk.attachmentId) == 0) {
                    attachmentDao.setState(chunk.attachmentId, AttachmentState.READY)
                    attachmentDao.get(chunk.attachmentId)?.let { attachment ->
                        messageDao.updateBody(attachment.id, "📎 ${attachment.fileName}")
                    }
                }
            }
        }

        var failed = false
        val acceptedClients = response.accepted.map { it.clientId }.toSet()
        for (skip in response.skipped.distinctBy {
            it.clientId to it.recipientSignalDeviceId
        }) {
            affected += skip.clientId
            val deviceKey = skip.recipientSignalDeviceId?.let(::canonicalSignalDeviceId)
            val rows = outboxDao.forClient(skip.clientId).filter {
                deviceKey == null ||
                    canonicalSignalDeviceId(it.recipientSignalDeviceId) == deviceKey
            }
            when (skip.reason) {
                "DEVICE_NOT_ACTIVE" -> {
                    // The contact reinstalled or replaced that device. Drop the
                    // stale ciphertext, refresh the device list and encrypt a
                    // fresh copy for the replacement on the next attempt.
                    if (rows.isNotEmpty()) {
                        rows.forEach { outboxDao.delete(it.id) }
                        rows.map { it.recipientNexId }.distinct().forEach {
                            invalidateDeviceCache(it)
                        }
                        intentDao.reopen(skip.clientId)
                    }
                }
                "GROUP_NOT_FOUND",
                "NOT_A_GROUP_MEMBER",
                -> {
                    // The whole group send is invalid, not just one member.
                    outboxDao.deleteForClient(skip.clientId)
                    intentDao.deleteForClient(skip.clientId)
                    failMessage(skip.clientId, skipReason(skip.reason))
                    failed = true
                }
                "RECIPIENT_NOT_FOUND",
                "RECIPIENT_NOT_IN_GROUP",
                -> {
                    // Drop only the unreachable member. If nobody accepted
                    // this message it is genuinely failed; for a group the
                    // remaining members may already have received it.
                    rows.forEach { outboxDao.delete(it.id) }
                    if (skip.clientId !in acceptedClients) {
                        failMessage(skip.clientId, skipReason(skip.reason))
                        failed = true
                    }
                }
                else -> {
                    rows.forEach { row ->
                        outboxDao.markAttempt(
                            row.id,
                            now,
                            now + retryDelay(row.attempts + 1),
                            skipReason(skip.reason),
                        )
                    }
                }
            }
        }

        // A response that omits a row is not an acceptance. Keep the ciphertext
        // and retry rather than showing a fake SENT status.
        for (row in pending) {
            val key = row.clientId to canonicalSignalDeviceId(row.recipientSignalDeviceId)
            if (key in acceptedKeys || key in skippedKeys) continue
            affected += row.clientId
            outboxDao.markAttempt(
                row.id,
                now,
                now + retryDelay(row.attempts + 1),
                "The relay did not confirm this message",
            )
        }

        cleanupMaterializedIntents()

        for (clientId in affected) {
            if (!hasQueuedSend(clientId)) {
                messageDao.markSent(clientId)
                messageDao.clearError(clientId)
            }
        }
        return !failed
    }

    private suspend fun scheduleOutboxRetry(
        rows: List<OutboxEntity>,
        error: Throwable,
        now: Long,
    ) {
        val message = sendFailureMessage(error)
        for (row in rows) {
            outboxDao.markAttempt(
                row.id,
                now,
                now + retryDelay(row.attempts + 1),
                message,
            )
        }
        for (clientId in rows.map { it.clientId }.distinct()) {
            messageDao.setError(clientId, message)
        }
    }

    private suspend fun scheduleIntent(intent: PendingIntentEntity, message: String, now: Long) {
        intentDao.markAttempt(
            intent.id,
            now,
            now + retryDelay(intent.attempts + 1),
            message,
        )
        messageDao.setError(intent.clientId, message)
    }

    private suspend fun cleanupMaterializedIntents() {
        for (intent in intentDao.materializedWithoutOutbox()) {
            intentDao.delete(intent.id)
        }
    }

    private suspend fun hasQueuedSend(clientId: String): Boolean =
        outboxDao.countForClient(clientId) > 0 || intentDao.countForClient(clientId) > 0

    private suspend fun failIfNothingQueued(clientId: String, message: String) {
        if (!hasQueuedSend(clientId)) failMessage(clientId, message)
    }

    private suspend fun failMessage(clientId: String, message: String) {
        messageDao.updateStatus(clientId, MessageStatus.FAILED, System.currentTimeMillis())
        messageDao.setError(clientId, message)
    }

    private fun isPermanentSendError(error: Throwable): Boolean =
        error is ApiException && error.code in setOf(
            "RECIPIENT_NOT_FOUND",
            "USER_NOT_FOUND",
            "NOT_A_GROUP_MEMBER",
            "RECIPIENT_NOT_IN_GROUP",
            "GROUP_NOT_FOUND",
            "NO_SERVER",
            "INSECURE_SERVER",
        )

    private fun isPermanentDeliveryError(error: Throwable): Boolean =
        isPermanentSendError(error) ||
            (error is ApiException && error.statusCode in setOf(400, 413, 415, 422))

    private fun retryDelay(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, 10)
        return (RETRY_BASE_MILLIS shl exponent).coerceAtMost(RETRY_MAX_MILLIS)
    }

    private fun ttlFor(kind: String): Long = when (kind) {
        ContentKind.CALL_OFFER,
        ContentKind.CALL_ANSWER,
        ContentKind.CALL_ICE,
        ContentKind.CALL_HANGUP,
        ContentKind.CALL_REJECT,
        -> CALL_SIGNAL_TTL_MILLIS
        else -> MESSAGE_TTL_MILLIS
    }

    private fun parseTimestamp(raw: String?): Long? =
        raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }

    private fun sendFailureMessage(error: Throwable?): String = when {
        error is SessionExpiredException -> error.message ?: "Your session expired. Sign in again."
        error is ApiException && error.code == "EPHEMERAL_TARGET_OFFLINE" ->
            "The recipient is offline."
        error is ApiException && error.code != "HTTP_0" -> error.message ?: "The server rejected the message"
        error is IOException ->
            "You're offline. NEXCHAT will send this automatically when the connection returns."
        else -> "Waiting to send. NEXCHAT will retry automatically."
    }

    suspend fun onConnected() = withContext(Dispatchers.IO) {
        runCatching { syncGroups() }
        runCatching { fetchReceipts() }
        runCatching { receivePending() }
        runCatching { flushCallSignals() }
        runCatching { flushOutbox() }
        runCatching { replenishPreKeysIfNeeded() }
    }

    /**
     * Fallback sync used while the socket is connected. Serverless relays can
     * place a sender and recipient on different instances, so a realtime push
     * is best-effort. This also drives the automatic retry loop for messages
     * composed while offline.
     */
    suspend fun pollPending() = withContext(Dispatchers.IO) {
        runCatching { receivePending() }
        runCatching { fetchReceipts() }
        runCatching { flushCallSignals() }
        runCatching { flushOutbox() }
        // Replenish even while the socket stays open. A recipient that remains
        // connected would otherwise never publish new one-time prekeys.
        runCatching { replenishPreKeysIfNeeded() }
    }

    /**
     * Holds the request open until ciphertext arrives. Serverless relays may
     * run the sender and recipient on different function instances, so the
     * in-memory WebSocket push can miss; this returns immediately when a call
     * signal appears instead of waiting for a fixed background poll interval.
     */
    suspend fun waitForPending(timeoutMs: Int = LONG_POLL_TIMEOUT_MILLIS) =
        withContext(Dispatchers.IO) {
            val response = api.waitForEnvelopes(timeoutMs)
            processPending(response.messages)
        }

    /**
     * Fetch queued ciphertext without syncing anything else. Call signaling
     * uses this at a short interval because a serverless relay may not push a
     * WebSocket event to the function instance that holds the recipient socket.
     */
    suspend fun pollIncoming() = withContext(Dispatchers.IO) {
        receivePending()
        flushCallSignals()
    }

    /**
     * Drops the pairwise Signal session and trust record for one contact so the
     * next send performs a fresh X3DH/PQXDH handshake. This is the explicit
     * recovery path after a safety-number change; it intentionally requires a
     * user action rather than silently accepting a new identity.
     */
    suspend fun resetSecuritySession(nexId: String) = withContext(Dispatchers.IO) {
        val queuedClients = outboxDao.clientIdsForRecipient(nexId)
        outboxDao.deleteForRecipient(nexId)
        val now = System.currentTimeMillis()
        for (clientId in queuedClients) {
            if (outboxDao.countForClient(clientId) == 0) {
                messageDao.updateStatus(clientId, MessageStatus.FAILED, now)
                messageDao.setError(
                    clientId,
                    "Security session reset. Verify the new safety number, then send again.",
                )
            }
        }
        deviceCacheMutex.withLock { deviceCache.remove(nexId) }
        contactDao.setVerified(nexId, null)
        crypto.forgetIdentity(nexId)
    }

    private suspend fun receivePending() {
        processPending(api.pendingEnvelopes().messages)
    }

    private suspend fun processPending(messages: List<EnvelopeDto>) {
        val ackIds = mutableListOf<String>()
        for (envelope in messages) {
            val handled = receiveEnvelope(envelope, acknowledge = false)
            if (handled) ackIds += envelope.id
        }
        if (ackIds.isNotEmpty()) runCatching { api.ackEnvelopes(ackIds) }
    }

    suspend fun receiveEnvelope(envelope: EnvelopeDto, acknowledge: Boolean = true): Boolean {
        return receiveMutex.withLock {
            withContext(Dispatchers.IO) {
                if (processedDao.get(envelope.id) != null) {
                    if (acknowledge) runCatching { api.ackEnvelopes(listOf(envelope.id)) }
                    return@withContext true
                }
                val content = try {
                    crypto.decrypt(envelope)
                } catch (_: DuplicateEnvelopeException) {
                    processedDao.insert(ProcessedEnvelopeEntity(envelope.id, System.currentTimeMillis()))
                    if (acknowledge) runCatching { api.ackEnvelopes(listOf(envelope.id)) }
                    return@withContext true
                } catch (error: IdentityChangedException) {
                    contactDao.setVerified(envelope.senderNexId, null)
                    processedDao.insert(ProcessedEnvelopeEntity(envelope.id, System.currentTimeMillis()))
                    _events.tryEmit(
                        ChatEvent.Security(
                            envelope.senderNexId,
                            "The security number for this contact changed. Verify it, then reset the security session from the contact screen.",
                        ),
                    )
                    if (acknowledge) runCatching { api.ackEnvelopes(listOf(envelope.id)) }
                    return@withContext true
                } catch (error: Exception) {
                    if (acknowledge) {
                        // Leave unacknowledged so a correctly sized retry can be fetched later.
                    }
                    return@withContext false
                }
                try {
                    handleContent(envelope, content)
                    processedDao.insert(ProcessedEnvelopeEntity(envelope.id, System.currentTimeMillis()))
                    if (acknowledge) runCatching { api.ackEnvelopes(listOf(envelope.id)) }
                    true
                } catch (error: Exception) {
                    false
                }
            }
        }
    }

    private suspend fun handleContent(envelope: EnvelopeDto, content: SecureContent) {
        // Identity always comes from the authenticated Signal envelope. The
        // display fields inside the encrypted payload are self-asserted and
        // must never be allowed to impersonate another NEX ID.
        val senderNexId = envelope.senderNexId
        upsertSender(senderNexId, content)
        when (content.kind) {
            ContentKind.TEXT -> {
                if (envelopeGroupMatches(content, envelope)) {
                    handleIncomingText(envelope, content, senderNexId)
                }
            }
            ContentKind.EDIT -> {
                val target = ownedIncomingTarget(envelope, content) ?: return
                messageDao.applyEdit(
                    target.id,
                    content.text.orEmpty(),
                    content.timestamp.takeIf { time -> time > 0 } ?: System.currentTimeMillis(),
                    target.conversationId,
                    senderNexId,
                    outgoing = false,
                )
            }
            ContentKind.DELETE -> {
                val target = ownedIncomingTarget(envelope, content) ?: return
                if (target.kind == MessageKind.ATTACHMENT) {
                    attachmentDao.deleteChunks(target.id)
                    attachmentDao.delete(target.id)
                }
                messageDao.applyDeleteForEveryone(
                    target.id,
                    content.timestamp.takeIf { time -> time > 0 } ?: System.currentTimeMillis(),
                    target.conversationId,
                    senderNexId,
                    outgoing = false,
                )
            }
            ContentKind.REACTION -> {
                if (envelopeGroupMatches(content, envelope)) {
                    handleReaction(envelope, content, senderNexId)
                }
            }
            ContentKind.READ -> {
                // The local setting controls whether we send receipts, not
                // whether receipts about our own messages are applied.
                val ids = content.readClientIds
                if (ids.isNotEmpty()) messageDao.markRead(ids, System.currentTimeMillis())
            }
            ContentKind.TYPING -> {
                val conversationId = conversationIdFor(envelope)
                _events.tryEmit(
                    ChatEvent.Typing(
                        TypingEvent(conversationId, senderNexId, content.text == "1"),
                    ),
                )
            }
            ContentKind.GROUP_META -> {
                if (envelopeGroupMatches(content, envelope)) {
                    handleGroupMeta(envelope, content, senderNexId)
                }
            }
            ContentKind.ATTACHMENT_MANIFEST -> {
                if (envelopeGroupMatches(content, envelope)) {
                    handleIncomingAttachmentManifest(envelope, content, senderNexId)
                }
            }
            ContentKind.ATTACHMENT_CHUNK -> {
                if (envelopeGroupMatches(content, envelope)) {
                    handleIncomingAttachmentChunk(envelope, content, senderNexId)
                }
            }
            ContentKind.CALL_OFFER -> emitCallSignal(envelope, content) { cid, callId, sentAt ->
                content.sdp?.let {
                    CallSignalEvent.Offer(
                        conversationId = cid,
                        fromNexId = senderNexId,
                        fromSignalDeviceId = envelope.senderSignalDeviceId,
                        callId = callId,
                        sentAt = sentAt,
                        displayName = content.senderDisplayName,
                        sdp = it,
                        video = content.video == true,
                    )
                }
            }
            ContentKind.CALL_ANSWER -> emitCallSignal(envelope, content) { cid, callId, sentAt ->
                content.sdp?.let {
                    CallSignalEvent.Answer(
                        cid,
                        senderNexId,
                        envelope.senderSignalDeviceId,
                        callId,
                        sentAt,
                        it,
                        video = content.video == true,
                    )
                }
            }
            ContentKind.CALL_ICE -> emitCallSignal(envelope, content) { cid, callId, sentAt ->
                CallSignalEvent.Ice(
                    cid,
                    senderNexId,
                    envelope.senderSignalDeviceId,
                    callId,
                    sentAt,
                    content.candidate.orEmpty(),
                    content.sdpMid,
                    content.sdpMLineIndex,
                )
            }
            ContentKind.CALL_HANGUP -> emitCallSignal(envelope, content) { cid, callId, sentAt ->
                CallSignalEvent.Ended(
                    cid,
                    senderNexId,
                    envelope.senderSignalDeviceId,
                    callId,
                    sentAt,
                    content.callEndReason,
                )
            }
            ContentKind.CALL_REJECT -> emitCallSignal(envelope, content) { cid, callId, sentAt ->
                CallSignalEvent.Rejected(
                    cid,
                    senderNexId,
                    envelope.senderSignalDeviceId,
                    callId,
                    sentAt,
                    content.callEndReason,
                )
            }
        }
    }

    /** The relay's group routing is authoritative; payload self-assertions are not. */
    private fun envelopeGroupMatches(content: SecureContent, envelope: EnvelopeDto): Boolean =
        content.groupId == envelope.groupId

    /**
     * EDIT/DELETE may only touch a message that was originally received from
     * this exact sender in this exact conversation. Local outgoing rows and
     * messages routed through another conversation are never valid targets.
     */
    private suspend fun ownedIncomingTarget(
        envelope: EnvelopeDto,
        content: SecureContent,
    ): MessageEntity? {
        if (!envelopeGroupMatches(content, envelope)) return null
        val targetId = content.targetClientId?.takeIf { it.isNotBlank() } ?: return null
        val conversationId = conversationIdFor(envelope)
        if (envelope.groupId != null &&
            groupDao.getMembers(envelope.groupId).none { it.nexId == envelope.senderNexId }
        ) {
            return null
        }
        val message = messageDao.get(targetId) ?: return null
        if (message.conversationId != conversationId || message.outgoing ||
            message.senderNexId != envelope.senderNexId
        ) {
            return null
        }
        return message
    }

    private suspend fun emitCallSignal(
        envelope: EnvelopeDto,
        content: SecureContent,
        build: (conversationId: String, callId: String, sentAt: Long) -> CallSignalEvent?,
    ) {
        // Calls are one-to-one. A group id on a call signal is never valid and
        // must not be allowed to pop an incoming-call screen for a group.
        if (content.groupId != null) return
        val senderNexId = envelope.senderNexId
        if (contactDao.get(senderNexId)?.blocked == true) return
        val callId = content.callId?.takeIf { it.isNotBlank() } ?: return
        // Use the relay timestamp so an expired queued offer is not revived by
        // a skewed or malicious sender clock.
        val sentAt = runCatching { Instant.parse(envelope.createdAt).toEpochMilli() }
            .getOrDefault(content.timestamp)
        val event = build(conversationIdFor(envelope), callId, sentAt) ?: return
        // Call signals are latency-sensitive and must not be dropped when the
        // event buffer is busy. This collector is always active while the app
        // process is alive, so suspending here is safe.
        _events.emit(ChatEvent.Call(event))
    }

    suspend fun isBlocked(nexId: String): Boolean = withContext(Dispatchers.IO) {
        contactDao.get(nexId)?.blocked == true
    }

    /**
     * Persists a finished call in the encrypted history table and inserts a
     * lightweight CALL row into the conversation timeline.
     */
    suspend fun recordCall(
        call: CallHistoryEntity,
        conversationId: String,
        body: String,
    ) = withContext(Dispatchers.IO) {
        callHistoryDao.upsert(call)
        val profile = profileDao.get() ?: return@withContext
        val conversation = conversationDao.get(conversationId) ?: return@withContext
        val endedAt = call.endedAt ?: System.currentTimeMillis()
        val message = MessageEntity(
            id = "call:${call.id}",
            conversationId = conversationId,
            senderNexId = if (call.outgoing) profile.nexId else call.peerNexId,
            senderDisplayName = if (call.outgoing) profile.displayName else call.peerName,
            outgoing = call.outgoing,
            body = body,
            kind = MessageKind.CALL,
            status = MessageStatus.SENT,
            sentAt = endedAt,
            receivedAt = endedAt,
        )
        if (messageDao.insertIgnore(message) == -1L) return@withContext
        conversationDao.touch(conversationId, body, endedAt)
    }

    private suspend fun handleIncomingText(envelope: EnvelopeDto, content: SecureContent, senderNexId: String) {
        val contact = contactDao.get(senderNexId)
        if (contact?.blocked == true) return
        val conversation = ensureConversationFor(envelope, content, senderNexId)
        val messageId = content.clientId ?: envelope.clientId
        val body = content.text.orEmpty()
        if (body.isBlank()) return
        val now = System.currentTimeMillis()
        // Preserve the sender's original composition time. A message that sat
        // in the relay while this device was offline must keep its place in
        // the timeline instead of jumping to "now" when it is finally fetched.
        val sentAt = content.timestamp.takeIf { it > 0 }
            ?: parseTimestamp(envelope.createdAt)
            ?: now
        val message = MessageEntity(
            id = messageId,
            conversationId = conversation.id,
            senderNexId = senderNexId,
            senderDisplayName = content.senderDisplayName ?: contact?.displayName ?: senderNexId,
            outgoing = false,
            body = body,
            status = MessageStatus.DELIVERED,
            sentAt = sentAt,
            receivedAt = now,
            replyToId = content.replyToId,
            replyToPreview = content.replyPreview,
        )
        val inserted = messageDao.insertIgnore(message)
        if (inserted == -1L) return
        // Delayed envelopes must not move the conversation backwards. When the
        // timestamps tie, the message that actually arrived last owns the
        // preview.
        conversationDao.touchIfNewer(
            conversation.id,
            previewForMessage(body, conversation.type == "GROUP"),
            sentAt,
        )
        if (_activeConversation.value != conversation.id) {
            conversationDao.incrementUnread(conversation.id, now)
        }
        _events.tryEmit(ChatEvent.Incoming(IncomingMessageEvent(conversation, message)))
    }

    private suspend fun handleIncomingAttachmentManifest(
        envelope: EnvelopeDto,
        content: SecureContent,
        senderNexId: String,
    ) {
        val contact = contactDao.get(senderNexId)
        if (contact?.blocked == true) return
        val id = content.attachmentId?.takeIf { it.isNotBlank() } ?: return
        val fileName = content.fileName?.takeIf { it.isNotBlank() }?.take(180) ?: return
        val mimeType = content.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val fileSize = content.fileSize ?: return
        val digest = content.sha256?.takeIf { SHA256_PATTERN.matches(it) } ?: return
        val chunkCount = content.chunkCount ?: return
        if (fileSize <= 0L || fileSize > MAX_ATTACHMENT_BYTES) return
        val maxChunks = (MAX_ATTACHMENT_BYTES + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES
        if (chunkCount <= 0 || chunkCount > maxChunks) return
        val conversation = ensureConversationFor(envelope, content, senderNexId)
        val now = System.currentTimeMillis()
        val existing = attachmentDao.get(id)
        val received = attachmentDao.chunkCount(id)
        val state = if (existing?.state == AttachmentState.READY) {
            AttachmentState.READY
        } else {
            AttachmentState.RECEIVING
        }
        val body = attachmentBody(fileName, state, minOf(received, chunkCount), chunkCount)
        val message = MessageEntity(
            id = id,
            conversationId = conversation.id,
            senderNexId = senderNexId,
            senderDisplayName = content.senderDisplayName ?: contact?.displayName ?: senderNexId,
            outgoing = false,
            body = body,
            kind = MessageKind.ATTACHMENT,
            status = MessageStatus.DELIVERED,
            sentAt = content.timestamp.takeIf { it > 0 }
                ?: parseTimestamp(envelope.createdAt) ?: now,
            receivedAt = now,
            replyToId = content.replyToId,
            replyToPreview = content.replyPreview,
        )
        val inserted = messageDao.insertIgnore(message)
        if (existing == null) {
            attachmentDao.upsert(
                AttachmentEntity(
                    id = id,
                    fileName = fileName,
                    mimeType = mimeType,
                    fileSize = fileSize,
                    sha256 = digest,
                    chunkCount = chunkCount,
                    state = state,
                    createdAt = now,
                ),
            )
        } else {
            messageDao.updateBody(id, body)
        }
        if (received >= chunkCount) finalizeAttachmentIfComplete(id)
        if (inserted == -1L) return
        conversationDao.touchIfNewer(
            conversation.id,
            previewForMessage(body, conversation.type == "GROUP"),
            message.sentAt,
        )
        if (_activeConversation.value != conversation.id) {
            conversationDao.incrementUnread(conversation.id, now)
        }
        _events.tryEmit(ChatEvent.Incoming(IncomingMessageEvent(conversation, message)))
    }

    private suspend fun handleIncomingAttachmentChunk(
        envelope: EnvelopeDto,
        content: SecureContent,
        senderNexId: String,
    ) {
        if (contactDao.get(senderNexId)?.blocked == true) return
        val id = content.attachmentId?.takeIf { it.isNotBlank() } ?: return
        val index = content.chunkIndex ?: return
        val encoded = content.chunkData ?: return
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return
        if (bytes.isEmpty() || bytes.size > ATTACHMENT_CHUNK_BYTES) return
        val declaredCount = content.chunkCount ?: return
        val maxChunks = (MAX_ATTACHMENT_BYTES + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES
        if (declaredCount <= 0 || declaredCount > maxChunks) return
        if (index < 0 || index >= declaredCount) return
        val conversationId = conversationIdFor(envelope)
        val attachment = attachmentDao.get(id)
        if (attachment != null) {
            if (declaredCount != attachment.chunkCount || index >= attachment.chunkCount) return
            val message = messageDao.get(id) ?: return
            if (message.conversationId != conversationId) return
        } else {
            // A chunk can arrive before its manifest on a different relay
            // instance. Buffer it, but bound both count and lifetime so an
            // attacker cannot grow the local SQLCipher database indefinitely.
            val now = System.currentTimeMillis()
            attachmentDao.deleteOrphanChunksOlderThan(now - ATTACHMENT_BUFFER_TTL_MILLIS)
            if (attachmentDao.chunkCount(id) >= declaredCount) return
            if (attachmentDao.orphanChunkCount() >= MAX_BUFFERED_ATTACHMENT_CHUNKS) return
        }
        attachmentDao.insertChunk(
            AttachmentChunkEntity(
                attachmentId = id,
                index = index,
                clientId = content.clientId ?: UUID.randomUUID().toString(),
                data = bytes,
                createdAt = System.currentTimeMillis(),
                sent = true,
            ),
        )
        if (attachment == null) return
        val received = attachmentDao.chunkCount(id)
        messageDao.updateBody(
            id,
            attachmentBody(attachment.fileName, AttachmentState.RECEIVING, minOf(received, attachment.chunkCount), attachment.chunkCount),
        )
        if (received < attachment.chunkCount) return
        finalizeAttachmentIfComplete(id)
    }

    private suspend fun finalizeAttachmentIfComplete(id: String) {
        val attachment = attachmentDao.get(id) ?: return
        val chunks = attachmentDao.chunks(id)
        if (chunks.size < attachment.chunkCount) return
        val assembled = ByteArray(attachment.fileSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var offset = 0
        for (chunk in chunks.sortedBy { it.index }) {
            val size = minOf(chunk.data.size, assembled.size - offset)
            if (size <= 0) {
                attachmentDao.setState(id, AttachmentState.FAILED)
                messageDao.updateBody(
                    id,
                    attachmentBody(attachment.fileName, AttachmentState.FAILED, chunks.size, attachment.chunkCount),
                )
                return
            }
            System.arraycopy(chunk.data, 0, assembled, offset, size)
            offset += size
        }
        val state = if (offset == assembled.size && sha256(assembled) == attachment.sha256) {
            AttachmentState.READY
        } else {
            AttachmentState.FAILED
        }
        attachmentDao.setState(id, state)
        messageDao.updateBody(
            id,
            attachmentBody(attachment.fileName, state, chunks.size, attachment.chunkCount),
        )
    }

    private suspend fun ensureConversationFor(
        envelope: EnvelopeDto,
        content: SecureContent,
        senderNexId: String,
    ): ConversationEntity {
        if (!envelopeGroupMatches(content, envelope)) {
            throw CryptoException("Group id does not match the relay envelope")
        }
        if (content.groupId != null) {
            val existing = groupDao.get(content.groupId)
            if (existing == null) {
                val now = System.currentTimeMillis()
                groupDao.upsert(
                    GroupEntity(
                        id = content.groupId,
                        title = content.groupTitle ?: "Group chat",
                        avatar = null,
                        creatorNexId = null,
                        myRole = "member",
                        createdAt = now,
                        updatedAt = now,
                        metadataKnown = content.groupTitle != null,
                    ),
                )
            }
            val conversation = conversationDao.getByGroup(content.groupId)
            return conversation ?: run {
                val now = System.currentTimeMillis()
                ConversationEntity(
                    id = "group:${content.groupId}",
                    type = "GROUP",
                    peerNexId = null,
                    groupId = content.groupId,
                    title = content.groupTitle ?: groupDao.get(content.groupId)?.title ?: "Group chat",
                    avatar = null,
                    lastMessagePreview = null,
                    lastMessageAt = now,
                    createdAt = now,
                    updatedAt = now,
                ).also { conversationDao.upsert(it) }
            }
        }
        val contact = contactDao.get(senderNexId)
        return directConversation(
            senderNexId,
            content.senderDisplayName ?: contact?.displayName ?: senderNexId,
            content.senderAvatar ?: contact?.avatar,
        )
    }

    private fun conversationIdFor(envelope: EnvelopeDto): String =
        envelope.groupId?.let { "group:$it" } ?: "dm:${envelope.senderNexId}"

    private suspend fun handleReaction(
        envelope: EnvelopeDto,
        content: SecureContent,
        senderNexId: String,
    ) {
        val target = content.targetClientId ?: return
        val emoji = content.emoji ?: return
        val conversationId = conversationIdFor(envelope)
        if (envelope.groupId != null &&
            groupDao.getMembers(envelope.groupId).none { it.nexId == senderNexId }
        ) {
            return
        }
        val existing = messageDao.get(target) ?: return
        if (existing.conversationId != conversationId) return
        val map = parseReactions(existing.reactionsJson).toMutableMap()
        val from = senderNexId
        map.keys.toList().forEach { key ->
            val updated = map[key].orEmpty().filterNot { it == from }
            if (updated.isEmpty()) map.remove(key) else map[key] = updated
        }
        val list = map[emoji].orEmpty().toMutableList()
        list += from
        map[emoji] = list
        messageDao.setReactions(target, json.encodeToString(reactionsSerializer, map))
    }

    private suspend fun upsertSender(nexId: String, content: SecureContent) {
        if (nexId == profileDao.get()?.nexId) return
        val existing = contactDao.get(nexId)
        val username = content.senderUsername ?: existing?.username ?: ""
        val displayName = content.senderDisplayName ?: existing?.displayName ?: nexId
        if (existing == null) {
            contactDao.upsert(
                ContactEntity(
                    nexId = nexId,
                    userId = null,
                    username = username,
                    displayName = displayName,
                    avatar = content.senderAvatar,
                    identityKey = crypto.knownDeviceIds(nexId).firstOrNull()?.let {
                        crypto.peerIdentity(nexId, it)
                    },
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } else if (
            existing.displayName != displayName ||
            (username.isNotBlank() && existing.username != username)
        ) {
            contactDao.upsert(
                existing.copy(
                    username = if (username.isBlank()) existing.username else username,
                    displayName = displayName,
                    avatar = content.senderAvatar ?: existing.avatar,
                ),
            )
        }
    }

    private suspend fun handleGroupMeta(
        envelope: EnvelopeDto,
        content: SecureContent,
        senderNexId: String,
    ) {
        val groupId = content.groupId ?: return
        if (!envelopeGroupMatches(content, envelope)) return
        // Order by the relay's arrival time and the envelope/client id, never
        // by the sender's self-asserted content timestamp.
        val timestamp = parseTimestamp(envelope.createdAt) ?: 0L
        val watermarkId = content.clientId?.takeIf { it.isNotBlank() } ?: envelope.clientId
        val watermark = GroupMetadataWatermark(timestamp, watermarkId)
        if (!metadataWatermarks.shouldApply(groupId, watermark)) return
        if (groupDao.getMembers(groupId).none { it.nexId == senderNexId }) return
        val now = System.currentTimeMillis()
        val existing = groupDao.get(groupId)
        groupDao.upsert(
            GroupEntity(
                id = groupId,
                title = content.groupTitle ?: existing?.title ?: "Group chat",
                avatar = existing?.avatar,
                creatorNexId = existing?.creatorNexId,
                myRole = existing?.myRole ?: "member",
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                metadataKnown = true,
            ),
        )
        val members = content.groupMembers.map {
            GroupMemberEntity(
                groupId = groupId,
                nexId = it.nexId,
                username = it.username,
                displayName = it.displayName,
                avatar = null,
                role = it.role,
            )
        }
        if (members.isNotEmpty()) {
            groupDao.upsertMembers(members)
            groupDao.deleteMembersNotIn(groupId, members.map { it.nexId } + senderNexId)
        }
        val conversation = conversationDao.getByGroup(groupId)
        val title = content.groupTitle ?: existing?.title ?: "Group chat"
        if (conversation == null) {
            conversationDao.upsert(
                ConversationEntity(
                    id = "group:$groupId",
                    type = "GROUP",
                    peerNexId = null,
                    groupId = groupId,
                    title = title,
                    avatar = null,
                    lastMessagePreview = null,
                    lastMessageAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else if (conversation.title != title) {
            conversationDao.upsert(conversation.copy(title = title, updatedAt = now))
        }
        metadataWatermarks.markApplied(groupId, watermark)
    }

    suspend fun createGroup(title: String, members: List<ContactEntity>): ConversationEntity =
        withContext(Dispatchers.IO) {
            val profile = profileDao.get() ?: throw CryptoException("Your local profile is missing")
            val response = api.createGroup(members.map { it.nexId })
            val dto = response.group
            val now = System.currentTimeMillis()
            val group = GroupEntity(
                id = dto.id,
                title = title.trim().ifBlank { "New group" },
                avatar = null,
                creatorNexId = profile.nexId,
                myRole = "admin",
                createdAt = now,
                updatedAt = now,
                metadataKnown = true,
            )
            groupDao.upsert(group)
            val rows = dto.members.map {
                GroupMemberEntity(
                    groupId = dto.id,
                    nexId = it.nexId,
                    username = it.username,
                    displayName = it.displayName,
                    avatar = it.avatar,
                    role = it.role,
                )
            }
            groupDao.upsertMembers(rows)
            val conversation = ConversationEntity(
                id = "group:${dto.id}",
                type = "GROUP",
                peerNexId = null,
                groupId = dto.id,
                title = group.title,
                avatar = null,
                lastMessagePreview = "Group created",
                lastMessageAt = now,
                createdAt = now,
                updatedAt = now,
            )
            conversationDao.upsert(conversation)
            sendGroupMeta(group)
            conversation
        }

    suspend fun refreshGroup(groupId: String) = withContext(Dispatchers.IO) {
        val response = api.getGroup(groupId)
        val now = System.currentTimeMillis()
        val existing = groupDao.get(groupId)
        val group = GroupEntity(
            id = groupId,
            title = existing?.title ?: "Group chat",
            avatar = null,
            creatorNexId = existing?.creatorNexId,
            myRole = response.group.members.firstOrNull {
                it.nexId == profileDao.get()?.nexId
            }?.role ?: existing?.myRole ?: "member",
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            metadataKnown = existing?.metadataKnown ?: false,
        )
        groupDao.upsert(group)
        groupDao.upsertMembers(
            response.group.members.map {
                GroupMemberEntity(
                    groupId = groupId,
                    nexId = it.nexId,
                    username = it.username,
                    displayName = it.displayName,
                    avatar = it.avatar,
                    role = it.role,
                )
            },
        )
        group
    }

    suspend fun addGroupMembers(groupId: String, contacts: List<ContactEntity>) = withContext(Dispatchers.IO) {
        api.addGroupMembers(groupId, contacts.map { it.nexId })
        val group = refreshGroup(groupId)
        runCatching { sendGroupMeta(group) }
    }

    suspend fun removeGroupMember(groupId: String, nexId: String) = withContext(Dispatchers.IO) {
        val member = api.getGroup(groupId).group.members.firstOrNull { it.nexId == nexId }
            ?: throw CryptoException("That member is no longer in the group")
        api.removeGroupMember(groupId, member.userId)
        val group = refreshGroup(groupId)
        runCatching { sendGroupMeta(group) }
    }

    suspend fun leaveGroup(groupId: String) = withContext(Dispatchers.IO) {
        api.leaveGroup(groupId)
        groupDao.delete(groupId)
        conversationDao.getByGroup(groupId)?.let { conversationDao.delete(it.id) }
    }

    suspend fun renameGroup(groupId: String, title: String) = withContext(Dispatchers.IO) {
        val existing = groupDao.get(groupId) ?: return@withContext
        groupDao.upsert(existing.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
        conversationDao.getByGroup(groupId)?.let {
            conversationDao.upsert(it.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
        }
        sendGroupMeta(groupDao.get(groupId)!!)
    }

    private suspend fun sendGroupMeta(group: GroupEntity) = withContext(Dispatchers.IO) {
        val profile = profileDao.get() ?: return@withContext
        val members = groupDao.getMembers(group.id)
        val content = baseContent(ContentKind.GROUP_META, UUID.randomUUID().toString(), profile.toSenderInfo()).copy(
            groupId = group.id,
            groupTitle = group.title,
            groupMembers = members.map {
                GroupMemberInfo(
                    nexId = it.nexId,
                    username = it.username,
                    displayName = it.displayName,
                    role = it.role,
                )
            },
        )
        sendControl(
            ConversationEntity(
                id = "group:${group.id}",
                type = "GROUP",
                peerNexId = null,
                groupId = group.id,
                title = group.title,
                avatar = null,
                lastMessagePreview = null,
                lastMessageAt = System.currentTimeMillis(),
                createdAt = group.createdAt,
                updatedAt = group.updatedAt,
            ),
            content,
        )
    }

    private suspend fun sendControl(conversation: ConversationEntity, content: SecureContent) {
        enqueueIntent(conversation, content)
        flushOutbox()
    }

    private suspend fun sendEphemeral(conversation: ConversationEntity, content: SecureContent) {
        val targets = resolveConversationTargets(conversation)
        if (targets.isEmpty()) return
        encryptAndSendEphemeral(conversation, content, targets)
    }

    private suspend fun resolveConversationTargets(conversation: ConversationEntity): List<Target> {
        return if (conversation.type == "GROUP") {
            val groupId = conversation.groupId ?: return emptyList()
            var members = groupDao.getMembers(groupId)
            if (members.isEmpty()) {
                runCatching { refreshGroup(groupId) }
                members = groupDao.getMembers(groupId)
            }
            val self = profileDao.get()?.nexId
            val targets = mutableListOf<Target>()
            val unavailable = mutableListOf<String>()
            for (member in members.filter { it.nexId != self }) {
                try {
                    targets += resolveTargets(member.nexId)
                } catch (error: IdentityChangedException) {
                    // An identity change is a security event, not an offline
                    // member. Never silently skip it.
                    throw error
                } catch (error: ApiException) {
                    if (!error.isUnavailableRecipient()) throw error
                    unavailable += member.displayName.ifBlank { member.nexId }
                }
            }
            if (targets.isEmpty() && unavailable.isNotEmpty()) {
                throw ApiException(
                    0,
                    "NO_ACTIVE_DEVICE",
                    "No group member currently has an active encryption device. Ask them to sign in and open NEXCHAT.",
                )
            }
            if (unavailable.isNotEmpty()) {
                _events.tryEmit(
                    ChatEvent.Warning(
                        conversation.id,
                        "Sent to available members. ${unavailable.distinct().joinToString(", ")} " +
                            "currently ${if (unavailable.size == 1) "has" else "have"} no active encryption device.",
                    ),
                )
            }
            targets.distinctBy { it.nexId to it.signalDeviceId }
        } else {
            val peer = conversation.peerNexId ?: return emptyList()
            resolveTargets(peer)
        }
    }

    private fun ApiException.isUnavailableRecipient(): Boolean =
        code == "NO_ACTIVE_DEVICES" ||
            code == "NO_ACTIVE_DEVICE" ||
            code == "NO_KEY_BUNDLE" ||
            code == "PREKEYS_EXHAUSTED" ||
            code == "USER_NOT_FOUND"

    private suspend fun resolveTargets(nexId: String): List<Target> {
        val known = crypto.knownDeviceIds(nexId).toSet()
        // Always discover the current device set. A contact can add or replace a
        // device at any time; using only cached session IDs would keep encrypting
        // to an old, unreachable device forever.
        val current = try {
            currentDevices(nexId)
        } catch (error: ApiException) {
            if (known.isNotEmpty() && error.isTransient()) {
                // Offline fallback: established sessions still work without a
                // device-list refresh. A deleted account or revoked session must
                // not be masked by an old ratchet.
                return known.map { Target(nexId, it, null) }
            }
            throw error
        } catch (error: Exception) {
            if (known.isNotEmpty()) return known.map { Target(nexId, it, null) }
            throw error
        }
        if (current.isEmpty()) {
            // HTTP 200 with zero active devices means the account exists but no
            // installation is currently signed in. This is different from an
            // empty prekey pool, so use a distinct code and message.
            throw ApiException(
                0,
                "NO_ACTIVE_DEVICE",
                "This contact is signed out or has not finished setting up NEXCHAT. Ask them to sign in and open the app.",
            )
        }

        val targets = mutableListOf<Target>()
        for (device in current.filter { canonicalSignalDeviceId(it.signalDeviceId) in known }) {
            // The server device list is authoritative. If the public identity key
            // no longer matches the key bound to our session, stop before sending
            // and require the user to verify the contact's new safety number.
            val trusted = crypto.peerIdentity(nexId, device.signalDeviceId)
            if (trusted != null && trusted != device.identityKey) {
                throw IdentityChangedException(nexId)
            }
            targets += Target(nexId, device.signalDeviceId, null)
        }
        val missing = current.filterNot { canonicalSignalDeviceId(it.signalDeviceId) in known }
        if (missing.isNotEmpty()) {
            // Only unknown devices consume a one-time prekey. Known sessions stay
            // on their existing ratchet and do not burn the contact's key pool.
            val bundles = api.getKeyBundles(nexId, missing.map { it.signalDeviceId }.toSet())
            val byDevice = bundles.devices.associateBy { it.signalDeviceId }
            for (device in missing) {
                byDevice[device.signalDeviceId]?.let { targets += Target(nexId, device.signalDeviceId, it) }
            }
        }
        if (targets.isEmpty()) {
            throw ApiException(
                0,
                "PREKEYS_EXHAUSTED",
                "This contact has no usable encryption keys. Ask them to reopen NEXCHAT so fresh keys are published.",
            )
        }
        return targets.distinctBy { it.signalDeviceId }
    }

    private fun ApiException.isTransient(): Boolean =
        statusCode == 0 || statusCode == 408 || statusCode == 429 || statusCode >= 500

    private suspend fun currentDevices(nexId: String): List<KeyDeviceDto> {
        val now = System.currentTimeMillis()
        deviceCacheMutex.withLock {
            deviceCache[nexId]?.let { (cachedAt, devices) ->
                // Never cache an empty list: the contact may have just signed in,
                // and a cached empty response would keep failing for a full minute.
                if (devices.isNotEmpty() && now - cachedAt < DEVICE_CACHE_MILLIS) return devices
            }
        }
        val response = api.getKeyDevices(nexId)
        // Keep the server order but remove duplicate signal device IDs.
        val devices = response.devices.distinctBy { it.signalDeviceId }
        deviceCacheMutex.withLock {
            if (devices.isEmpty()) deviceCache.remove(nexId)
            else deviceCache[nexId] = now to devices
        }
        return devices
    }

    private suspend fun invalidateDeviceCache(nexId: String) {
        deviceCacheMutex.withLock { deviceCache.remove(nexId) }
    }

    /**
     * Stores the send intent in SQLCipher. The encrypted content cannot be
     * produced until the recipient's current device bundle is reachable, so
     * this is what survives an offline composition or a process kill.
     */
    private suspend fun enqueueIntent(conversation: ConversationEntity, content: SecureContent) {
        val now = System.currentTimeMillis()
        val clientId = content.clientId ?: UUID.randomUUID().toString()
        // The conversation row is the only authority for group routing. This
        // closes the gap where group content was relayed as a DM and skipped
        // server-side group membership checks.
        val scoped = scopeContentToConversation(conversation, content)
        intentDao.upsert(
            PendingIntentEntity(
                id = UUID.randomUUID().toString(),
                clientId = clientId,
                conversationId = conversation.id,
                contentJson = json.encodeToString(SecureContent.serializer(), scoped),
                durable = true,
                createdAt = now,
                expiresAt = now + ttlFor(content.kind),
            ),
        )
    }

    private suspend fun encryptAndSendEphemeral(
        conversation: ConversationEntity,
        content: SecureContent,
        targets: List<Target>,
    ) {
        val scoped = scopeContentToConversation(conversation, content)
        val messages = targets.mapNotNull { target ->
            runCatching {
                val payload = crypto.encrypt(scoped, target.nexId, target.signalDeviceId, target.bundle)
                OutgoingEnvelope(
                    recipientNexId = target.nexId,
                    recipientSignalDeviceId = target.signalDeviceId,
                    clientId = scoped.clientId ?: UUID.randomUUID().toString(),
                    ciphertext = payload.ciphertext,
                    cipherType = payload.cipherType,
                    ephemeral = true,
                    expiresInSeconds = 120,
                    groupId = scoped.groupId,
                )
            }.getOrNull()
        }
        if (messages.isNotEmpty()) api.sendEnvelopes(messages)
    }

    suspend fun syncGroups() = withContext(Dispatchers.IO) {
        val response = runCatching { api.getGroups() }.getOrNull() ?: return@withContext
        val self = profileDao.get()?.nexId
        val now = System.currentTimeMillis()
        for (dto in response.groups) {
            val existing = groupDao.get(dto.id)
            groupDao.upsert(
                GroupEntity(
                    id = dto.id,
                    title = existing?.title ?: "Group chat",
                    avatar = existing?.avatar,
                    creatorNexId = existing?.creatorNexId,
                    myRole = dto.members.firstOrNull { it.nexId == self }?.role ?: existing?.myRole ?: "member",
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    metadataKnown = existing?.metadataKnown ?: false,
                ),
            )
            if (existing == null) {
                groupDao.upsertMembers(
                    dto.members.map {
                        GroupMemberEntity(
                            groupId = dto.id,
                            nexId = it.nexId,
                            username = it.username,
                            displayName = it.displayName,
                            avatar = it.avatar,
                            role = it.role,
                        )
                    },
                )
                if (conversationDao.getByGroup(dto.id) == null) {
                    conversationDao.upsert(
                        ConversationEntity(
                            id = "group:${dto.id}",
                            type = "GROUP",
                            peerNexId = null,
                            groupId = dto.id,
                            title = existing?.title ?: "Group chat",
                            avatar = null,
                            lastMessagePreview = "You were added to a group",
                            lastMessageAt = now,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Called on startup and while connected. Repairs the published prekey set
     * if a previous session exhausted its one-time keys or the device row no
     * longer points at a usable Kyber key.
     */
    suspend fun ensurePublishedKeys() = withContext(Dispatchers.IO) {
        crypto.ensureInitialized()
        runCatching { replenishPreKeysIfNeeded() }
        Unit
    }

    private suspend fun replenishPreKeysIfNeeded() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastPreKeyCheckAt < PRE_KEY_CHECK_INTERVAL_MILLIS) return@withContext
        if (!replenishMutex.tryLock()) return@withContext
        try {
            lastPreKeyCheckAt = System.currentTimeMillis()
            val count = api.preKeyCount()
            if (count.oneTimePreKeys >= 10 && count.kyberPreKeys >= 1) return@withContext
            val batch = crypto.newUploadBatch()
            api.rotateKeys(
                RotateKeysRequest(
                    signedPreKeyId = batch.signedPreKeyId,
                    signedPreKeyPublic = batch.signedPreKeyPublic,
                    signedPreKeySignature = batch.signedPreKeySignature,
                    kyberPreKeyId = batch.kyberPreKeyId,
                    kyberPreKeyPublic = batch.kyberPreKeyPublic,
                    kyberPreKeySignature = batch.kyberPreKeySignature,
                    oneTimePreKeys = batch.oneTimePreKeys.map { PreKeyDto(it.first, it.second) },
                ),
            )
        } finally {
            replenishMutex.unlock()
        }
    }

    suspend fun markDelivered(clientId: String, at: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            messageDao.markDelivered(clientId, at)
            _events.tryEmit(ChatEvent.Delivery(clientId))
        }

    /** A nearby relay confirmed the recipient received this client's envelope. */
    suspend fun markNearbyDelivered(clientId: String, at: Long) = withContext(Dispatchers.IO) {
        messageDao.markDelivered(clientId, at)
        _events.tryEmit(ChatEvent.Delivery(clientId))
    }

    /**
     * The nearby relay could not finish delivery for now. Keep the status
     * intact so a later success can still advance PENDING/SENT to DELIVERED.
     */
    suspend fun markNearbyFailed(clientId: String, message: String) = withContext(Dispatchers.IO) {
        messageDao.setError(clientId, message)
        val conversationId = messageDao.get(clientId)?.conversationId ?: clientId
        _events.tryEmit(ChatEvent.Warning(conversationId, message))
    }

    suspend fun markConversationArchived(id: String, archived: Boolean) =
        withContext(Dispatchers.IO) { conversationDao.setArchived(id, archived) }

    suspend fun markConversationMuted(id: String, muted: Boolean) =
        withContext(Dispatchers.IO) { conversationDao.setMuted(id, muted) }

    suspend fun pinConversation(id: String, pinned: Boolean) =
        withContext(Dispatchers.IO) { conversationDao.setPinned(id, pinned, System.currentTimeMillis()) }

    suspend fun saveDraft(id: String, draft: String) =
        withContext(Dispatchers.IO) { conversationDao.setDraft(id, draft) }

    suspend fun clearConversation(id: String) = withContext(Dispatchers.IO) {
        messageDao.allForConversation(id)
            .filter { it.kind == MessageKind.ATTACHMENT }
            .forEach { attachmentDao.deleteChunks(it.id); attachmentDao.delete(it.id) }
        messageDao.deleteConversation(id)
        conversationDao.updateLastMessage(id, "", System.currentTimeMillis())
    }

    suspend fun deleteLocalMessage(messageId: String) = withContext(Dispatchers.IO) {
        val message = messageDao.get(messageId)
        if (message?.kind == MessageKind.ATTACHMENT) {
            attachmentDao.deleteChunks(messageId)
            attachmentDao.delete(messageId)
        }
        messageDao.deleteLocally(messageId)
    }

    suspend fun searchMessages(conversationId: String, query: String): List<MessageEntity> =
        withContext(Dispatchers.IO) { messageDao.searchInConversation(conversationId, query) }

    suspend fun searchConversations(query: String): List<ConversationEntity> =
        withContext(Dispatchers.IO) { conversationDao.search(query) }

    private suspend fun directConversation(
        nexId: String,
        displayName: String,
        avatar: String?,
    ): ConversationEntity {
        val existing = conversationDao.getByPeer(nexId)
        val now = System.currentTimeMillis()
        if (existing != null) {
            if (existing.title != displayName && displayName.isNotBlank() && displayName != nexId) {
                val updated = existing.copy(title = displayName, avatar = avatar ?: existing.avatar, updatedAt = now)
                conversationDao.upsert(updated)
                return updated
            }
            return existing
        }
        return ConversationEntity(
            id = "dm:$nexId",
            type = "DM",
            peerNexId = nexId,
            groupId = null,
            title = displayName.ifBlank { nexId },
            avatar = avatar,
            lastMessagePreview = null,
            lastMessageAt = now,
            createdAt = now,
            updatedAt = now,
        ).also { conversationDao.upsert(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun attachmentBody(
        fileName: String,
        state: String,
        received: Int,
        total: Int,
    ): String = when (state) {
        AttachmentState.SENDING -> "📎 $fileName · sending…"
        AttachmentState.RECEIVING -> "📎 $fileName · receiving $received/$total"
        AttachmentState.READY -> "📎 $fileName · ready"
        AttachmentState.FAILED -> "📎 $fileName · transfer failed"
        else -> "📎 $fileName"
    }

    private fun scopeContentToConversation(
        conversation: ConversationEntity,
        content: SecureContent,
    ): SecureContent = content.copy(
        groupId = if (conversation.type == "GROUP") conversation.groupId else null,
    )

    private fun baseContent(kind: String, clientId: String, sender: SenderInfo): SecureContent =
        SecureContent(
            kind = kind,
            clientId = clientId,
            senderNexId = sender.nexId,
            senderUsername = sender.username,
            senderDisplayName = sender.displayName,
            senderAvatar = sender.avatar,
        )

    private fun parseReactions(raw: String): Map<String, List<String>> = runCatching {
        json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject.mapValues { (_, value) ->
            value.jsonArray.map { it.jsonPrimitive.content }
        }
    }.getOrDefault(emptyMap())

    private fun previewForMessage(text: String, group: Boolean): String {
        val clean = text.replace('\n', ' ').take(140)
        return clean
    }

    private fun friendlyError(error: Exception): String = when {
        error is ApiException && error.code == "PREKEYS_EXHAUSTED" ->
            "This contact has not published encryption keys. Ask them to reopen NEXCHAT."
        error is ApiException && (error.code == "NO_ACTIVE_DEVICE" || error.code == "NO_ACTIVE_DEVICES") ->
            "This contact is signed out or has not finished setting up NEXCHAT. Ask them to sign in and open the app."
        error is ApiException && error.code == "NO_KEY_BUNDLE" ->
            "This contact has no usable encryption keys yet. Ask them to reopen NEXCHAT."
        error is ApiException && error.code == "RECIPIENT_NOT_FOUND" -> "This account no longer exists"
        error is IdentityChangedException ->
            "Safety number changed. Verify it, then reset the security session from the contact screen."
        else -> error.message ?: "Message could not be encrypted"
    }

    private fun skipReason(reason: String): String = when (reason) {
        "RECIPIENT_NOT_FOUND" -> "The recipient account no longer exists"
        "DEVICE_NOT_ACTIVE" -> "The recipient device is no longer active"
        "PREKEYS_EXHAUSTED" -> "The recipient must reopen NEXCHAT to replenish encryption keys"
        "NO_ACTIVE_DEVICE", "NO_ACTIVE_DEVICES" -> "The recipient is signed out or has not finished setting up NEXCHAT"
        "NO_KEY_BUNDLE" -> "The recipient has no usable encryption keys yet"
        "NOT_A_GROUP_MEMBER" -> "You are no longer a member of this group"
        "RECIPIENT_NOT_IN_GROUP" -> "The recipient is no longer in this group"
        "EPHEMERAL_TARGET_OFFLINE" -> "The recipient is offline"
        else -> "The message could not be delivered ($reason)"
    }

    private data class SenderInfo(
        val nexId: String,
        val username: String,
        val displayName: String,
        val avatar: String?,
    )

    private fun com.nexchat.app.core.db.ProfileEntity.toSenderInfo(): SenderInfo =
        SenderInfo(nexId, username, displayName, avatar)
}

private val reactionsSerializer = MapSerializer(
    String.serializer(),
    ListSerializer(String.serializer()),
)

private const val DEVICE_CACHE_MILLIS = 60_000L
private const val PRE_KEY_CHECK_INTERVAL_MILLIS = 60_000L
private const val MAX_SEND_BATCH = 100
private const val MAX_CALL_SIGNAL_BATCH = 60
private const val RETRY_BASE_MILLIS = 5_000L
private const val RETRY_MAX_MILLIS = 15L * 60 * 1000
private const val MAX_ENVELOPE_TTL_SECONDS = 7L * 24 * 3600
private const val MESSAGE_TTL_MILLIS = MAX_ENVELOPE_TTL_SECONDS * 1000L
// A call offer is only useful while the caller is still ringing. Keeping it
// alive for minutes produced delayed rings and "Accept" on an ended call.
private const val CALL_SIGNAL_TTL_MILLIS = 45L * 1000
private const val TERMINAL_CALL_SIGNAL_TTL_MILLIS = 60L * 1000
private const val CALL_RETRY_BASE_MILLIS = 750L
private const val CALL_RETRY_MAX_MILLIS = 8_000L
private const val LONG_POLL_TIMEOUT_MILLIS = 20_000
// 90 KiB raw is ~120 KiB as base64. Together with JSON and Signal overhead it
// stays safely below the relay's 131,072-byte ciphertext limit.
private const val ATTACHMENT_CHUNK_BYTES = 90 * 1024
const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
/** Pre-manifest chunks are buffered only long enough for a plausible relay delay. */
const val ATTACHMENT_BUFFER_TTL_MILLIS = 10L * 60 * 1000
/** Enough room for one maximum-size attachment plus relay overhead. */
const val MAX_BUFFERED_ATTACHMENT_CHUNKS = 160
private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

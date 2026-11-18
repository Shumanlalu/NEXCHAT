package com.nexchat.app.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.nexchat.app.core.crypto.ContentKind
import com.nexchat.app.core.crypto.CryptoException
import com.nexchat.app.core.crypto.DuplicateEnvelopeException
import com.nexchat.app.core.crypto.GroupMemberInfo
import com.nexchat.app.core.crypto.IdentityChangedException
import com.nexchat.app.core.crypto.SecureContent
import com.nexchat.app.core.crypto.SignalCryptoManager
import com.nexchat.app.core.db.CallHistoryEntity
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.core.db.ConversationEntity
import com.nexchat.app.core.db.GroupEntity
import com.nexchat.app.core.db.GroupMemberEntity
import com.nexchat.app.core.db.MessageEntity
import com.nexchat.app.core.db.MessageKind
import com.nexchat.app.core.db.MessageStatus
import com.nexchat.app.core.db.NexDatabase
import com.nexchat.app.core.db.OutboxEntity
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
import com.nexchat.app.core.settings.SettingsStore
import java.io.IOException
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
    ) : CallSignalEvent
    data class Answer(
        override val conversationId: String,
        override val fromNexId: String,
        override val fromSignalDeviceId: Int,
        override val callId: String,
        override val sentAt: Long,
        val sdp: String,
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

class ChatRepository(
    private val db: NexDatabase,
    private val api: ApiClient,
    private val crypto: SignalCryptoManager,
    private val settings: SettingsStore,
) {
    private val profileDao = db.profileDao()
    private val contactDao = db.contactDao()
    private val conversationDao = db.conversationDao()
    private val messageDao = db.messageDao()
    private val groupDao = db.groupDao()
    private val outboxDao = db.outboxDao()
    private val processedDao = db.processedEnvelopeDao()
    private val callHistoryDao = db.callHistoryDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private val sendMutex = Mutex()
    private val receiveMutex = Mutex()
    private val replenishMutex = Mutex()
    @Volatile private var lastPreKeyCheckAt = 0L
    @Volatile private var staleOutboxCleared = false
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
            messageDao.upsert(message)
            conversationDao.touch(
                conversationId,
                previewForMessage(trimmed, conversation.type == "GROUP"),
                now,
            )
            val content = baseContent(ContentKind.TEXT, clientId, profile.toSenderInfo()).copy(
                text = trimmed,
                replyToId = replyTo?.id,
                replyPreview = replyTo?.body?.take(160),
            )
            try {
                val targets = resolveConversationTargets(conversation)
                if (targets.isEmpty()) throw CryptoException("No active encrypted device is available for this conversation")
                enqueue(conversation, content, targets)
                flushOutbox()
            } catch (error: IdentityChangedException) {
                conversation.peerNexId?.let { contactDao.setVerified(it, null) }
                messageDao.setError(clientId, friendlyError(error))
                messageDao.updateStatus(clientId, MessageStatus.FAILED, System.currentTimeMillis())
                throw error
            } catch (error: Exception) {
                messageDao.setError(clientId, friendlyError(error))
                messageDao.updateStatus(clientId, MessageStatus.FAILED, System.currentTimeMillis())
                throw error
            }
        }

    suspend fun editMessage(conversationId: String, messageId: String, text: String) =
        withContext(Dispatchers.IO) {
            val message = messageDao.get(messageId) ?: return@withContext
            if (!message.outgoing) return@withContext
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext
            val now = System.currentTimeMillis()
            messageDao.applyEdit(messageId, trimmed, now)
            val conversation = conversationDao.get(conversationId) ?: return@withContext
            val profile = profileDao.get() ?: return@withContext
            val content = baseContent(ContentKind.EDIT, UUID.randomUUID().toString(), profile.toSenderInfo()).copy(
                targetClientId = messageId,
                text = trimmed,
            )
            sendControl(conversation, content)
        }

    suspend fun deleteMessage(conversationId: String, messageId: String) = withContext(Dispatchers.IO) {
        val message = messageDao.get(messageId) ?: return@withContext
        if (!message.outgoing) {
            messageDao.deleteLocally(messageId)
            return@withContext
        }
        val now = System.currentTimeMillis()
        messageDao.applyDeleteForEveryone(messageId, now)
        val conversation = conversationDao.get(conversationId) ?: return@withContext
        val profile = profileDao.get() ?: return@withContext
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
        runCatching { sendEphemeral(conversation, content) }
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
                senderNexId = profile.nexId,
                senderUsername = profile.username,
                senderDisplayName = profile.displayName,
                senderAvatar = profile.avatar,
            )
            if (durable) sendControl(conversation, filled) else runCatching { sendEphemeral(conversation, filled) }
        }

    /**
     * NEXCHAT sends online only: every queued envelope gets exactly one delivery
     * attempt. If the relay cannot be reached, the local rows are deleted and
     * the message is marked failed instead of being retried in the background.
     * The encrypted server queue is untouched, so a recipient who was already
     * online still receives accepted ciphertext.
     */
    suspend fun flushOutbox(): Boolean = withContext(Dispatchers.IO) {
        // Wait for another send instead of skipping this one. A skipped flush
        // would leave ciphertext in the outbox with no retry to pick it up.
        sendMutex.withLock {
            val pending = outboxDao.due(System.currentTimeMillis(), 100)
            if (pending.isEmpty()) return@withLock true
            val now = System.currentTimeMillis()
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
            } catch (error: Throwable) {
                withContext(NonCancellable) { discardOutbox(pending, error) }
                if (error is CancellationException || error is SessionExpiredException) throw error
                throw ApiException(0, "SEND_FAILED_ONLINE_ONLY", sendFailureMessage(error))
            }
            for (accepted in response.accepted) {
                val row = pending.firstOrNull {
                    it.clientId == accepted.clientId &&
                        it.recipientSignalDeviceId == accepted.recipientSignalDeviceId
                }
                if (row != null) {
                    outboxDao.delete(row.id)
                }
            }
            for (accepted in response.accepted.distinctBy { it.clientId }) {
                if (outboxDao.countForClient(accepted.clientId) == 0) {
                    messageDao.markSent(accepted.clientId)
                }
            }
            var rejected: ApiException? = null
            if (response.skipped.isNotEmpty()) {
                val acceptedClients = response.accepted.map { it.clientId }.toSet()
                for (skipped in response.skipped.distinctBy { it.clientId }) {
                    if (skipped.clientId in acceptedClients) continue
                    if (skipped.reason == "DEVICE_NOT_ACTIVE") {
                        outboxDao.forClient(skipped.clientId)
                            .map { it.recipientNexId }
                            .distinct()
                            .forEach { invalidateDeviceCache(it) }
                    }
                    outboxDao.deleteForClient(skipped.clientId)
                    messageDao.updateStatus(skipped.clientId, MessageStatus.FAILED, System.currentTimeMillis())
                    messageDao.setError(skipped.clientId, skipReason(skipped.reason))
                    if (rejected == null) {
                        rejected = ApiException(0, skipped.reason, skipReason(skipped.reason))
                    }
                }
            }
            // Every row must be confirmed by the relay. If a response omitted
            // one, treat it as failed rather than leaving it for a later retry.
            val acceptedKeys = response.accepted
                .map { it.clientId to it.recipientSignalDeviceId }
                .toSet()
            val skippedClients = response.skipped.map { it.clientId }.toSet()
            val unconfirmed = pending.filter {
                it.clientId !in skippedClients &&
                    (it.clientId to it.recipientSignalDeviceId) !in acceptedKeys
            }
            if (unconfirmed.isNotEmpty()) {
                discardOutbox(unconfirmed, null)
            }
            rejected?.let { throw it }
            true
        }
    }

    /**
     * Clears local outbox rows left behind by an older build, a crash, or a
     * killed process. Online-only sending never resumes a send automatically.
     */
    suspend fun abandonQueuedSends() = withContext(Dispatchers.IO) {
        if (staleOutboxCleared) return@withContext
        sendMutex.withLock {
            if (staleOutboxCleared) return@withLock
            // Only old rows are crash leftovers. A row enqueued by a send that
            // is still starting must never be mistaken for one.
            val pending = outboxDao.due(
                System.currentTimeMillis() - STALE_OUTBOX_MILLIS,
                500,
            )
            if (pending.isNotEmpty()) discardOutbox(pending, null)
            staleOutboxCleared = true
        }
    }

    private suspend fun discardOutbox(rows: List<OutboxEntity>, error: Throwable?) {
        for (row in rows) {
            outboxDao.delete(row.id)
        }
        val now = System.currentTimeMillis()
        val message = sendFailureMessage(error)
        for (clientId in rows.map { it.clientId }.distinct()) {
            if (outboxDao.countForClient(clientId) == 0) {
                messageDao.updateStatus(clientId, MessageStatus.FAILED, now)
                messageDao.setError(clientId, message)
            }
        }
    }

    private fun sendFailureMessage(error: Throwable?): String = when {
        error is SessionExpiredException -> error.message ?: "Your session expired. Sign in again."
        error is ApiException && error.code == "EPHEMERAL_TARGET_OFFLINE" ->
            "The recipient is offline. NEXCHAT only delivers while both of you are online."
        error is ApiException -> error.message ?: "The server rejected the message"
        error is IOException -> "You appear to be offline. Connect to the internet and send again."
        else -> "The message could not be sent. Check your connection and try again."
    }

    suspend fun onConnected() = withContext(Dispatchers.IO) {
        runCatching { syncGroups() }
        runCatching { abandonQueuedSends() }
        runCatching { receivePending() }
        runCatching { replenishPreKeysIfNeeded() }
    }

    /**
     * Fallback sync used while the socket is connected. Serverless relays can
     * place a sender and recipient on different instances, so a realtime push
     * is best-effort. This ensures queued ciphertext is still fetched.
     */
    suspend fun pollPending() = withContext(Dispatchers.IO) {
        runCatching { receivePending() }
        // Replenish even while the socket stays open. A recipient that remains
        // connected would otherwise never publish new one-time prekeys.
        runCatching { replenishPreKeysIfNeeded() }
    }

    /**
     * Fetch queued ciphertext without syncing anything else. Call signaling
     * uses this at a short interval because a serverless relay may not push a
     * WebSocket event to the function instance that holds the recipient socket.
     */
    suspend fun pollIncoming() = withContext(Dispatchers.IO) {
        receivePending()
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
        val response = api.pendingEnvelopes()
        val ackIds = mutableListOf<String>()
        for (envelope in response.messages) {
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
            ContentKind.TEXT -> handleIncomingText(envelope, content, senderNexId)
            ContentKind.EDIT -> content.targetClientId?.let { messageDao.applyEdit(it, content.text.orEmpty(), System.currentTimeMillis()) }
            ContentKind.DELETE -> content.targetClientId?.let {
                messageDao.applyDeleteForEveryone(it, System.currentTimeMillis())
            }
            ContentKind.REACTION -> handleReaction(content, senderNexId)
            ContentKind.READ -> {
                if (settings.state.value.readReceipts) {
                    val ids = content.readClientIds
                    if (ids.isNotEmpty()) messageDao.markRead(ids, System.currentTimeMillis())
                }
            }
            ContentKind.TYPING -> {
                val conversationId = conversationIdFor(envelope)
                _events.tryEmit(
                    ChatEvent.Typing(
                        TypingEvent(conversationId, senderNexId, content.text == "1"),
                    ),
                )
            }
            ContentKind.GROUP_META -> handleGroupMeta(content, senderNexId)
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
        val message = MessageEntity(
            id = messageId,
            conversationId = conversation.id,
            senderNexId = senderNexId,
            senderDisplayName = content.senderDisplayName ?: contact?.displayName ?: senderNexId,
            outgoing = false,
            body = body,
            status = MessageStatus.DELIVERED,
            sentAt = now,
            receivedAt = now,
            replyToId = content.replyToId,
            replyToPreview = content.replyPreview,
        )
        val inserted = messageDao.insertIgnore(message)
        if (inserted == -1L) return
        conversationDao.touch(
            conversation.id,
            previewForMessage(body, conversation.type == "GROUP"),
            now,
        )
        if (_activeConversation.value != conversation.id) {
            conversationDao.incrementUnread(conversation.id, now)
        }
        _events.tryEmit(ChatEvent.Incoming(IncomingMessageEvent(conversation, message)))
    }

    private suspend fun ensureConversationFor(
        envelope: EnvelopeDto,
        content: SecureContent,
        senderNexId: String,
    ): ConversationEntity {
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

    private suspend fun handleReaction(content: SecureContent, senderNexId: String) {
        val target = content.targetClientId ?: return
        val emoji = content.emoji ?: return
        val existing = messageDao.get(target) ?: return
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

    private suspend fun handleGroupMeta(content: SecureContent, senderNexId: String) {
        val groupId = content.groupId ?: return
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
        val targets = resolveConversationTargets(conversation)
        if (targets.isEmpty()) throw CryptoException("No active encrypted device is available")
        enqueue(conversation, content, targets)
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
        for (device in current.filter { it.signalDeviceId in known }) {
            // The server device list is authoritative. If the public identity key
            // no longer matches the key bound to our session, stop before sending
            // and require the user to verify the contact's new safety number.
            val trusted = crypto.peerIdentity(nexId, device.signalDeviceId)
            if (trusted != null && trusted != device.identityKey) {
                throw IdentityChangedException(nexId)
            }
            targets += Target(nexId, device.signalDeviceId, null)
        }
        val missing = current.filterNot { it.signalDeviceId in known }
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

    private suspend fun enqueue(conversation: ConversationEntity, content: SecureContent, targets: List<Target>) {
        val encrypted = mutableListOf<OutboxEntity>()
        val now = System.currentTimeMillis()
        // A call signal is only useful while the call is ringing. Do not let a
        // queued offer or ICE candidate survive for days on the relay.
        val ttlMillis = when (content.kind) {
            ContentKind.CALL_OFFER,
            ContentKind.CALL_ANSWER,
            ContentKind.CALL_ICE,
            ContentKind.CALL_HANGUP,
            ContentKind.CALL_REJECT,
            -> CALL_SIGNAL_TTL_MILLIS
            else -> MESSAGE_TTL_MILLIS
        }
        for (target in targets) {
            val payload = crypto.encrypt(content, target.nexId, target.signalDeviceId, target.bundle)
            encrypted += OutboxEntity(
                id = UUID.randomUUID().toString(),
                clientId = content.clientId ?: UUID.randomUUID().toString(),
                conversationId = conversation.id,
                recipientNexId = target.nexId,
                recipientSignalDeviceId = target.signalDeviceId,
                groupId = content.groupId,
                cipherType = payload.cipherType,
                ciphertext = payload.ciphertext,
                ephemeral = false,
                createdAt = now,
                expiresAt = now + ttlMillis,
            )
        }
        encrypted.forEach { outboxDao.upsert(it) }
    }

    private suspend fun encryptAndSendEphemeral(
        conversation: ConversationEntity,
        content: SecureContent,
        targets: List<Target>,
    ) {
        val messages = targets.mapNotNull { target ->
            runCatching {
                val payload = crypto.encrypt(content, target.nexId, target.signalDeviceId, target.bundle)
                OutgoingEnvelope(
                    recipientNexId = target.nexId,
                    recipientSignalDeviceId = target.signalDeviceId,
                    clientId = content.clientId ?: UUID.randomUUID().toString(),
                    ciphertext = payload.ciphertext,
                    cipherType = payload.cipherType,
                    ephemeral = true,
                    expiresInSeconds = 120,
                    groupId = content.groupId,
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

    suspend fun markDelivered(clientId: String) = withContext(Dispatchers.IO) {
        messageDao.markDelivered(clientId, System.currentTimeMillis())
        _events.tryEmit(ChatEvent.Delivery(clientId))
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
        messageDao.deleteConversation(id)
        conversationDao.updateLastMessage(id, "", System.currentTimeMillis())
    }

    suspend fun deleteLocalMessage(messageId: String) = withContext(Dispatchers.IO) {
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
private const val STALE_OUTBOX_MILLIS = 60_000L
private const val MAX_ENVELOPE_TTL_SECONDS = 7L * 24 * 3600
private const val MESSAGE_TTL_MILLIS = MAX_ENVELOPE_TTL_SECONDS * 1000L
private const val CALL_SIGNAL_TTL_MILLIS = 3L * 60 * 1000

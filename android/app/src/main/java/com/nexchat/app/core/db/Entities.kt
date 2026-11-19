package com.nexchat.app.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val userId: String,
    val nexId: String,
    val username: String,
    val displayName: String,
    val avatar: String?,
    val deviceId: String,
    val signalDeviceId: Int,
    val registrationId: Int,
    val createdAt: Long,
)

@Entity(tableName = "contacts", indices = [Index(value = ["username"], unique = false)])
data class ContactEntity(
    @PrimaryKey val nexId: String,
    val userId: String?,
    val username: String,
    val displayName: String,
    val avatar: String?,
    val identityKey: String?,
    val verifiedAt: Long? = null,
    val blocked: Boolean = false,
    val createdAt: Long,
)

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["lastMessageAt"]), Index(value = ["peerNexId"]), Index(value = ["groupId"])],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val peerNexId: String?,
    val groupId: String?,
    val title: String,
    val avatar: String?,
    val lastMessagePreview: String?,
    val lastMessageAt: Long,
    val unreadCount: Int = 0,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val draft: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "sentAt"]),
        Index(value = ["senderNexId"]),
        Index(value = ["status"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderNexId: String,
    val senderDisplayName: String,
    val outgoing: Boolean,
    val body: String,
    val kind: String = MessageKind.TEXT,
    val status: String = MessageStatus.PENDING,
    val sentAt: Long,
    val receivedAt: Long? = null,
    val editedAt: Long? = null,
    val deletedForEveryone: Boolean = false,
    val deletedLocally: Boolean = false,
    val replyToId: String? = null,
    val replyToPreview: String? = null,
    val reactionsJson: String = "{}",
    val error: String? = null,
)

object MessageKind {
    const val TEXT = "TEXT"
    const val SYSTEM = "SYSTEM"
    const val CALL = "CALL"
    const val GROUP_META = "GROUP_META"
}

object MessageStatus {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val READ = "READ"
    const val FAILED = "FAILED"
}

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val title: String,
    val avatar: String?,
    val creatorNexId: String?,
    val myRole: String,
    val createdAt: Long,
    val updatedAt: Long,
    val metadataKnown: Boolean = false,
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "nexId"])
data class GroupMemberEntity(
    val groupId: String,
    val nexId: String,
    val username: String,
    val displayName: String,
    val avatar: String?,
    val role: String,
)

@Entity(tableName = "signal_state")
data class SignalStateEntity(
    @PrimaryKey val key: String,
    val value: ByteArray,
)

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["clientId"]),
        Index(value = ["recipientSignalDeviceId"]),
        Index(value = ["createdAt"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    val clientId: String,
    val conversationId: String,
    val recipientNexId: String,
    val recipientSignalDeviceId: Int,
    val groupId: String?,
    val cipherType: Int,
    val ciphertext: String,
    val ephemeral: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0,
    val lastError: String? = null,
)

/**
 * A durable, SQLCipher-encrypted send intent. Ciphertext for a target device
 * can only be produced once the recipient's current bundle is reachable, so
 * the sealed content is kept here until encryption and relay acceptance have
 * both succeeded.
 */
@Entity(
    tableName = "pending_intents",
    indices = [
        Index(value = ["clientId"]),
        Index(value = ["nextAttemptAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class PendingIntentEntity(
    @PrimaryKey val id: String,
    val clientId: String,
    val conversationId: String,
    val contentJson: String,
    val durable: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    /**
     * Set once ciphertext rows exist in the outbox. While this is non-null the
     * intent must not be encrypted again: those rows already advanced the
     * Signal ratchet and are retried from the outbox.
     */
    val materializedAt: Long? = null,
)

@Entity(
    tableName = "relay_packets",
    indices = [
        Index(value = ["packetId"], unique = true),
        Index(value = ["expiresAt"]),
        Index(value = ["targetNexId"]),
    ],
)
data class RelayPacketEntity(
    @PrimaryKey val id: String,
    val packetId: String,
    val targetNexId: String,
    val targetSignalDeviceId: Int,
    val originNexId: String,
    val originSignalDeviceId: Int,
    val groupId: String?,
    val cipherType: Int,
    val ciphertext: String,
    val createdAt: Long,
    val expiresAt: Long,
    val hopCount: Int,
    val maxHops: Int,
    val receivedAt: Long,
)

@Entity(tableName = "processed_envelopes")
data class ProcessedEnvelopeEntity(
    @PrimaryKey val envelopeId: String,
    val processedAt: Long,
)

@Entity(tableName = "call_history")
data class CallHistoryEntity(
    @PrimaryKey val id: String,
    val peerNexId: String,
    val peerName: String,
    val outgoing: Boolean,
    val startedAt: Long,
    val endedAt: Long?,
    val connected: Boolean,
    val endReason: String?,
)

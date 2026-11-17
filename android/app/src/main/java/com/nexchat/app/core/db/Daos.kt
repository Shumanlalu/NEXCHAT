package com.nexchat.app.core.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun get(): ProfileEntity?

    @Query("SELECT * FROM profile WHERE id = 1")
    fun observe(): Flow<ProfileEntity?>

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profile")
    suspend fun delete()
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE blocked = 1 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeBlocked(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE blocked = 1 ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getBlocked(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE nexId = :nexId")
    suspend fun get(nexId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE nexId = :nexId")
    fun observe(nexId: String): Flow<ContactEntity?>

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET blocked = :blocked WHERE nexId = :nexId")
    suspend fun setBlocked(nexId: String, blocked: Boolean)

    @Query("UPDATE contacts SET verifiedAt = :verifiedAt WHERE nexId = :nexId")
    suspend fun setVerified(nexId: String, verifiedAt: Long?)

    @Query("UPDATE contacts SET identityKey = :identityKey WHERE nexId = :nexId")
    suspend fun setIdentityKey(nexId: String, identityKey: String?)

    @Query("DELETE FROM contacts WHERE nexId = :nexId")
    suspend fun delete(nexId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE archived = :archived ORDER BY pinned DESC, lastMessageAt DESC")
    fun observe(archived: Boolean): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeOne(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE peerNexId = :nexId LIMIT 1")
    suspend fun getByPeer(nexId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE groupId = :groupId LIMIT 1")
    suspend fun getByGroup(groupId: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """UPDATE conversations SET lastMessagePreview = :preview, lastMessageAt = :at,
           updatedAt = :at WHERE id = :id""",
    )
    suspend fun updateLastMessage(id: String, preview: String, at: Long)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1, updatedAt = :at WHERE id = :id")
    suspend fun incrementUnread(id: String, at: Long)

    @Query("UPDATE conversations SET unreadCount = 0, updatedAt = :at WHERE id = :id")
    suspend fun clearUnread(id: String, at: Long)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE conversations SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean)

    @Query("UPDATE conversations SET pinned = :pinned, updatedAt = :at WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, at: Long)

    @Query("UPDATE conversations SET lastMessagePreview = :preview, lastMessageAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, preview: String, at: Long)

    @Query("UPDATE conversations SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: String, draft: String)

    @Query(
        """SELECT * FROM conversations
           WHERE title LIKE '%' || :query || '%'
              OR lastMessagePreview LIKE '%' || :query || '%'
           ORDER BY lastMessageAt DESC LIMIT 100""",
    )
    suspend fun search(query: String): List<ConversationEntity>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt DESC, id DESC")
    fun pagingSource(conversationId: String): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt DESC LIMIT 1")
    fun observeLatest(conversationId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query(
        """UPDATE messages SET status = :status,
           receivedAt = CASE WHEN receivedAt IS NULL THEN :at ELSE receivedAt END
           WHERE id = :id""",
    )
    suspend fun updateStatus(id: String, status: String, at: Long)

    @Query("UPDATE messages SET status = :status WHERE id IN (:ids)")
    suspend fun updateStatuses(ids: List<String>, status: String)

    @Query("UPDATE messages SET status = 'SENT' WHERE id = :id AND status = 'PENDING'")
    suspend fun markSent(id: String)

    @Query("UPDATE messages SET status = 'DELIVERED', receivedAt = COALESCE(receivedAt, :at) WHERE id = :id AND status IN ('PENDING','SENT')")
    suspend fun markDelivered(id: String, at: Long)

    @Query("UPDATE messages SET status = 'READ', receivedAt = COALESCE(receivedAt, :at) WHERE id IN (:ids) AND status != 'READ'")
    suspend fun markRead(ids: List<String>, at: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND outgoing = 1 AND status IN ('SENT','DELIVERED') ORDER BY sentAt DESC LIMIT :limit")
    suspend fun unreadOutgoing(conversationId: String, limit: Int = 100): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt DESC LIMIT :limit")
    suspend fun latest(conversationId: String, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET body = :body, editedAt = :at WHERE id = :id")
    suspend fun applyEdit(id: String, body: String, at: Long)

    @Query("UPDATE messages SET deletedForEveryone = 1, body = '', editedAt = :at WHERE id = :id")
    suspend fun applyDeleteForEveryone(id: String, at: Long)

    @Query("UPDATE messages SET deletedLocally = 1 WHERE id = :id")
    suspend fun deleteLocally(id: String)

    @Query("UPDATE messages SET reactionsJson = :json WHERE id = :id")
    suspend fun setReactions(id: String, json: String)

    @Query("UPDATE messages SET error = :error WHERE id = :id")
    suspend fun setError(id: String, error: String?)

    @Query(
        """SELECT * FROM messages WHERE conversationId = :conversationId
           AND body LIKE '%' || :query || '%' ORDER BY sentAt DESC LIMIT 100""",
    )
    suspend fun searchInConversation(conversationId: String, query: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt ASC")
    suspend fun allForConversation(conversationId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun get(id: String): GroupEntity?

    @Upsert
    suspend fun upsert(group: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY displayName COLLATE NOCASE")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY displayName COLLATE NOCASE")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY displayName COLLATE NOCASE")
    fun observeMembersById(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM groups ORDER BY updatedAt DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups")
    suspend fun getAll(): List<GroupEntity>

    @Upsert
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND nexId NOT IN (:keepNexIds)")
    suspend fun deleteMembersNotIn(groupId: String, keepNexIds: List<String>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteAllMembers(groupId: String)

    @Query("DELETE FROM groups")
    suspend fun deleteAll()

    @Query("DELETE FROM group_members")
    suspend fun deleteAllMembersEverywhere()
}

@Dao
interface SignalStateDao {
    @Query("SELECT * FROM signal_state")
    suspend fun loadAll(): List<SignalStateEntity>

    @Query("SELECT * FROM signal_state")
    fun loadAllBlocking(): List<SignalStateEntity>

    @Upsert
    suspend fun upsert(state: SignalStateEntity)

    @Upsert
    fun upsertBlocking(state: SignalStateEntity)

    @Query("DELETE FROM signal_state WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM signal_state WHERE `key` = :key")
    fun deleteBlocking(key: String)

    @Query("DELETE FROM signal_state")
    suspend fun deleteAll()
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE createdAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE clientId = :clientId")
    suspend fun forClient(clientId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE recipientSignalDeviceId = :deviceId ORDER BY createdAt ASC")
    suspend fun forDevice(deviceId: Int): List<OutboxEntity>

    @Upsert
    suspend fun upsert(item: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastAttemptAt = :at WHERE id = :id")
    suspend fun markAttempt(id: String, at: Long)

    @Query("DELETE FROM outbox WHERE clientId = :clientId")
    suspend fun deleteForClient(clientId: String)

    @Query("SELECT DISTINCT clientId FROM outbox WHERE recipientNexId = :nexId")
    suspend fun clientIdsForRecipient(nexId: String): List<String>

    @Query("DELETE FROM outbox WHERE recipientNexId = :nexId")
    suspend fun deleteForRecipient(nexId: String)

    @Query("DELETE FROM outbox WHERE clientId = :clientId AND recipientSignalDeviceId = :signalDeviceId")
    suspend fun deleteForClientAndDevice(clientId: String, signalDeviceId: Int)

    @Query("SELECT COUNT(*) FROM outbox WHERE clientId = :clientId")
    suspend fun countForClient(clientId: String): Int

    @Query("DELETE FROM outbox WHERE createdAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int
}

@Dao
interface RelayDao {
    @Query("SELECT * FROM relay_packets WHERE expiresAt > :now ORDER BY receivedAt ASC LIMIT :limit")
    suspend fun active(now: Long, limit: Int = 200): List<RelayPacketEntity>

    @Query("SELECT * FROM relay_packets WHERE targetNexId = :nexId AND targetSignalDeviceId = :signalDeviceId AND expiresAt > :now")
    suspend fun forDestination(nexId: String, signalDeviceId: Int, now: Long): List<RelayPacketEntity>

    @Query("SELECT * FROM relay_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun findByPacketId(packetId: String): RelayPacketEntity?

    @Upsert
    suspend fun upsert(packet: RelayPacketEntity)

    @Query("DELETE FROM relay_packets WHERE packetId = :packetId")
    suspend fun deleteByPacketId(packetId: String)

    @Query("DELETE FROM relay_packets WHERE expiresAt <= :now OR hopCount >= maxHops")
    suspend fun purgeExpired(now: Long)

    @Query("SELECT COUNT(*) FROM relay_packets WHERE expiresAt > :now")
    suspend fun activeCount(now: Long): Int

    @Query("DELETE FROM relay_packets")
    suspend fun deleteAll()
}

@Dao
interface ProcessedEnvelopeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(processed: ProcessedEnvelopeEntity): Long

    @Query("SELECT * FROM processed_envelopes WHERE envelopeId = :id")
    suspend fun get(id: String): ProcessedEnvelopeEntity?

    @Query("DELETE FROM processed_envelopes WHERE processedAt < :cutoff")
    suspend fun purge(cutoff: Long)

    @Query("DELETE FROM processed_envelopes")
    suspend fun deleteAll()
}

@Dao
interface CallHistoryDao {
    @Upsert
    suspend fun upsert(call: CallHistoryEntity)

    @Query("SELECT * FROM call_history ORDER BY startedAt DESC LIMIT 100")
    fun observeAll(): Flow<List<CallHistoryEntity>>

    @Query("DELETE FROM call_history")
    suspend fun deleteAll()
}

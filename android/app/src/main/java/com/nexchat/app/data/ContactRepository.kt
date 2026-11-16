package com.nexchat.app.data

import com.nexchat.app.core.crypto.SignalCryptoManager
import com.nexchat.app.core.db.ContactEntity
import com.nexchat.app.core.db.NexDatabase
import com.nexchat.app.core.network.ApiClient
import com.nexchat.app.core.network.AuthUser
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class SafetyNumber(
    val deviceId: Int,
    val fingerprint: String,
    val verified: Boolean,
)

class ContactRepository(
    private val db: NexDatabase,
    private val api: ApiClient,
    private val crypto: SignalCryptoManager,
) {
    private val contactDao = db.contactDao()
    private val profileDao = db.profileDao()

    fun contacts(): Flow<List<ContactEntity>> = contactDao.observeAll()

    fun contact(nexId: String): Flow<ContactEntity?> = contactDao.observe(nexId)

    fun blocked(): Flow<List<ContactEntity>> = contactDao.observeBlocked()

    suspend fun get(nexId: String): ContactEntity? = withContext(Dispatchers.IO) { contactDao.get(nexId) }

    suspend fun search(query: String): List<AuthUser> = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.length < 2) return@withContext emptyList()
        api.searchUsers(cleaned).users
    }

    suspend fun add(user: AuthUser): ContactEntity = withContext(Dispatchers.IO) {
        val existing = contactDao.get(user.nexId)
        val contact = ContactEntity(
            nexId = user.nexId,
            userId = user.id,
            username = user.username,
            displayName = user.displayName,
            avatar = user.avatar,
            identityKey = existing?.identityKey,
            verifiedAt = existing?.verifiedAt,
            blocked = existing?.blocked ?: false,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        contactDao.upsert(contact)
        contact
    }

    suspend fun setBlocked(nexId: String, blocked: Boolean) = withContext(Dispatchers.IO) {
        contactDao.setBlocked(nexId, blocked)
    }

    suspend fun report(nexId: String, reason: String) = withContext(Dispatchers.IO) {
        api.report(nexId, reason)
    }

    suspend fun setVerified(nexId: String, verified: Boolean) = withContext(Dispatchers.IO) {
        contactDao.setVerified(nexId, if (verified) System.currentTimeMillis() else null)
    }

    suspend fun delete(nexId: String) = withContext(Dispatchers.IO) { contactDao.delete(nexId) }

    suspend fun presence(nexId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { api.presence(nexId).online }.getOrDefault(false)
    }

    suspend fun safetyNumbers(nexId: String): List<SafetyNumber> = withContext(Dispatchers.IO) {
        val contact = contactDao.get(nexId)
        val localNexId = profileDao.get()?.nexId
            ?: throw IllegalStateException("Your local profile is missing")
        val deviceIds = crypto.knownDeviceIds(nexId)
        val numbers = mutableListOf<SafetyNumber>()
        for (deviceId in deviceIds) {
            val identity = crypto.peerIdentity(nexId, deviceId) ?: continue
            val fingerprint = crypto.safetyNumber(localNexId, nexId, identity) ?: continue
            numbers += SafetyNumber(
                deviceId = deviceId,
                fingerprint = formatFingerprint(fingerprint),
                verified = contact?.verifiedAt != null,
            )
        }
        numbers
    }

    suspend fun savePeerIdentity(nexId: String, identityKey: String?) = withContext(Dispatchers.IO) {
        contactDao.setIdentityKey(nexId, identityKey)
    }

    fun avatarInitials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return "?"
        if (parts.size == 1) return parts.first().take(2).uppercase()
        return (parts.first().first().toString() + parts.last().first().toString()).uppercase()
    }

    fun decodeAvatar(avatar: String?): ByteArray? {
        if (avatar.isNullOrBlank()) return null
        val payload = if (avatar.startsWith("data:")) avatar.substringAfter(',' ) else avatar
        return runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
    }

    private fun formatFingerprint(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return raw
        return digits.chunked(5).take(12).joinToString(" ")
    }
}

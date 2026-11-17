package com.nexchat.app.core.crypto

import android.util.Base64
import com.nexchat.app.core.db.NexDatabase
import com.nexchat.app.core.db.SignalStateEntity
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.ecc.ECPublicKey

data class UploadKeyBatch(
    val signedPreKeyId: Int,
    val signedPreKeyPublic: String,
    val signedPreKeySignature: String,
    val kyberPreKeyId: Int,
    val kyberPreKeyPublic: String,
    val kyberPreKeySignature: String,
    val oneTimePreKeys: List<Pair<Int, String>>,
)

/**
 * SignalProtocolStore persisted inside the SQLCipher database. Key material is
 * loaded into memory only while the process is alive; every mutation is written
 * through to encrypted storage. Record formats are produced by libsignal itself,
 * not by NEXCHAT.
 */
class NexSignalStore(private val db: NexDatabase) : SignalProtocolStore {
    private val dao = db.signalStateDao()
    private val random = SecureRandom()
    private val lock = Any()

    private var identityKeyPair: IdentityKeyPair? = null
    private var localRegistrationId: Int = 0
    private var localDeviceId: String? = null
    private var localSignalDeviceId: Int = 0
    private val preKeys = linkedMapOf<Int, PreKeyRecord>()
    private val signedPreKeys = linkedMapOf<Int, SignedPreKeyRecord>()
    private val kyberPreKeys = linkedMapOf<Int, KyberPreKeyRecord>()
    private val sessions = linkedMapOf<String, SessionRecord>()
    private val trustedIdentities = linkedMapOf<String, IdentityKey>()
    private val usedKyberBaseKeys = mutableSetOf<String>()
    private var initialized = false

    suspend fun initialize() {
        synchronized(lock) {
            if (initialized) return
            val rows = dao.loadAllBlocking()
            for (row in rows) {
                when {
                    row.key == KEY_IDENTITY -> identityKeyPair = IdentityKeyPair(row.value)
                    row.key == KEY_REGISTRATION_ID -> localRegistrationId =
                        ByteBuffer.wrap(row.value).int
                    row.key == KEY_DEVICE_ID -> localDeviceId = String(row.value, Charsets.UTF_8)
                    row.key == KEY_SIGNAL_DEVICE_ID -> localSignalDeviceId =
                        ByteBuffer.wrap(row.value).int
                    row.key.startsWith(PREFIX_PREKEY) -> {
                        val record = PreKeyRecord(row.value)
                        preKeys[record.id] = record
                    }
                    row.key.startsWith(PREFIX_SIGNED) -> {
                        val record = SignedPreKeyRecord(row.value)
                        signedPreKeys[record.id] = record
                    }
                    row.key.startsWith(PREFIX_KYBER) -> {
                        val record = KyberPreKeyRecord(row.value)
                        kyberPreKeys[record.id] = record
                    }
                    row.key.startsWith(PREFIX_SESSION) -> {
                        val address = row.key.removePrefix(PREFIX_SESSION)
                        sessions[address] = SessionRecord(row.value)
                    }
                    row.key.startsWith(PREFIX_IDENTITY_PEER) -> {
                        val address = row.key.removePrefix(PREFIX_IDENTITY_PEER)
                        trustedIdentities[address] = IdentityKey(row.value)
                    }
                    row.key.startsWith(PREFIX_KYBER_USED) -> {
                        usedKyberBaseKeys += row.key.removePrefix(PREFIX_KYBER_USED)
                    }
                }
            }
            initialized = true
        }
    }

    fun isInitialized(): Boolean = synchronized(lock) { initialized && identityKeyPair != null }

    fun identityPublicBase64(): String = synchronized(lock) {
        requireNotNull(identityKeyPair) { "Signal identity is not initialized" }
        Base64.encodeToString(identityKeyPair!!.publicKey.serialize(), Base64.NO_WRAP)
    }

    fun pendingKeyMaterial(): UploadKeyBatch = synchronized(lock) {
        requireNotNull(identityKeyPair) { "Signal identity is not initialized" }
        generateAndStoreBatch()
    }

    data class LocalDeviceIdentity(
        val deviceId: String,
        val signalDeviceId: Int,
        val registrationId: Int,
    )

    suspend fun ensureLocalDeviceIdentity(): LocalDeviceIdentity {
        initialize()
        synchronized(lock) {
            if (identityKeyPair == null) {
                identityKeyPair = IdentityKeyPair.generate()
                localRegistrationId = random.nextInt(16_380) + 1
                persist(KEY_IDENTITY, identityKeyPair!!.serialize())
                persist(KEY_REGISTRATION_ID, ByteBuffer.allocate(4).putInt(localRegistrationId).array())
            }
            if (localDeviceId == null) {
                localDeviceId = UUID.randomUUID().toString()
                localSignalDeviceId = random.nextInt(Int.MAX_VALUE - 1) + 1
                persist(KEY_DEVICE_ID, localDeviceId!!.toByteArray(Charsets.UTF_8))
                persist(
                    KEY_SIGNAL_DEVICE_ID,
                    ByteBuffer.allocate(4).putInt(localSignalDeviceId).array(),
                )
            }
            return LocalDeviceIdentity(
                deviceId = requireNotNull(localDeviceId),
                signalDeviceId = localSignalDeviceId,
                registrationId = localRegistrationId,
            )
        }
    }

    suspend fun initializeIdentityIfNeeded(): UploadKeyBatch? {
        val identity = ensureLocalDeviceIdentity()
        synchronized(lock) {
            if (signedPreKeys.isNotEmpty() && kyberPreKeys.isNotEmpty() && preKeys.isNotEmpty()) {
                return null
            }
            return generateAndStoreBatch()
        }
    }

    @Synchronized
    fun hasSession(nexId: String, signalDeviceId: Int): Boolean = synchronized(lock) {
        sessions.containsKey(addressKey(SignalProtocolAddress(nexId, signalDeviceId)))
    }

    @Synchronized
    fun peerIdentityBase64(nexId: String, signalDeviceId: Int): String? = synchronized(lock) {
        val key = addressKey(SignalProtocolAddress(nexId, signalDeviceId))
        trustedIdentities[key]?.let { Base64.encodeToString(it.serialize(), Base64.NO_WRAP) }
    }

    @Synchronized
    fun knownDeviceIds(nexId: String): List<Int> = synchronized(lock) {
        sessions.keys
            .filter { it.startsWith("$nexId|") }
            .mapNotNull { it.substringAfter("$nexId|").toIntOrNull() }
            .sorted()
    }

    @Synchronized
    fun hasAnySession(nexId: String): Boolean = synchronized(lock) {
        sessions.keys.any { it.startsWith("$nexId|") }
    }

    @Synchronized
    fun forgetPeerIdentity(nexId: String) {
        synchronized(lock) {
            val keys = trustedIdentities.keys.filter { it.startsWith("$nexId|") }
            for (key in keys) {
                trustedIdentities.remove(key)
                dao.deleteBlocking(PREFIX_IDENTITY_PEER + key)
            }
            val sessionKeys = sessions.keys.filter { it.startsWith("$nexId|") }
            for (key in sessionKeys) {
                sessions.remove(key)
                dao.deleteBlocking(PREFIX_SESSION + key)
            }
        }
    }

    /**
     * Drops all in-memory Signal state after the encrypted database has been
     * wiped. The next sign-in generates a fresh device identity.
     */
    @Synchronized
    fun reset() {
        synchronized(lock) {
            identityKeyPair = null
            localRegistrationId = 0
            localDeviceId = null
            localSignalDeviceId = 0
            preKeys.clear()
            signedPreKeys.clear()
            kyberPreKeys.clear()
            sessions.clear()
            trustedIdentities.clear()
            usedKyberBaseKeys.clear()
            initialized = false
        }
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = synchronized(lock) {
        requireNotNull(identityKeyPair) { "Signal identity is not initialized" }
    }

    override fun getLocalRegistrationId(): Int = synchronized(lock) { localRegistrationId }

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange = synchronized(lock) {
        val key = addressKey(address)
        val existing = trustedIdentities[key]
        if (existing != null && existing == identityKey) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            trustedIdentities[key] = identityKey
            persist(PREFIX_IDENTITY_PEER + key, identityKey.serialize())
            if (existing == null) {
                IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            } else {
                IdentityKeyStore.IdentityChange.REPLACED_EXISTING
            }
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = synchronized(lock) {
        val existing = trustedIdentities[addressKey(address)] ?: return@synchronized true
        existing == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = synchronized(lock) {
        trustedIdentities[addressKey(address)]
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = synchronized(lock) {
        preKeys[preKeyId] ?: throw InvalidKeyIdException("No prekey for ID $preKeyId")
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = synchronized(lock) {
        preKeys[preKeyId] = record
        persist(PREFIX_PREKEY + preKeyId, record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean = synchronized(lock) {
        preKeys.containsKey(preKeyId)
    }

    override fun removePreKey(preKeyId: Int) = synchronized(lock) {
        preKeys.remove(preKeyId)
        dao.deleteBlocking(PREFIX_PREKEY + preKeyId)
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord = synchronized(lock) {
        sessions.getOrPut(addressKey(address)) { SessionRecord() }
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        synchronized(lock) {
            addresses.map { address ->
                sessions[addressKey(address)]
                    ?: throw CryptoException("No encrypted session exists for this device")
            }
        }

    override fun getSubDeviceSessions(name: String): List<Int> = synchronized(lock) {
        sessions.keys.filter { it.startsWith("$name|") }
            .mapNotNull { it.substringAfter("$name|").toIntOrNull() }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = synchronized(lock) {
        val key = addressKey(address)
        sessions[key] = record
        persist(PREFIX_SESSION + key, record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = synchronized(lock) {
        sessions.containsKey(addressKey(address))
    }

    override fun deleteSession(address: SignalProtocolAddress) = synchronized(lock) {
        val key = addressKey(address)
        sessions.remove(key)
        dao.deleteBlocking(PREFIX_SESSION + key)
    }

    override fun deleteAllSessions(name: String) = synchronized(lock) {
        val keys = sessions.keys.filter { it.startsWith("$name|") }
        keys.forEach {
            sessions.remove(it)
            dao.deleteBlocking(PREFIX_SESSION + it)
        }
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = synchronized(lock) {
        signedPreKeys[signedPreKeyId]
            ?: throw InvalidKeyIdException("No signed prekey for ID $signedPreKeyId")
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = synchronized(lock) {
        signedPreKeys.values.toList()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = synchronized(lock) {
        signedPreKeys[signedPreKeyId] = record
        persist(PREFIX_SIGNED + signedPreKeyId, record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = synchronized(lock) {
        signedPreKeys.containsKey(signedPreKeyId)
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) = synchronized(lock) {
        signedPreKeys.remove(signedPreKeyId)
        dao.deleteBlocking(PREFIX_SIGNED + signedPreKeyId)
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = synchronized(lock) {
        kyberPreKeys[kyberPreKeyId]
            ?: throw InvalidKeyIdException("No Kyber prekey for ID $kyberPreKeyId")
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = synchronized(lock) {
        kyberPreKeys.values.toList()
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = synchronized(lock) {
        kyberPreKeys[kyberPreKeyId] = record
        persist(PREFIX_KYBER + kyberPreKeyId, record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = synchronized(lock) {
        kyberPreKeys.containsKey(kyberPreKeyId)
    }

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        baseKeyId: Int,
        baseKey: ECPublicKey,
    ) = synchronized(lock) {
        // The server publishes one reusable last-resort Kyber prekey so a fresh
        // sender never fails just because another sender consumed it. That makes
        // replay detection our responsibility: persist the exact
        // (Kyber prekey, signed prekey, base key) tuple and reject a second use.
        // The private Kyber key stays available for delayed prekey messages.
        if (!kyberPreKeys.containsKey(kyberPreKeyId)) {
            throw InvalidKeyIdException("No Kyber prekey for ID $kyberPreKeyId")
        }
        val tuple = "$kyberPreKeyId|$baseKeyId|${base64(baseKey.serialize())}"
        if (!usedKyberBaseKeys.add(tuple)) {
            throw ReusedBaseKeyException("This Kyber prekey was already used with the same base key")
        }
        persist(PREFIX_KYBER_USED + tuple, ByteArray(0))
    }

    override fun storeSenderKey(address: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        throw UnsupportedOperationException("NEXCHAT group messages use pairwise Signal sessions")
    }

    override fun loadSenderKey(address: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord {
        throw UnsupportedOperationException("NEXCHAT group messages use pairwise Signal sessions")
    }

    private fun generateAndStoreBatch(): UploadKeyBatch {
        val identity = requireNotNull(identityKeyPair)
        val signedId = nextId(signedPreKeys.keys)
        val signedKeyPair = ECKeyPair.generate()
        val signedSignature = identity.privateKey.calculateSignature(signedKeyPair.publicKey.serialize())
        val signedRecord = SignedPreKeyRecord(
            signedId,
            System.currentTimeMillis(),
            signedKeyPair,
            signedSignature,
        )
        storeSignedPreKey(signedId, signedRecord)

        val kyberId = nextId(kyberPreKeys.keys)
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identity.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
        val kyberRecord = KyberPreKeyRecord(
            kyberId,
            System.currentTimeMillis(),
            kyberKeyPair,
            kyberSignature,
        )
        storeKyberPreKey(kyberId, kyberRecord)

        val oneTime = mutableListOf<Pair<Int, String>>()
        repeat(30) {
            val id = nextId(preKeys.keys)
            val keyPair = ECKeyPair.generate()
            val record = PreKeyRecord(id, keyPair)
            storePreKey(id, record)
            oneTime += id to base64(keyPair.publicKey.serialize())
        }

        return UploadKeyBatch(
            signedPreKeyId = signedId,
            signedPreKeyPublic = base64(signedKeyPair.publicKey.serialize()),
            signedPreKeySignature = base64(signedSignature),
            kyberPreKeyId = kyberId,
            kyberPreKeyPublic = base64(kyberKeyPair.publicKey.serialize()),
            kyberPreKeySignature = base64(kyberSignature),
            oneTimePreKeys = oneTime,
        )
    }

    private fun nextId(existing: Set<Int>): Int {
        var id: Int
        do {
            id = random.nextInt(Int.MAX_VALUE - 1) + 1
        } while (existing.contains(id))
        return id
    }

    private fun persist(key: String, value: ByteArray) {
        dao.upsertBlocking(SignalStateEntity(key, value))
    }

    private fun addressKey(address: SignalProtocolAddress): String =
        "${address.name}|${address.deviceId}"

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEY_IDENTITY = "identity_key_pair"
        const val KEY_REGISTRATION_ID = "registration_id"
        const val KEY_DEVICE_ID = "local_device_id"
        const val KEY_SIGNAL_DEVICE_ID = "local_signal_device_id"
        const val PREFIX_PREKEY = "prekey|"
        const val PREFIX_SIGNED = "signed_prekey|"
        const val PREFIX_KYBER = "kyber_prekey|"
        const val PREFIX_SESSION = "session|"
        const val PREFIX_IDENTITY_PEER = "identity_peer|"
        const val PREFIX_KYBER_USED = "kyber_used|"
    }
}

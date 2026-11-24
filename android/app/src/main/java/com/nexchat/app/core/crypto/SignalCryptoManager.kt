package com.nexchat.app.core.crypto

import android.util.Base64
import com.nexchat.app.core.network.EnvelopeDto
import com.nexchat.app.core.network.KeyBundle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

open class CryptoException(message: String, cause: Throwable? = null) : IOException(message, cause)
class IdentityChangedException(val nexId: String) :
    CryptoException("The safety number for this contact has changed. Verify the new identity before continuing.")
class DuplicateEnvelopeException : CryptoException("This encrypted envelope was already processed")

data class EncryptedPayload(val ciphertext: String, val cipherType: Int)

/**
 * All Signal encryption operations are serialized through one mutex. Session
 * ratchets must not be advanced concurrently with the same stored session.
 */
class SignalCryptoManager(
    private val store: NexSignalStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) {
    private val cryptoMutex = Mutex()

    suspend fun ensureInitialized(): UploadKeyBatch? = withContext(Dispatchers.IO) {
        cryptoMutex.withLock { store.initializeIdentityIfNeeded() }
    }

    /**
     * Switches the local protocol address to the relay's authoritative ID.
     * Existing pairwise sessions are dropped by the store because they are
     * bound to the previous address.
     */
    suspend fun adoptSignalDeviceId(newId: Int): Boolean = withContext(Dispatchers.IO) {
        cryptoMutex.withLock { store.adoptSignalDeviceId(newId) }
    }

    suspend fun deviceIdentity(): NexSignalStore.LocalDeviceIdentity = withContext(Dispatchers.IO) {
        store.ensureLocalDeviceIdentity()
    }

    suspend fun newUploadBatch(): UploadKeyBatch = withContext(Dispatchers.IO) {
        cryptoMutex.withLock {
            store.initialize()
            store.pendingKeyMaterial()
        }
    }

    suspend fun identityKeyBase64(): String = withContext(Dispatchers.IO) {
        store.initialize()
        store.identityPublicBase64()
    }

    suspend fun hasSession(nexId: String, signalDeviceId: Int): Boolean =
        withContext(Dispatchers.IO) { store.hasSession(nexId, signalDeviceId) }

    suspend fun knownDeviceIds(nexId: String): List<Int> =
        withContext(Dispatchers.IO) { store.knownDeviceIds(nexId) }

    suspend fun encrypt(
        content: SecureContent,
        peerNexId: String,
        signalDeviceId: Int,
        bundle: KeyBundle? = null,
    ): EncryptedPayload = withContext(Dispatchers.IO) {
        encryptContent(content, peerNexId, signalDeviceId, bundle)
    }

    /**
     * Signal-encrypts an arbitrary UTF-8 string with the same pairwise session
     * used for messages. Nearby routing headers use this so relays see only
     * ciphertext, while the original end-to-end envelope stays untouched.
     */
    suspend fun encryptText(
        text: String,
        peerNexId: String,
        signalDeviceId: Int,
        bundle: KeyBundle? = null,
    ): EncryptedPayload = withContext(Dispatchers.IO) {
        encryptContent(SecureContent(kind = ContentKind.TEXT, text = text), peerNexId, signalDeviceId, bundle)
    }

    suspend fun decrypt(envelope: EnvelopeDto): SecureContent = withContext(Dispatchers.IO) {
        decryptEnvelope(envelope)
    }

    /** Decrypts a Signal envelope produced by [encryptText]. */
    suspend fun decryptText(envelope: EnvelopeDto): String = withContext(Dispatchers.IO) {
        decryptEnvelope(envelope).text
            ?: throw CryptoException("Encrypted payload did not contain text")
    }

    /**
     * Fresh public key material for a nearby HELLO. One public one-time prekey
     * is included so the peer can establish a new X3DH session; no private key
     * ever leaves the store.
     */
    suspend fun nearbyKeyBundle(): KeyBundle = withContext(Dispatchers.IO) {
        cryptoMutex.withLock {
            store.initialize()
            val identity = store.ensureLocalDeviceIdentity()
            val batch = store.pendingKeyMaterial()
            val oneTime = batch.oneTimePreKeys.firstOrNull()
                ?: throw CryptoException("No one-time Signal prekey available for nearby")
            KeyBundle(
                deviceId = identity.deviceId,
                signalDeviceId = identity.signalDeviceId,
                registrationId = identity.registrationId,
                identityKey = store.identityPublicBase64(),
                signedPreKeyId = batch.signedPreKeyId,
                signedPreKeyPublic = batch.signedPreKeyPublic,
                signedPreKeySignature = batch.signedPreKeySignature,
                oneTimePreKeyId = oneTime.first,
                oneTimePreKeyPublic = oneTime.second,
                kyberPreKeyId = batch.kyberPreKeyId,
                kyberPreKeyPublic = batch.kyberPreKeyPublic,
                kyberPreKeySignature = batch.kyberPreKeySignature,
            )
        }
    }

    suspend fun peerIdentity(nexId: String, signalDeviceId: Int): String? =
        withContext(Dispatchers.IO) { store.peerIdentityBase64(nexId, signalDeviceId) }

    suspend fun forgetIdentity(nexId: String) = withContext(Dispatchers.IO) {
        store.forgetPeerIdentity(nexId)
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        cryptoMutex.withLock { store.reset() }
    }

    /**
     * Signal-style numeric safety number. It is a human-comparable fingerprint,
     * not a secret and not an alternative encryption mechanism.
     */
    suspend fun safetyNumber(
        localNexId: String,
        remoteNexId: String,
        remoteIdentityBase64: String,
    ): String? =
        withContext(Dispatchers.IO) {
            store.initialize()
            runCatching {
                val local = store.getIdentityKeyPair().publicKey
                val remote = IdentityKey(decode(remoteIdentityBase64))
                val generator = NumericFingerprintGenerator(2)
                val fingerprint = generator.createFor(
                    2,
                    localNexId.toByteArray(Charsets.UTF_8),
                    local,
                    remoteNexId.toByteArray(Charsets.UTF_8),
                    remote,
                )
                fingerprint.displayableFingerprint.displayText
            }.getOrNull()
        }

    private suspend fun encryptContent(
        content: SecureContent,
        peerNexId: String,
        signalDeviceId: Int,
        bundle: KeyBundle?,
    ): EncryptedPayload = cryptoMutex.withLock {
        store.initialize()
        val protocolDeviceId = canonicalSignalDeviceId(signalDeviceId)
        if (bundle != null && canonicalSignalDeviceId(bundle.signalDeviceId) != protocolDeviceId) {
            throw CryptoException("Encryption bundle did not match the target device")
        }
        val address = SignalProtocolAddress(peerNexId, protocolDeviceId)
        if (!store.containsSession(address)) {
            val required = bundle ?: throw CryptoException("No prekey bundle available for $peerNexId")
            val oneTimeKeyId = required.oneTimePreKeyId ?: PreKeyBundle.NULL_PRE_KEY_ID
            val oneTimeKey = required.oneTimePreKeyPublic?.let { ECPublicKey(decode(it)) }
            val preKeyBundle = PreKeyBundle(
                required.registrationId,
                protocolDeviceId,
                oneTimeKeyId,
                oneTimeKey,
                required.signedPreKeyId,
                ECPublicKey(decode(required.signedPreKeyPublic)),
                decode(required.signedPreKeySignature),
                IdentityKey(decode(required.identityKey)),
                required.kyberPreKeyId,
                KEMPublicKey(decode(required.kyberPreKeyPublic)),
                decode(required.kyberPreKeySignature),
            )
            try {
                SessionBuilder(store, address).process(preKeyBundle)
            } catch (error: UntrustedIdentityException) {
                throw IdentityChangedException(peerNexId)
            }
        }
        val cipher = SessionCipher(store, address)
        val plaintext = json.encodeToString(content).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plaintext)
        EncryptedPayload(
            ciphertext = Base64.encodeToString(encrypted.serialize(), Base64.NO_WRAP),
            cipherType = encrypted.type,
        )
    }

    private suspend fun decryptEnvelope(envelope: EnvelopeDto): SecureContent = cryptoMutex.withLock {
        store.initialize()
        val address = SignalProtocolAddress(
            envelope.senderNexId,
            canonicalSignalDeviceId(envelope.senderSignalDeviceId),
        )
        val cipher = SessionCipher(store, address)
        val bytes = Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
        val plaintext = try {
            when (envelope.cipherType) {
                CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(bytes))
                CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(bytes))
                else -> throw CryptoException("Unsupported ciphertext type ${envelope.cipherType}")
            }
        } catch (error: DuplicateMessageException) {
            throw DuplicateEnvelopeException()
        } catch (error: ReusedBaseKeyException) {
            throw DuplicateEnvelopeException()
        } catch (error: UntrustedIdentityException) {
            throw IdentityChangedException(envelope.senderNexId)
        } catch (error: Exception) {
            if (error is CryptoException) throw error
            throw CryptoException("Message authentication or decryption failed", error)
        }
        try {
            json.decodeFromString<SecureContent>(plaintext.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw CryptoException("Decrypted content was malformed", error)
        }
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}

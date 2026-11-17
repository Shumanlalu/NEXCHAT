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
        encodeDefaults = false
        explicitNulls = false
    },
) {
    private val cryptoMutex = Mutex()

    suspend fun ensureInitialized(): UploadKeyBatch? = withContext(Dispatchers.IO) {
        cryptoMutex.withLock { store.initializeIdentityIfNeeded() }
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
        cryptoMutex.withLock {
            store.initialize()
            if (bundle != null && bundle.signalDeviceId != signalDeviceId) {
                throw CryptoException("Encryption bundle did not match the target device")
            }
            val address = SignalProtocolAddress(peerNexId, signalDeviceId)
            if (!store.containsSession(address)) {
                val required = bundle ?: throw CryptoException("No prekey bundle available for $peerNexId")
                val oneTimeKeyId = required.oneTimePreKeyId ?: PreKeyBundle.NULL_PRE_KEY_ID
                val oneTimeKey = required.oneTimePreKeyPublic?.let { ECPublicKey(decode(it)) }
                val preKeyBundle = PreKeyBundle(
                    required.registrationId,
                    required.signalDeviceId,
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
    }

    suspend fun decrypt(envelope: EnvelopeDto): SecureContent = withContext(Dispatchers.IO) {
        cryptoMutex.withLock {
            store.initialize()
            val address = SignalProtocolAddress(envelope.senderNexId, envelope.senderSignalDeviceId)
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

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}

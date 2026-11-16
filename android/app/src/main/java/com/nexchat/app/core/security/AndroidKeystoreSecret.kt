package com.nexchat.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small wrapper around an Android Keystore AES-GCM key. The key never leaves the
 * Keystore (hardware-backed where the device supports it). Only ciphertext and a
 * fresh random IV are written to preferences.
 */
class AndroidKeystoreSecret(private val alias: String = "nexchat_master_v1") {
    private val random = SecureRandom()

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val output = ByteArray(1 + iv.size + ciphertext.size)
        output[0] = iv.size.toByte()
        iv.copyInto(output, 1)
        ciphertext.copyInto(output, 1 + iv.size)
        return output
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > 2) { "Invalid encrypted payload" }
        val ivLength = payload[0].toInt() and 0xff
        require(ivLength in 12..16 && payload.size > 1 + ivLength) { "Invalid IV length" }
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encryptToBase64(plaintext: ByteArray): String =
        Base64.encodeToString(encrypt(plaintext), Base64.NO_WRAP)

    fun decryptFromBase64(encoded: String): ByteArray =
        decrypt(Base64.decode(encoded, Base64.NO_WRAP))

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun deleteKey() {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}

class SecureSessionStore(
    context: Context,
    private val keystore: AndroidKeystoreSecret = AndroidKeystoreSecret(),
) {
    private val prefs = context.applicationContext.getSharedPreferences("nexchat_secure_v1", Context.MODE_PRIVATE)

    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var refreshToken: String? = null
        private set

    init {
        accessToken = readEncrypted(KEY_ACCESS)
        refreshToken = readEncrypted(KEY_REFRESH)
    }

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

    @Synchronized
    fun saveTokens(access: String, refresh: String) {
        accessToken = access
        refreshToken = refresh
        prefs.edit()
            .putString(KEY_ACCESS, keystore.encryptToBase64(access.toByteArray(Charsets.UTF_8)))
            .putString(KEY_REFRESH, keystore.encryptToBase64(refresh.toByteArray(Charsets.UTF_8)))
            .apply()
    }

    @Synchronized
    fun clearTokens() {
        accessToken = null
        refreshToken = null
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
    }

    @Synchronized
    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) {
            return try {
                keystore.decryptFromBase64(existing)
            } catch (error: Exception) {
                throw IllegalStateException(
                    "The local encryption key can no longer be unlocked. Local history cannot be recovered.",
                    error,
                )
            }
        }
        val generated = keystore.randomBytes(32)
        prefs.edit()
            .putString(KEY_DB_PASSPHRASE, keystore.encryptToBase64(generated))
            .apply()
        return generated
    }

    @Synchronized
    fun clearDatabasePassphrase() {
        prefs.edit().remove(KEY_DB_PASSPHRASE).apply()
    }

    @Synchronized
    fun wipeAll() {
        clearTokens()
        prefs.edit().clear().apply()
        try {
            keystore.deleteKey()
        } catch (_: Exception) {
            // A failed Keystore deletion must not leave tokens behind.
        }
    }

    private fun readEncrypted(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            String(keystore.decryptFromBase64(encoded), Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove(key).apply()
            null
        }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_DB_PASSPHRASE = "db_passphrase"
    }
}

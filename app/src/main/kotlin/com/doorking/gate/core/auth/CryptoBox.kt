package com.doorking.gate.core.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts short secrets (session tokens) using a hardware-backed AES-256-GCM key in AndroidKeyStore.
 * Format: Base64(12-byte IV || ciphertext || 16-byte GCM tag).
 */
interface TokenCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}

class KeystoreTokenCipher(
    private val keyAlias: String = "doorking_session_aes_gcm",
) : TokenCipher {

    @Volatile
    private var cachedKey: SecretKey? = null

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (_: Throwable) {
            cachedKey = null
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String? = runCatching {
        val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
        if (combined.size <= IV_BYTES) return null
        val iv = combined.copyOfRange(0, IV_BYTES)
        val body = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.onFailure {
        cachedKey = null
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = ks.getKey(keyAlias, null) as? SecretKey
            val resolved = if (existing != null) {
                existing
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                generator.init(spec)
                generator.generateKey()
            }
            cachedKey = resolved
            return resolved
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

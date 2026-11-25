package com.doorking.gate.core.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

data class RegisteredKeyMaterial(
    val keyId: String,
    val publicKeySpkiBase64: String,
    val label: String,
)

/**
 * Manages a device-bound ECDSA P-256 keypair inside Android Keystore with `AUTH_BIOMETRIC_STRONG`.
 * Private keys never leave secure hardware and require a live `BiometricPrompt.CryptoObject` scan
 * for every single challenge signature.
 */
object BiometricKeyManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "doorking_biometric_stepup_ec_v1"

    fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val combined = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
        return combined.ifBlank { "Android" }.take(80)
    }

    /**
     * Generates (or replaces) a hardware-backed ECDSA P-256 signing key that requires a live strong
     * biometric scan on every signing operation, returning its new `keyId` and base64 SPKI DER public key.
     */
    fun generateFreshKey(): RegisteredKeyMaterial {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
        }
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        kpg.initialize(spec)
        val keyPair = kpg.generateKeyPair()
        val spki = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        return RegisteredKeyMaterial(
            keyId = "and-${UUID.randomUUID()}",
            publicKeySpkiBase64 = spki,
            label = deviceLabel(),
        )
    }

    /**
     * Prepares a `SHA256withECDSA` `Signature` bound to the hardware private key for `BiometricPrompt.CryptoObject`.
     * Returns `null` if the key does not exist or was invalidated by a new biometric enrollment.
     */
    fun initSigningCrypto(): Signature? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = ks.getKey(KEY_ALIAS, null) as? PrivateKey ?: return null
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
        }
    }.getOrElse { err ->
        if (err is KeyPermanentlyInvalidatedException) deleteLocalKey()
        null
    }

    /** Signs a server-issued UTF-8 challenge using the `Signature` unlocked by `BiometricPrompt`. */
    fun signChallenge(unlockedSignature: Signature, challenge: String): String {
        unlockedSignature.update(challenge.toByteArray(Charsets.UTF_8))
        val derSignature = unlockedSignature.sign()
        return Base64.encodeToString(derSignature, Base64.NO_WRAP)
    }

    fun deleteLocalKey() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }
}

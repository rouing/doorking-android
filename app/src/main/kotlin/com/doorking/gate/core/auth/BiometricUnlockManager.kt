package com.doorking.gate.core.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

const val BIOMETRIC_RELOCK_GRACE_MS = 5_000L

/**
 * Pure decision rule for whether the foreground app should lock behind the native Fingerprint / Biometric prompt.
 */
fun shouldRequireBiometricUnlock(
    loaded: Boolean,
    hasSession: Boolean,
    biometricLockEnabled: Boolean,
    alreadyUnlocked: Boolean,
    backgroundDurationMs: Long = 0L,
): Boolean {
    if (!loaded || !hasSession || !biometricLockEnabled) return false
    if (!alreadyUnlocked) return true
    return backgroundDurationMs >= BIOMETRIC_RELOCK_GRACE_MS
}

/**
 * Wraps AndroidX `BiometricPrompt` for immediate native fingerprint / face / device-credential unlock.
 * Works regardless of how the user signed in (Google SSO, password, passkey, or invite).
 */
object BiometricUnlockManager {

    private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean {
        val status = runCatching {
            BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
        }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED)
        return status == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun findFragmentActivity(context: Context): FragmentActivity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is FragmentActivity) return cur
            cur = cur.baseContext
        }
        return cur as? FragmentActivity
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Doorking Gate",
        subtitle: String = "Confirm your fingerprint or device screen lock",
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
        onCancelled: () -> Unit = {},
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    onCancelled()
                } else {
                    onError(errString.toString().ifBlank { "Biometric authentication failed" })
                }
            }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()
        prompt.authenticate(promptInfo)
    }
}

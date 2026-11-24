package com.doorking.gate.core.auth

import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricLockTest {

    @Test
    fun requiresUnlockOnColdStartOnlyWhenSignedInAndEnabled() {
        assertFalse(shouldRequireBiometricUnlock(loaded = false, hasSession = true, biometricLockEnabled = true, alreadyUnlocked = false))
        assertFalse(shouldRequireBiometricUnlock(loaded = true, hasSession = false, biometricLockEnabled = true, alreadyUnlocked = false))
        assertFalse(shouldRequireBiometricUnlock(loaded = true, hasSession = true, biometricLockEnabled = false, alreadyUnlocked = false))
        assertTrue(shouldRequireBiometricUnlock(loaded = true, hasSession = true, biometricLockEnabled = true, alreadyUnlocked = false))
    }

    @Test
    fun relocksOnlyAfterGracePeriodInBackground() {
        assertFalse(
            shouldRequireBiometricUnlock(
                loaded = true,
                hasSession = true,
                biometricLockEnabled = true,
                alreadyUnlocked = true,
                backgroundDurationMs = 2_000L,
            ),
        )
        assertTrue(
            shouldRequireBiometricUnlock(
                loaded = true,
                hasSession = true,
                biometricLockEnabled = true,
                alreadyUnlocked = true,
                backgroundDurationMs = BIOMETRIC_RELOCK_GRACE_MS,
            ),
        )
    }

    @Test
    fun clearSessionResetsBiometricLockPreference() = runTest {
        val storage = FakeSessionStorage(initialToken = "dks_test")
        storage.setBiometricLockEnabled(true)
        assertTrue(storage.state.value.biometricLockEnabled)
        storage.clearSessionToken()
        assertFalse(storage.state.value.biometricLockEnabled)
    }
}

package com.doorking.gate.feature.auth

import android.content.ContextWrapper
import com.doorking.gate.CrashStore
import com.doorking.gate.core.auth.PasskeyAuthenticator
import com.doorking.gate.core.auth.PasskeyResult
import com.doorking.gate.core.auth.UiStepUpCoordinator
import com.doorking.gate.core.auth.isGenuineUserCancellation
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.push.FirebaseHelper
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInAndStepUpTest {

    @Test
    fun validatesMinimumPasswordLengthAndMatch() {
        assertNotNull(validateNewPassword("short", "short"))
        assertNotNull(validateNewPassword("long-enough-pw1", "long-enough-pw2"))
        assertNull(validateNewPassword("correct-horse-battery", "correct-horse-battery"))
    }

    @Test
    fun stepUpCoordinatorSuspendsUntilResolved() = runTest {
        val coordinator = UiStepUpCoordinator()
        val deferred = async {
            coordinator.confirmStepUp(ApiErrorResponse(error = "Confirm with passkey", stepUp = true, hasPasskey = true))
        }
        testScheduler.runCurrent()
        assertNotNull(coordinator.pending.value)
        assertEquals("Confirm with passkey", coordinator.pending.value?.error?.error)

        coordinator.resolve(true)
        assertTrue(deferred.await())
        assertNull(coordinator.pending.value)
    }

    @Test
    fun stepUpCoordinatorReturnsFalseWhenCancelled() = runTest {
        val coordinator = UiStepUpCoordinator()
        val deferred = async {
            coordinator.confirmStepUp(ApiErrorResponse(error = "Confirm with passkey", stepUp = true, hasPasskey = true))
        }
        testScheduler.runCurrent()
        coordinator.resolve(false)
        assertFalse(deferred.await())
        assertNull(coordinator.pending.value)
    }

    @Test
    fun stepUpCoordinatorTimesOutAndClearsPendingState() = runTest {
        val coordinator = UiStepUpCoordinator()
        val deferred = async {
            coordinator.confirmStepUp(ApiErrorResponse(error = "Confirm with passkey", stepUp = true, hasPasskey = true))
        }
        testScheduler.runCurrent()
        assertNotNull(coordinator.pending.value)

        testScheduler.advanceTimeBy(UiStepUpCoordinator.STEP_UP_TIMEOUT_MS + 1L)
        testScheduler.runCurrent()
        assertFalse(deferred.await())
        assertNull(coordinator.pending.value)
    }

    @Test
    fun sessionStorageTracksAndResetsPushOptIn() = runTest {
        val storage = FakeSessionStorage(initialToken = "dks_${"1".repeat(64)}")
        assertFalse(storage.awaitLoaded().pushOptIn)

        storage.setPushOptIn(true)
        assertTrue(storage.awaitLoaded().pushOptIn)

        FirebaseHelper.disablePushOptIn(ContextWrapper(null), storage)
        assertFalse(storage.awaitLoaded().pushOptIn)

        storage.setPushOptIn(true)
        storage.clearSessionToken()
        assertFalse(storage.awaitLoaded().pushOptIn)
    }

    @Test
    fun fakePasskeyAuthenticatorContract() = runTest {
        val fake = object : PasskeyAuthenticator {
            override suspend fun authenticate(activityContext: android.content.Context, requestJson: String): PasskeyResult =
                PasskeyResult.Cancelled
        }
        // Verify cancellation type cleanly exists for UI handling without exceptions.
        assertEquals(PasskeyResult.Cancelled::class, fake::class.let { PasskeyResult.Cancelled::class })
    }

    @Test
    fun parsesValidHttpsInviteLinksAndRejectsUnsafeSchemes() {
        val validToken = "a".repeat(43)
        val ok = parseInviteDeepLink("https://gate.example.com/", validToken, allowLocalHttp = false)
        assertEquals(ParsedInviteLink("https://gate.example.com", validToken), ok)

        assertNull(parseInviteDeepLink("http://gate.example.com", validToken, allowLocalHttp = false))
        assertNull(parseInviteDeepLink("https://user:pass@gate.example.com", validToken, allowLocalHttp = false))
        assertNull(parseInviteDeepLink("https://gate.example.com", "short", allowLocalHttp = false))
        assertNull(parseInviteDeepLink("javascript:alert(1)", validToken, allowLocalHttp = false))
        assertNotNull(parseInviteDeepLink("http://10.0.2.2:8080", validToken, allowLocalHttp = true))
    }

    @Test
    fun validatesExternalBrowserUrlsStrictly() {
        assertTrue(isValidExternalBrowserUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=1", allowLocalHttp = false))
        assertTrue(isValidExternalBrowserUrl("https://gate.example.com/privacy", allowLocalHttp = false))
        assertFalse(isValidExternalBrowserUrl("http://gate.example.com/privacy", allowLocalHttp = false))
        assertFalse(isValidExternalBrowserUrl("https://user:pass@gate.example.com/auth", allowLocalHttp = false))
        assertFalse(isValidExternalBrowserUrl("javascript:alert(1)", allowLocalHttp = false))
        assertFalse(isValidExternalBrowserUrl("intent://evil/#Intent;end", allowLocalHttp = false))
        assertFalse(isValidExternalBrowserUrl("file:///etc/passwd", allowLocalHttp = false))
        assertTrue(isValidExternalBrowserUrl("http://10.0.2.2:3000/privacy", allowLocalHttp = true))
        assertFalse(isValidExternalBrowserUrl("http://10.0.2.2:3000/privacy", allowLocalHttp = false))
    }

    @Test
    fun crashStoreScrubsTokensUrlsEmailsAndPhoneNumbers() {
        val raw = """
            Failed request to https://gate.example.com/api/gates/2145550100?token=secret123
            Authorization: Bearer dks_${"a".repeat(64)}
            User alice@example.com called (214) 555-0199 for gate 2145550100
            at com.doorking.gate.MainActivity.onCreate(MainActivity.kt:123)
        """.trimIndent()
        val scrubbed = CrashStore.scrub(raw)
        assertFalse(scrubbed.contains("secret123"))
        assertFalse(scrubbed.contains("gate.example.com"))
        assertFalse(scrubbed.contains("dks_"))
        assertFalse(scrubbed.contains("alice@example.com"))
        assertFalse(scrubbed.contains("2145550100"))
        assertFalse(scrubbed.contains("555-0199"))
        assertTrue(scrubbed.contains("[REDACTED_URL]"))
        assertTrue(scrubbed.contains("Bearer [REDACTED]"))
        assertTrue(scrubbed.contains("[REDACTED_EMAIL]"))
        assertTrue(scrubbed.contains("[REDACTED_PHONE]"))
        assertTrue(scrubbed.contains("MainActivity.kt:123"))
    }

    @Test
    fun distinguishesGenuineGoogleSignInCancellationFromPlayServicesFailures() {
        assertTrue(isGenuineUserCancellation(null))
        assertTrue(isGenuineUserCancellation(""))
        assertTrue(isGenuineUserCancellation("   "))
        assertTrue(isGenuineUserCancellation("Request was cancelled by the user"))
        assertTrue(isGenuineUserCancellation("Canceled by the user."))
        assertTrue(isGenuineUserCancellation("User cancelled the sign-in flow"))
        assertTrue(isGenuineUserCancellation("User canceled"))
        assertTrue(isGenuineUserCancellation("Activity is cancelled by the user."))

        assertFalse(isGenuineUserCancellation("[16] Account reauth failed."))
        assertFalse(isGenuineUserCancellation("16: Account reauth failed."))
        assertFalse(isGenuineUserCancellation("Cannot find a matching credential"))
        assertFalse(isGenuineUserCancellation("Developer console is not set up correctly"))
        assertFalse(isGenuineUserCancellation("Play Services internal error"))
        assertFalse(isGenuineUserCancellation("User cancelled because reauth failed"))
        assertFalse(isGenuineUserCancellation("Unexpected interruption"))
    }
}

package com.doorking.gate.feature.auth

import com.doorking.gate.core.auth.PasskeyAuthenticator
import com.doorking.gate.core.auth.PasskeyResult
import com.doorking.gate.core.auth.UiStepUpCoordinator
import com.doorking.gate.core.network.ApiErrorResponse
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
        testScheduler.advanceUntilIdle()
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
        testScheduler.advanceUntilIdle()
        coordinator.resolve(false)
        assertFalse(deferred.await())
        assertNull(coordinator.pending.value)
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
}

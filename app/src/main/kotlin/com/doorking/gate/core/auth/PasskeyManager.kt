package com.doorking.gate.core.auth

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.StepUpCoordinator
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Distinguishes a genuine user dismissal of the Credential Manager sheet from Play Services
 * configuration or reauth failures that are also surfaced as [GetCredentialCancellationException].
 */
internal fun isGenuineUserCancellation(message: String?): Boolean {
    if (message.isNullOrBlank()) return true
    val lower = message.lowercase()
    val nonUserFailureMarkers = listOf(
        "reauth",
        "[16]",
        "16:",
        "developer",
        "failed",
        "error",
        "matching credential",
    )
    if (nonUserFailureMarkers.any { lower.contains(it) }) return false
    val userCancelMarkers = listOf(
        "cancelled by the user",
        "canceled by the user",
        "user cancelled",
        "user canceled",
        "activity is cancelled",
    )
    return userCancelMarkers.any { lower.contains(it) }
}

sealed interface PasskeyResult {
    data class Success(val assertionJson: String) : PasskeyResult
    data object Cancelled : PasskeyResult
    data class Error(val message: String) : PasskeyResult
}

sealed interface GoogleSignInResult {
    data class Success(val idToken: String) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class FallbackToBrowser(val reason: String) : GoogleSignInResult
    data class Error(val message: String) : GoogleSignInResult
}

/**
 * Abstraction over Android Credential Manager passkey assertions so auth flows can be tested locally with a fake.
 */
interface PasskeyAuthenticator {
    suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult
    suspend fun register(activityContext: Context, requestJson: String): PasskeyResult =
        PasskeyResult.Error("Passkey registration is not supported here.")
    suspend fun signInWithGoogle(
        activityContext: Context,
        serverClientId: String,
        nonce: String,
        hostedDomain: String? = null,
    ): GoogleSignInResult = GoogleSignInResult.FallbackToBrowser("Not supported")
}

class AndroidPasskeyManager : PasskeyAuthenticator {

    override suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult {
        val manager = CredentialManager.create(activityContext)
        val option = GetPublicKeyCredentialOption(requestJson = requestJson)
        val request = GetCredentialRequest(listOf(option))
        return try {
            val result = manager.getCredential(context = activityContext, request = request)
            val cred = result.credential as? PublicKeyCredential
                ?: return PasskeyResult.Error("Unsupported credential type returned by device.")
            PasskeyResult.Success(cred.authenticationResponseJson)
        } catch (_: GetCredentialCancellationException) {
            PasskeyResult.Cancelled
        } catch (_: NoCredentialException) {
            PasskeyResult.Error("No passkey for this DoorKing server was found on this device.")
        } catch (err: GetCredentialException) {
            PasskeyResult.Error(err.errorMessage?.toString() ?: err.message ?: "Passkey confirmation failed.")
        } catch (err: Throwable) {
            PasskeyResult.Error(err.message ?: "Passkey confirmation failed.")
        }
    }

    override suspend fun register(activityContext: Context, requestJson: String): PasskeyResult {
        val manager = CredentialManager.create(activityContext)
        val request = CreatePublicKeyCredentialRequest(requestJson = requestJson)
        return try {
            val result = manager.createCredential(context = activityContext, request = request)
            val cred = result as? CreatePublicKeyCredentialResponse
                ?: return PasskeyResult.Error("Unsupported passkey response returned by device.")
            PasskeyResult.Success(cred.registrationResponseJson)
        } catch (_: CreateCredentialCancellationException) {
            PasskeyResult.Cancelled
        } catch (err: CreateCredentialException) {
            PasskeyResult.Error(err.errorMessage?.toString() ?: err.message ?: "Could not save passkey on this device.")
        } catch (err: Throwable) {
            PasskeyResult.Error(err.message ?: "Could not save passkey on this device.")
        }
    }

    override suspend fun signInWithGoogle(
        activityContext: Context,
        serverClientId: String,
        nonce: String,
        hostedDomain: String?,
    ): GoogleSignInResult {
        val manager = CredentialManager.create(activityContext)
        val option = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(nonce)
            .apply {
                if (!hostedDomain.isNullOrBlank()) {
                    setHostedDomainFilter(hostedDomain)
                }
            }
            .build()
        val request = GetCredentialRequest(listOf(option))
        return try {
            val result = manager.getCredential(context = activityContext, request = request)
            val cred = result.credential
            if (cred is CustomCredential &&
                cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                if (idToken.isBlank()) {
                    GoogleSignInResult.FallbackToBrowser("Empty Google ID token returned by device.")
                } else {
                    GoogleSignInResult.Success(idToken)
                }
            } else {
                GoogleSignInResult.FallbackToBrowser("Unsupported credential type returned by device.")
            }
        } catch (err: GetCredentialCancellationException) {
            val msg = err.errorMessage?.toString()?.takeIf { it.isNotBlank() } ?: err.message
            if (isGenuineUserCancellation(msg)) {
                GoogleSignInResult.Cancelled
            } else {
                GoogleSignInResult.FallbackToBrowser(
                    msg ?: "Google Sign-In could not complete natively.",
                )
            }
        } catch (err: NoCredentialException) {
            GoogleSignInResult.FallbackToBrowser(
                err.errorMessage?.toString() ?: err.message ?: "No Google credential available.",
            )
        } catch (err: GetCredentialProviderConfigurationException) {
            GoogleSignInResult.FallbackToBrowser(
                err.errorMessage?.toString() ?: err.message ?: "Google Sign-In provider unavailable.",
            )
        } catch (err: GetCredentialCustomException) {
            GoogleSignInResult.FallbackToBrowser(
                err.errorMessage?.toString() ?: err.message ?: "Google Sign-In custom error.",
            )
        } catch (err: GetCredentialException) {
            GoogleSignInResult.FallbackToBrowser(
                err.errorMessage?.toString() ?: err.message ?: "Google Sign-In unavailable.",
            )
        } catch (err: GoogleIdTokenParsingException) {
            GoogleSignInResult.Error(err.message ?: "Could not parse Google ID token.")
        } catch (err: CancellationException) {
            throw err
        } catch (err: Throwable) {
            GoogleSignInResult.Error(err.message ?: "Google Sign-In failed.")
        }
    }
}

data class PendingStepUp(
    val error: ApiErrorResponse,
    val deferred: CompletableDeferred<Boolean>,
)

/**
 * Bridges OkHttp's `403 { stepUp: true }` interceptor to the active Compose UI so the user can confirm with a passkey
 * and transparently retry the blocked request once. Serializes concurrent prompts via a `Mutex`.
 */
class UiStepUpCoordinator : StepUpCoordinator {

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<PendingStepUp?>(null)
    val pending: StateFlow<PendingStepUp?> = _pending.asStateFlow()

    override suspend fun confirmStepUp(error: ApiErrorResponse): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        _pending.value = PendingStepUp(error = error, deferred = deferred)
        try {
            deferred.await()
        } finally {
            _pending.value = null
        }
    }

    fun resolve(confirmed: Boolean) {
        _pending.value?.deferred?.complete(confirmed)
    }
}

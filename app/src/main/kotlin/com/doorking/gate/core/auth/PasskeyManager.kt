package com.doorking.gate.core.auth

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.StepUpCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PasskeyResult {
    data class Success(val assertionJson: String) : PasskeyResult
    data object Cancelled : PasskeyResult
    data class Error(val message: String) : PasskeyResult
}

/**
 * Abstraction over Android Credential Manager passkey assertions so auth flows can be tested locally with a fake.
 */
interface PasskeyAuthenticator {
    suspend fun authenticate(activityContext: Context, requestJson: String): PasskeyResult
    suspend fun register(activityContext: Context, requestJson: String): PasskeyResult =
        PasskeyResult.Error("Passkey registration is not supported here.")
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

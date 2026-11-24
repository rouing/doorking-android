package com.doorking.gate.core.network

import com.doorking.gate.core.auth.SessionStorage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

sealed interface AuthEvent {
    data object SessionExpired : AuthEvent
    data object MustChangePassword : AuthEvent
}

/**
 * Coordinates interactive passkey step-up confirmation when a protected endpoint returns `403 { stepUp: true }`.
 * Phase 2 wires Android Credential Manager into this hook so sensitive actions retry seamlessly once confirmed.
 */
fun interface StepUpCoordinator {
    suspend fun confirmStepUp(error: ApiErrorResponse): Boolean
}

class AuthEventBus {
    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    fun emit(event: AuthEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Rewrites requests to the user's configured server URL, attaches `Authorization: Bearer dks_...` for signed-in
 * sessions, sends `X-Client-Auth: bearer` on sign-in endpoints, and handles `401` / `403 mustChangePassword` /
 * `403 stepUp` responses consistently across Retrofit, Coil, and Media3.
 */
class DoorkingAuthInterceptor(
    private val sessionStorage: SessionStorage,
    private val authEvents: AuthEventBus,
    private val json: Json,
    @Volatile var stepUpCoordinator: StepUpCoordinator? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = rewriteUrl(original)
        val token = sessionStorage.currentSessionToken()
        val path = rewritten.url.encodedPath

        val builder = rewritten.newBuilder()
            .header("Accept", "application/json")

        if (original.header("Authorization") == null && !token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        if (path in BEARER_ISSUE_PATHS && original.method.equals("POST", ignoreCase = true)) {
            builder.header("X-Client-Auth", "bearer")
        }

        val request = builder.build()
        val response = chain.proceed(request)

        if (response.code == 401 && !(path == "/api/session" && request.method.equals("POST", ignoreCase = true))) {
            runBlocking { sessionStorage.clearSessionToken() }
            authEvents.emit(AuthEvent.SessionExpired)
            return response
        }

        if (response.code == 403) {
            val errorBody = peekApiError(response)
            if (errorBody?.mustChangePassword == true) {
                authEvents.emit(AuthEvent.MustChangePassword)
                return response
            }
            val alreadyRetried = request.header(HEADER_STEP_UP_RETRIED) == "1"
            val coordinator = stepUpCoordinator
            if (errorBody?.stepUp == true && !alreadyRetried && coordinator != null) {
                val confirmed = runBlocking { coordinator.confirmStepUp(errorBody) }
                if (confirmed) {
                    response.close()
                    val retry = request.newBuilder()
                        .header(HEADER_STEP_UP_RETRIED, "1")
                        .build()
                    return chain.proceed(retry)
                }
            }
        }

        return response
    }

    private fun rewriteUrl(request: okhttp3.Request): okhttp3.Request {
        val configured = sessionStorage.currentServerUrl().toHttpUrlOrNull() ?: return request
        val old = request.url
        if (old.scheme == configured.scheme && old.host == configured.host && old.port == configured.port) {
            return request
        }
        val nextUrl = old.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()
        return request.newBuilder().url(nextUrl).build()
    }

    private fun peekApiError(response: Response): ApiErrorResponse? {
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("json", ignoreCase = true)) return null
        return runCatching {
            val raw = response.peekBody(MAX_ERROR_PEEK_BYTES).string()
            json.decodeFromString<ApiErrorResponse>(raw)
        }.getOrNull()
    }

    companion object {
        private const val HEADER_STEP_UP_RETRIED = "X-Doorking-StepUp-Retried"
        private const val MAX_ERROR_PEEK_BYTES = 16_384L
        private val BEARER_ISSUE_PATHS = setOf(
            "/api/session",
            "/api/passkey/session",
            "/api/sso/native-exchange",
            "/api/setup",
            "/api/invite",
        )
    }
}

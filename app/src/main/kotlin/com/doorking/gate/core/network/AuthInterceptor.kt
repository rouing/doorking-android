package com.doorking.gate.core.network

import android.net.TrafficStats
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.auth.serverOriginOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AuthEvent {
    data object SessionExpired : AuthEvent
    data object MustChangePassword : AuthEvent
}

/**
 * Coordinates interactive passkey/biometric/password step-up confirmation when a protected endpoint
 * returns `403 { stepUp: true }`.
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
 * Rewrites relative API requests to the user's configured server URL, attaches `Authorization: Bearer`
 * strictly when the target origin matches the session token's origin and the endpoint is not on the
 * unauthenticated allowlist, sends `X-Client-Auth: bearer` on sign-in endpoints, and handles `401` /
 * `403 mustChangePassword` / `403 stepUp` responses consistently across Retrofit, Coil, and Media3.
 */
class DoorkingAuthInterceptor(
    private val sessionStorage: SessionStorage,
    private val authEvents: AuthEventBus,
    private val json: Json,
    @Volatile var stepUpCoordinator: StepUpCoordinator? = null,
) : Interceptor {

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clearingSession = AtomicBoolean(false)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val explicitUrl = original.header(HEADER_EXPLICIT_URL) == "1"
        val noInteractiveStepUp = original.header(HEADER_NO_INTERACTIVE_STEP_UP) == "1"

        val rewritten = if (explicitUrl) original else rewriteUrl(original)
        val path = rewritten.url.encodedPath
        val method = rewritten.method
        val unauthenticated = isUnauthenticatedRequest(method, path)

        val stored = sessionStorage.state.value
        val token = stored.sessionToken
        val targetOrigin = "${rewritten.url.scheme}://${rewritten.url.host}:${rewritten.url.port}"
        val allowedOrigin = stored.tokenServerOrigin ?: serverOriginOf(stored.serverUrl)
        val originMatches = allowedOrigin != null && targetOrigin.equals(allowedOrigin, ignoreCase = true)

        val builder = rewritten.newBuilder()
            .removeHeader(HEADER_EXPLICIT_URL)
            .removeHeader(HEADER_NO_INTERACTIVE_STEP_UP)
            .header("Accept", "application/json")

        val shouldAttachBearer = original.header("Authorization") == null &&
            !token.isNullOrBlank() &&
            !unauthenticated &&
            originMatches

        if (shouldAttachBearer) {
            builder.header("Authorization", "Bearer $token")
        }
        if (path in BEARER_ISSUE_PATHS && method.equals("POST", ignoreCase = true)) {
            builder.header("X-Client-Auth", "bearer")
        }

        val request = builder.build()
        val trafficTag = tagForPath(path)
        val response = proceedTagged(chain, request, trafficTag)

        if (response.code == 401 && !unauthenticated && originMatches && !token.isNullOrBlank()) {
            if (clearingSession.compareAndSet(false, true)) {
                cleanupScope.launch {
                    try {
                        if (sessionStorage.currentSessionToken() != null) {
                            sessionStorage.clearSessionToken()
                            authEvents.emit(AuthEvent.SessionExpired)
                        }
                    } finally {
                        clearingSession.set(false)
                    }
                }
            }
            return response
        }

        if (response.code == 403 && !unauthenticated && originMatches) {
            val errorBody = peekApiError(response)
            if (errorBody?.mustChangePassword == true) {
                authEvents.emit(AuthEvent.MustChangePassword)
                return response
            }
            val coordinator = stepUpCoordinator
            val skipInteractive = noInteractiveStepUp || path in STEP_UP_PATHS
            if (errorBody?.stepUp == true && !skipInteractive && coordinator != null) {
                val confirmed = runBlocking { coordinator.confirmStepUp(errorBody) }
                if (confirmed) {
                    response.close()
                    return proceedTagged(chain, request, trafficTag)
                }
            }
        }

        return response
    }

    private fun proceedTagged(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
        tag: Int,
    ): Response {
        runCatching { TrafficStats.setThreadStatsTag(tag) }
        return try {
            chain.proceed(request)
        } finally {
            runCatching { TrafficStats.clearThreadStatsTag() }
        }
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
        const val HEADER_NO_INTERACTIVE_STEP_UP = "X-Doorking-No-Interactive-StepUp"
        const val HEADER_EXPLICIT_URL = "X-Doorking-Explicit-Url"
        private const val MAX_ERROR_PEEK_BYTES = 16_384L

        private val UNAUTHENTICATED_PATHS = setOf(
            "/api/invite/check",
            "/api/invite",
            "/api/password-reset/request",
            "/api/server-info",
            "/api/sso/start",
            "/api/sso/native-exchange",
            "/api/sso/google-nonce",
            "/api/sso/google-id-token",
            "/api/passkey/options",
            "/api/passkey/session",
        )

        private val STEP_UP_PATHS = setOf(
            "/api/me/step-up/options",
            "/api/me/step-up",
            "/api/me/step-up/biometric/options",
        )

        private val BEARER_ISSUE_PATHS = setOf(
            "/api/session",
            "/api/passkey/session",
            "/api/sso/native-exchange",
            "/api/sso/google-id-token",
            "/api/setup",
            "/api/invite",
        )

        internal const val TAG_OTHER = 0x444B00
        internal const val TAG_AUTH = 0x444B01
        internal const val TAG_PORTAL = 0x444B02
        internal const val TAG_STAFF = 0x444B03
        internal const val TAG_MEDIA = 0x444B04
        internal const val TAG_ADMIN = 0x444B05

        internal fun tagForPath(path: String): Int {
            val p = path.lowercase()
            return when {
                p.startsWith("/api/session") ||
                    p.startsWith("/api/me") ||
                    p.startsWith("/api/passkey") ||
                    p.startsWith("/api/sso") ||
                    p.startsWith("/api/invite") ||
                    p.startsWith("/api/password-reset") ||
                    p.startsWith("/api/setup") ||
                    p.startsWith("/api/server-info") -> TAG_AUTH

                p.startsWith("/api/portal/") -> TAG_PORTAL

                p.contains("/cameras") ||
                    p.contains("/snapshots") ||
                    p.contains("/media/") ||
                    p.endsWith(".jpg") ||
                    p.endsWith(".jpeg") ||
                    p.endsWith(".mp4") -> TAG_MEDIA

                p.contains("/settings") ||
                    p.contains("/prefs") ||
                    p.contains("/users") ||
                    p.contains("/share") ||
                    p.contains("/login") ||
                    p.contains("/api-keys") ||
                    p.contains("/webhooks") ||
                    p.contains("/schedules") ||
                    p.contains("/audit") ||
                    p.contains("/history") ||
                    p.contains("/pull-archive") ||
                    p.contains("/delete-data") -> TAG_ADMIN

                p.startsWith("/api/gates") || p.startsWith("/api/gate-add") -> TAG_STAFF

                else -> TAG_OTHER
            }
        }

        internal fun isUnauthenticatedRequest(method: String, path: String): Boolean {
            if (path == "/api/session" && method.equals("POST", ignoreCase = true)) return true
            return path in UNAUTHENTICATED_PATHS
        }
    }
}

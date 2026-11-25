package com.doorking.gate.core.network

import app.cash.turbine.test
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun signInSendsBearerClientHeaderAndRewritesBaseUrl() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl)
        val events = AuthEventBus()
        val client = ApiClientFactory.createOkHttp(storage, events)
        // Deliberately use a different placeholder base URL to verify dynamic URL rewriting.
        val api = ApiClientFactory.createApi(client, baseUrl = "https://placeholder.invalid/")

        val token = "dks_${"a".repeat(64)}"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"sessionToken":"$token"}"""),
        )

        val response = api.signIn(LoginRequest(user = "alice", password = "secret-password"))
        assertTrue(response.isSuccessful)
        assertEquals(token, response.body()?.sessionToken)

        val recorded = server.takeRequest()
        assertEquals("/api/session", recorded.path)
        assertEquals("bearer", recorded.getHeader("X-Client-Auth"))
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun authenticatedRequestAttachesBearerTokenAndClearsOn401() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = "dks_${"b".repeat(64)}"
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = token)
        val events = AuthEventBus()
        val client = ApiClientFactory.createOkHttp(storage, events)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error":"Session expired. Sign in again."}"""),
        )

        events.events.test {
            val result = runCatching { api.listGates() }
            assertTrue(result.isFailure)
            assertEquals(AuthEvent.SessionExpired, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val recorded = server.takeRequest()
        assertEquals("Bearer $token", recorded.getHeader("Authorization"))
        assertNull(storage.currentSessionToken())
    }

    @Test
    fun parallel401ResponsesDeduplicateSessionExpiredEvent() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = "dks_${"f".repeat(64)}"
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = token)
        val events = AuthEventBus()
        val client = ApiClientFactory.createOkHttp(storage, events)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"error":"Session expired"}"""),
            )
        }

        events.events.test {
            listOf(
                async { runCatching { api.listGates() } },
                async { runCatching { api.listGates() } },
                async { runCatching { api.listGates() } },
            ).awaitAll()
            assertEquals(AuthEvent.SessionExpired, awaitItem())
            expectNoEvents()
        }
        assertNull(storage.currentSessionToken())
    }

    @Test
    fun unauthenticatedAllowlistEndpointsNeverReceiveBearerTokenEvenWhenSignedIn() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = "dks_${"1".repeat(64)}"
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = token)
        val events = AuthEventBus()
        val client = ApiClientFactory.createOkHttp(storage, events)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        repeat(5) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"url":"https://idp.example.com/auth","nativeVerifier":"v1","message":"sent","username":"alice"}"""),
            )
        }

        api.checkInviteAtUrl("$baseUrl/api/invite/check", InviteCheckRequest(token = "x".repeat(32)))
        api.requestPasswordReset(PasswordResetRequest(identifier = "alice"))
        api.startSso(SsoStartRequest(provider = "google", native = true))
        api.getPasskeySignInOptions()
        api.signIn(LoginRequest(user = "alice", password = "password12345"))

        repeat(5) {
            val req = server.takeRequest()
            assertNull("Expected no Authorization header on ${req.method} ${req.path}", req.getHeader("Authorization"))
        }
    }

    @Test
    fun bearerTokenIsNotSentToDifferentOriginAndExplicitUrlIsNotRewritten() = runTest {
        val primaryUrl = server.url("/").toString().trimEnd('/')
        val otherServer = MockWebServer()
        otherServer.start()
        try {
            val otherUrl = otherServer.url("/").toString().trimEnd('/')
            val token = "dks_${"2".repeat(64)}"
            val storage = FakeSessionStorage(initialServerUrl = primaryUrl, initialToken = token)
            val events = AuthEventBus()
            val client = ApiClientFactory.createOkHttp(storage, events)
            val api = ApiClientFactory.createApi(client, baseUrl = "$primaryUrl/")

            otherServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"username":"bob","isResident":true,"email":"bob@example.com"}"""),
            )

            val res = api.checkInviteAtUrl("$otherUrl/api/invite/check", InviteCheckRequest(token = "x".repeat(32)))
            assertTrue(res.isSuccessful)
            assertEquals("bob", res.body()?.username)

            // Hit otherServer (not rewritten to primaryUrl), without Authorization or internal header.
            assertEquals(0, server.requestCount)
            val recorded = otherServer.takeRequest()
            assertEquals("/api/invite/check", recorded.path)
            assertNull(recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader(DoorkingAuthInterceptor.HEADER_EXPLICIT_URL))

            // Switching serverUrl without clearing token must NOT send the old token to the new origin.
            storage.setServerUrl(otherUrl)
            otherServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"gates":[]}"""),
            )
            api.listGates()
            val crossOriginReq = otherServer.takeRequest()
            assertNull(crossOriginReq.getHeader("Authorization"))
        } finally {
            otherServer.shutdown()
        }
    }

    @Test
    fun legacyMigratedTokenWithoutSavedOriginUsesServerUrlOrigin() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = "dks_${"3".repeat(64)}"
        val storage = FakeSessionStorage(
            initialServerUrl = baseUrl,
            initialToken = token,
            initialTokenServerOrigin = null,
        )
        val events = AuthEventBus()
        val client = ApiClientFactory.createOkHttp(storage, events)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"gates":[]}"""),
        )
        api.listGates()
        val req = server.takeRequest()
        assertEquals("Bearer $token", req.getHeader("Authorization"))
    }

    @Test
    fun stepUp403InvokesCoordinatorAndRetriesOnce() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = "dks_${"c".repeat(64)}"
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = token)
        val events = AuthEventBus()
        var stepUpPrompted = 0
        val interceptor = DoorkingAuthInterceptor(storage, events, ApiClientFactory.json) { err ->
            stepUpPrompted += 1
            err.stepUp && err.hasPasskey
        }
        val client = ApiClientFactory.createOkHttp(storage, events, authInterceptor = interceptor)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"stepUp":true,"hasPasskey":true,"error":"Confirm with passkey"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}"""),
        )

        val reply = api.startSend("2145550100")
        assertTrue(reply.ok)
        assertEquals(1, stepUpPrompted)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun noInteractiveStepUpHeaderSkipsCoordinatorAndIsStrippedUpstream() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = "dks_${"4".repeat(64)}"
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = token)
        val events = AuthEventBus()
        var stepUpPrompted = 0
        val interceptor = DoorkingAuthInterceptor(storage, events, ApiClientFactory.json) {
            stepUpPrompted += 1
            true
        }
        val client = ApiClientFactory.createOkHttp(storage, events, authInterceptor = interceptor)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"stepUp":true,"hasPasskey":true,"error":"Confirm with passkey"}"""),
        )

        val res = api.portalBuzz("2145550100", BuzzRequest(relay = 0), noInteractiveStepUp = "1")
        assertFalse(res.isSuccessful)
        assertEquals(403, res.code())
        assertEquals(0, stepUpPrompted)
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertNull(req.getHeader(DoorkingAuthInterceptor.HEADER_NO_INTERACTIVE_STEP_UP))
    }

    @Test
    fun mustChangePassword403EmitsGlobalEvent() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = "dks_${"d".repeat(64)}"
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = token)
        val events = AuthEventBus()
        val client = ApiClientFactory.createOkHttp(storage, events)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error":"Choose a new password first","mustChangePassword":true}"""),
        )

        events.events.test {
            val result = runCatching { api.listGates() }
            assertTrue(result.isFailure)
            assertEquals(AuthEvent.MustChangePassword, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun passkeyOptionsSendsJsonContentTypeAndNativeSsoExchangeSendsBearerHeader() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl)
        val events = AuthEventBus()
        val client = ApiClientFactory.createOkHttp(storage, events)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"challenge":"abc","rpId":"gate.example.com"}"""),
        )
        val optRes = api.getPasskeySignInOptions()
        assertTrue(optRes.isSuccessful)
        val optReq = server.takeRequest()
        assertTrue(optReq.getHeader("Content-Type").orEmpty().startsWith("application/json"))

        val token = "dks_${"e".repeat(64)}"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"sessionToken":"$token"}"""),
        )
        val ssoRes = api.exchangeNativeSso(NativeSsoExchangeRequest(code = "c1", verifier = "v1"))
        assertTrue(ssoRes.isSuccessful)
        assertEquals(token, ssoRes.body()?.sessionToken)
        val ssoReq = server.takeRequest()
        assertEquals("bearer", ssoReq.getHeader("X-Client-Auth"))
    }

    @Test
    fun googleSsoEndpointsOmitAuthorizationAndIdTokenRequestsBearerSession() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val existingToken = "dks_${"5".repeat(64)}"
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = existingToken)
        val events = AuthEventBus()
        val client = ApiClientFactory.createOkHttp(storage, events)
        val api = ApiClientFactory.createApi(client, baseUrl = "$baseUrl/")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"nonce":"n-1","clientId":"cid.apps.googleusercontent.com","hostedDomain":"example.com"}"""),
        )
        val nonceRes = api.getGoogleSsoNonce(GoogleNonceRequest(provider = "google"))
        assertTrue(nonceRes.isSuccessful)
        assertEquals("n-1", nonceRes.body()?.nonce)
        assertEquals("cid.apps.googleusercontent.com", nonceRes.body()?.clientId)
        assertEquals("example.com", nonceRes.body()?.hostedDomain)

        val nonceReq = server.takeRequest()
        assertEquals("/api/sso/google-nonce", nonceReq.path)
        assertNull(nonceReq.getHeader("Authorization"))
        assertNull(nonceReq.getHeader("X-Client-Auth"))

        val issuedToken = "dks_${"6".repeat(64)}"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"sessionToken":"$issuedToken"}"""),
        )
        val tokenRes = api.signInWithGoogleIdToken(
            GoogleIdTokenRequest(provider = "google", idToken = "jwt-token", nonce = "n-1"),
        )
        assertTrue(tokenRes.isSuccessful)
        assertEquals(issuedToken, tokenRes.body()?.sessionToken)

        val tokenReq = server.takeRequest()
        assertEquals("/api/sso/google-id-token", tokenReq.path)
        assertNull(tokenReq.getHeader("Authorization"))
        assertEquals("bearer", tokenReq.getHeader("X-Client-Auth"))
    }

    @Test
    fun mediaAndCameraTestOkHttpClientsHaveIsolatedDispatcherNoStepUpAnd90sTimeout() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = "dks_${"9".repeat(64)}"
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = token)
        val events = AuthEventBus()
        var stepUpPrompted = 0
        val primaryInterceptor = DoorkingAuthInterceptor(storage, events, ApiClientFactory.json) {
            stepUpPrompted += 1
            true
        }
        val primaryClient = ApiClientFactory.createOkHttp(storage, events, authInterceptor = primaryInterceptor)
        val mediaClient = ApiClientFactory.createMediaOkHttp(primaryClient, storage, events)
        val cameraTestClient = ApiClientFactory.createCameraTestOkHttp(primaryClient)

        assertTrue(mediaClient.dispatcher !== primaryClient.dispatcher)
        assertEquals(90_000, cameraTestClient.readTimeoutMillis)

        // Media client must never invoke interactive stepUpCoordinator on 403 { stepUp: true }.
        val mediaApi = ApiClientFactory.createApi(mediaClient, baseUrl = "$baseUrl/")
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"stepUp":true,"hasPasskey":true,"error":"Confirm with passkey"}"""),
        )
        val res = mediaApi.portalBuzz("2145550100", BuzzRequest(relay = 0))
        assertEquals(403, res.code())
        assertEquals(0, stepUpPrompted)
    }
}

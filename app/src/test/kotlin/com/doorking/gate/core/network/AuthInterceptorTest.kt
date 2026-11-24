package com.doorking.gate.core.network

import app.cash.turbine.test
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
}

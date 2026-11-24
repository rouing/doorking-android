package com.doorking.gate.widget

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GateWidgetRepositoryTest {

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

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun returnsSignedOutSnapshotWhenNoTokenStored() = runBlocking {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = null)
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        val snap = GateWidgetRepository.fetchSnapshot(storage, api)
        assertFalse(snap.signedIn)
        assertFalse(snap.canBuzz)
        assertEquals("Signed out", snap.statusText)
    }

    @Test
    fun resolvesResidentReadySnapshotAndBuzzesGate() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/session" -> json(
                    200,
                    """{"loggedIn":true,"user":"res_101","isResidentOnly":true,"gate":{"phoneNumber":"2145550100","siteName":"Oakridge","role":"resident"}}""",
                )
                request.path!!.startsWith("/api/portal/gates/2145550100/me") -> json(
                    200,
                    """{"gate":{"phoneNumber":"2145550100","siteName":"Oakridge"},"unit":{"directoryCode":"101","name":"Jordan"},"portal":{"enabled":true,"buzzEnabled":true,"buzzRelays":[0],"buzzRelayLabels":[{"relay":0,"name":"Main Gate"}]},"reroll":{"remainingToday":1}}""",
                )
                request.path == "/api/portal/gates/2145550100/status" -> json(
                    200,
                    """{"available":true,"cooldownSeconds":0}""",
                )
                request.path == "/api/portal/gates/2145550100/buzz" -> json(
                    200,
                    """{"ok":true,"alreadyOpen":false}""",
                )
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"5".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        val snap = GateWidgetRepository.fetchSnapshot(storage, api)
        assertTrue(snap.signedIn)
        assertTrue(snap.canBuzz)
        assertEquals("Oakridge", snap.gateName)
        assertEquals("Main Gate", snap.buzzRelayName)
        assertEquals("Ready", snap.statusText)

        val outcome = GateWidgetRepository.buzz(api, "2145550100", 0, "Main Gate", "101")
        assertTrue(outcome is WidgetBuzzOutcome.Opened)
        assertEquals("Main Gate opened!", (outcome as WidgetBuzzOutcome.Opened).message)
    }

    @Test
    fun reportsBusyAndCooldownFailureCleanly() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/session" -> json(
                    200,
                    """{"loggedIn":true,"user":"res_101","isResidentOnly":true,"gate":{"phoneNumber":"2145550100","siteName":"Oakridge","role":"resident"}}""",
                )
                request.path!!.startsWith("/api/portal/gates/2145550100/me") -> json(
                    200,
                    """{"gate":{"phoneNumber":"2145550100","siteName":"Oakridge"},"unit":{"directoryCode":"101","name":"Jordan"},"portal":{"enabled":true,"buzzEnabled":true,"buzzRelays":[0],"buzzRelayLabels":[{"relay":0,"name":"Main Gate"}]},"reroll":{"remainingToday":1}}""",
                )
                request.path == "/api/portal/gates/2145550100/status" -> json(
                    200,
                    """{"available":false,"busyReason":"pulling residents","percent":45,"cooldownSeconds":0}""",
                )
                request.path == "/api/portal/gates/2145550100/buzz" -> json(
                    429,
                    """{"error":"Please wait 18 seconds before opening the gate again."}""",
                )
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"6".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        val snap = GateWidgetRepository.fetchSnapshot(storage, api)
        assertFalse(snap.canBuzz)
        assertEquals("Busy 45%", snap.statusText)

        val failed = GateWidgetRepository.buzz(api, "2145550100", 0, "Main Gate", "101")
        assertTrue(failed is WidgetBuzzOutcome.Failed)
        assertTrue((failed as WidgetBuzzOutcome.Failed).message.contains("18 seconds"))
    }
}

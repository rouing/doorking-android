package com.doorking.gate.feature.portal

import androidx.lifecycle.viewModelScope
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ResidentPortalViewModelTest {

    private lateinit var server: MockWebServer
    private val mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val activeViewModels = mutableListOf<ResidentPortalViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThread)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        activeViewModels.forEach { it.viewModelScope.cancel() }
        activeViewModels.clear()
        server.shutdown()
        Dispatchers.resetMain()
        mainThread.close()
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun loadsPortalBuzzesGateAndRerollsCode() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/portal/gates/2145550100/me") -> json(
                    200,
                    """
                    {
                      "gate":{"phoneNumber":"2145550100","siteName":"Oakridge Estates","adminPhone":"2145550199","grantAccessDigit":"9"},
                      "units":[{"directoryCode":"101","name":"Smith"}],
                      "unit":{"directoryCode":"101","name":"Smith","phone":"2145550177","entryCode":"4821","hidden":false,"cards":[{"number":"1234","device":"Fob"}],"email":"smith@unit.test"},
                      "portal":{"enabled":true,"buzzEnabled":true,"buzzRelays":[0],"pulseSeconds":2,"maxRerollsPerDay":1,"allowRequests":true,"buzzRelayLabels":[{"relay":0,"name":"Main Gate"}]},
                      "reroll":{"usedToday":0,"maxPerDay":1,"remainingToday":1,"pendingSend":false,"nextScheduledSend":null}
                    }
                    """.trimIndent(),
                )
                request.path == "/api/portal/gates/2145550100/status" -> json(
                    200,
                    """{"available":true,"cooldownSeconds":0}""",
                )
                request.path!!.startsWith("/api/portal/gates/2145550100/activity") && request.method == "GET" -> json(
                    200,
                    """{"rows":[{"ts":1700000000000,"date":"11/22","time":"12:00","eventType":"Portal Open","access":"Admit","source":"portal","txKey":"portal:1"}],"showSnapshots":false}""",
                )
                request.path == "/api/portal/gates/2145550100/requests" -> json(200, """{"requests":[]}""")
                request.path == "/api/portal/gates/2145550100/buzz" -> json(200, """{"ok":true,"alreadyOpen":false}""")
                request.path == "/api/portal/gates/2145550100/reroll-code" -> json(
                    200,
                    """{"entryCode":"7391","remainingToday":0,"nextScheduledSend":"2026-11-23T08:00:00Z"}""",
                )
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"3".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = ResidentPortalViewModel("2145550100", api, pollIntervalMs = 60_000L).also { activeViewModels += it }
        val loaded = withTimeout(5_000) { vm.uiState.first { !it.loading } }
        assertEquals("4821", loaded.me?.unit?.entryCode)
        assertEquals(1, loaded.activity.size)

        vm.buzz(0, "Main Gate")
        val buzzed = withTimeout(5_000) { vm.uiState.first { it.bannerMessage?.contains("Main Gate opened") == true } }
        assertTrue(buzzed.error == null)

        vm.rerollCode()
        val rerolled = withTimeout(5_000) { vm.uiState.first { it.me?.unit?.entryCode == "7391" } }
        assertEquals(0, rerolled.me?.reroll?.remainingToday)
        assertTrue(rerolled.me?.reroll?.pendingSend == true)
    }

    @Test
    fun ticksCooldownLocallyAndStopsPollingWhenAvailableAndIdle() = runBlocking {
        val statusCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/portal/gates/2145550100/me") -> json(
                    200,
                    """
                    {
                      "gate":{"phoneNumber":"2145550100","siteName":"Oakridge Estates","adminPhone":"2145550199","grantAccessDigit":"9"},
                      "units":[{"directoryCode":"101","name":"Smith"}],
                      "unit":{"directoryCode":"101","name":"Smith","phone":"2145550177","entryCode":"4821","hidden":false,"cards":[],"email":"smith@unit.test"},
                      "portal":{"enabled":true,"buzzEnabled":true,"buzzRelays":[0],"pulseSeconds":2,"maxRerollsPerDay":1,"allowRequests":true,"buzzRelayLabels":[{"relay":0,"name":"Main Gate"}]},
                      "reroll":{"usedToday":0,"maxPerDay":1,"remainingToday":1,"pendingSend":false,"nextScheduledSend":null}
                    }
                    """.trimIndent(),
                )
                request.path == "/api/portal/gates/2145550100/status" -> {
                    val call = statusCalls.incrementAndGet()
                    if (call <= 2) {
                        json(200, """{"available":true,"cooldownSeconds":3}""")
                    } else {
                        json(200, """{"available":true,"cooldownSeconds":0}""")
                    }
                }
                request.path!!.startsWith("/api/portal/gates/2145550100/activity") -> json(200, """{"rows":[],"showSnapshots":false}""")
                request.path == "/api/portal/gates/2145550100/requests" -> json(200, """{"requests":[]}""")
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"6".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = ResidentPortalViewModel("2145550100", api, pollIntervalMs = 200L, cooldownTickMs = 25L).also { activeViewModels += it }
        withTimeout(5_000) { vm.uiState.first { !it.loading } }
        assertEquals(3, vm.uiState.value.status.cooldownSeconds)

        // Enabling polling on Gate tab ticks cooldown down locally to 0 without spinning forever.
        vm.setStatusPollingEnabled(true)
        val ready = withTimeout(5_000) { vm.uiState.first { it.status.available && it.status.cooldownSeconds == 0 } }
        assertEquals(0, ready.status.cooldownSeconds)

        val callsAfterIdle = statusCalls.get()
        delay(300L)
        assertTrue(statusCalls.get() <= callsAfterIdle + 1)

        vm.setStatusPollingEnabled(false)
    }
}

package com.doorking.gate.feature.gates

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.GateConfigDto
import com.doorking.gate.core.network.GateInfoDto
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class GatePickerViewModelTest {

    private lateinit var server: MockWebServer
    private val mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThread)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        mainThread.close()
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .addHeader("Content-Type", "application/json")
            .setBody(body)

    @Test
    fun loadsGatesAndOpensSelectedGate() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/gates" -> jsonResponse(
                    200,
                    """{"gates":[{"phoneNumber":"2145550100","siteName":"Oakridge Estates","role":"admin"}]}""",
                )
                "/api/gate-add" -> jsonResponse(200, """{"job":null}""")
                "/api/session/gate" -> jsonResponse(200, """{"ok":true}""")
                else -> jsonResponse(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"1".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = GatePickerViewModel(api, storage, pollIntervalMs = 20L)
        val loaded = withTimeout(5_000) { vm.uiState.first { !it.loading } }

        assertEquals(1, loaded.gates.size)
        assertEquals("Oakridge Estates", loaded.gates.first().siteName)
        assertNull(loaded.error)

        var opened: GateInfoDto? = null
        vm.openGate(loaded.gates.first()) { opened = it }
        withTimeout(5_000) {
            while (opened == null) delay(10)
        }

        assertEquals("2145550100", opened?.phoneNumber)
        assertEquals("2145550100", storage.state.value.lastGatePhone)
    }

    @Test
    fun addGatePollsUntilDoneAndRefreshesList() = runBlocking {
        val addPolls = AtomicInteger(0)
        val added = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/gates" && request.method == "GET" -> {
                    val body = if (added.get() > 0) {
                        """{"gates":[{"phoneNumber":"2145550200","siteName":"North Gate","role":"admin"}]}"""
                    } else {
                        """{"gates":[]}"""
                    }
                    jsonResponse(200, body)
                }
                request.path == "/api/gates" && request.method == "POST" -> {
                    jsonResponse(202, """{"ok":true}""")
                }
                request.path == "/api/gate-add" -> {
                    when (addPolls.getAndIncrement()) {
                        0 -> jsonResponse(200, """{"job":null}""")
                        1 -> jsonResponse(
                            200,
                            """{"job":{"state":"running","phoneNumber":"2145550200","log":["Connecting to DKS Cloud…"]}}""",
                        )
                        else -> {
                            added.set(1)
                            jsonResponse(
                                200,
                                """{"job":{"state":"done","phoneNumber":"2145550200","log":["Connecting to DKS Cloud…","Done"]}}""",
                            )
                        }
                    }
                }
                else -> jsonResponse(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"2".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = GatePickerViewModel(api, storage, pollIntervalMs = 20L)
        withTimeout(5_000) { vm.uiState.first { !it.loading } }

        vm.startAddGate("2145550200", "1234", "dksuser", "dkspass", GateConfigDto(siteName = "North Gate"))
        val finished = withTimeout(5_000) {
            vm.uiState.first { it.addJob?.state == "done" && it.gates.size == 1 }
        }

        assertEquals("done", finished.addJob?.state)
        assertEquals("North Gate", finished.gates.first().siteName)
    }

    @Test
    fun formatsTenDigitUsPhoneNumbers() {
        assertEquals("(214) 555-0100", formatUsPhone("2145550100"))
        assertEquals("555-0100", formatUsPhone("555-0100"))
    }

    @Test
    fun addGatePollingBacksOffAndStopsAfterRepeatedErrors() = runBlocking {
        val addPolls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/gates" && request.method == "GET" -> jsonResponse(200, """{"gates":[]}""")
                request.path == "/api/gates" && request.method == "POST" -> jsonResponse(202, """{"ok":true}""")
                request.path == "/api/gate-add" -> {
                    if (addPolls.getAndIncrement() == 0) {
                        jsonResponse(200, """{"job":null}""")
                    } else {
                        jsonResponse(500, """{"error":"temporary outage"}""")
                    }
                }
                else -> jsonResponse(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"7".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = GatePickerViewModel(api, storage, pollIntervalMs = 15L)
        withTimeout(5_000) { vm.uiState.first { !it.loading } }

        vm.startAddGate("2145550200", "1234", "dksuser", "dkspass", GateConfigDto(siteName = "North Gate"))
        val errored = withTimeout(5_000) { vm.uiState.first { it.addError != null } }
        assertNotNull(errored.addError)
        val countAtStop = addPolls.get()
        delay(80L)
        assertTrue(addPolls.get() <= countAtStop + 1)
    }
}

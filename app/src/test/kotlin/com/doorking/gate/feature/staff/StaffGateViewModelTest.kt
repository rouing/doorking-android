package com.doorking.gate.feature.staff

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class StaffGateViewModelTest {

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

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun loadsStaffGatePreviewsSendAndControlsRelay() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/gates/2145550100/residents" -> json(
                    200,
                    """{"rows":[{"directoryCode":"101","name":"Alice","type":"Resident","entryCode":"4821"}],"cache":{"exists":true,"edited":true,"conflicts":[]}}""",
                )
                request.path!!.startsWith("/api/gates/2145550100/data") -> json(
                    200,
                    """{"transactions":{"updated":"2026-11-22T00:00:00Z","rows":[{"ts":1700000000000,"minute":"20261122-1200","date":"11/22","time":"12:00","eventType":"Entry Code","access":"Admit","name":"Alice","directoryCode":"101","camera":true}],"next":null}}""",
                )
                request.path == "/api/gates/2145550100/prefs" -> json(200, """{"relayLabels":{"0":"Main Gate"}}""")
                request.path == "/api/gates/2145550100/cameras" -> json(200, """{"settings":{"enabled":true,"mode":"video","channels":[{"channel":1,"name":"Entry"}]}}""")
                request.path == "/api/gates/2145550100/snapshots" -> json(200, """{"snapshots":{}}""")
                request.path == "/api/gates/2145550100/job" -> json(200, """{"state":"idle","log":[]}""")
                request.path == "/api/gates/2145550100/send" && request.method == "GET" -> json(
                    200,
                    """{"changedBlocks":{"names.bin":[1]},"changes":[{"key":"101","label":"101 Alice","change":"changed","fields":[{"field":"Entry code","from":"1234","to":"4821"}]}]}""",
                )
                request.path == "/api/gates/2145550100/relays" && request.method == "PUT" -> json(
                    200,
                    """{"checkedAt":"2026-11-22T12:00:00Z","relays":[{"relay":0,"code":"1","state":"Active"}],"timers":[{"phone":"2145550100","relay":0,"releaseAt":"2026-11-22T12:30:00Z"}]}""",
                )
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"4".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = StaffGateViewModel("2145550100", api, storage, pollIntervalMs = 20L)
        val loaded = withTimeout(5_000) { vm.uiState.first { !it.loading } }
        assertEquals(1, loaded.residentsReply?.rows?.size)
        assertEquals(1, loaded.transactions.size)
        assertEquals("Main Gate", loaded.prefs.relayLabels["0"])

        vm.loadSendPreview()
        val previewed = withTimeout(5_000) { vm.uiState.first { it.sendPreview != null } }
        assertEquals(1, previewed.sendPreview?.changes?.size)

        vm.toggleRelay(relay = 0, holdOpen = true, holdMinutes = 30)
        val held = withTimeout(5_000) { vm.uiState.first { it.relays.any { r -> r.relay == 0 && r.code == "1" } } }
        assertEquals(1, held.relayTimers.size)
    }

    @Test
    fun safeEntryCodeGeneratorAvoidsWeakAndTakenCodes() {
        assertTrue(StaffGateViewModel.isWeakEntryCode("1234"))
        assertTrue(StaffGateViewModel.isWeakEntryCode("0000"))
        assertTrue(StaffGateViewModel.isWeakEntryCode("1212"))
        assertFalse(StaffGateViewModel.isWeakEntryCode("4829"))

        val taken = setOf("4829", "7391")
        val generated = StaffGateViewModel.generateSafeEntryCode(taken)
        assertEquals(4, generated.length)
        assertFalse(generated in taken)
        assertFalse(StaffGateViewModel.isWeakEntryCode(generated))
    }
}

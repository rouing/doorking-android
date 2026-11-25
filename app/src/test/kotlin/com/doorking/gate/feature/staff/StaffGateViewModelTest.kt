package com.doorking.gate.feature.staff

import androidx.lifecycle.viewModelScope
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.JobDto
import com.doorking.gate.core.network.ResidentRowDto
import com.doorking.gate.core.network.TransactionRowDto
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class StaffGateViewModelTest {

    private lateinit var server: MockWebServer
    private val mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val activeViewModels = mutableListOf<StaffGateViewModel>()

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

        val vm = StaffGateViewModel("2145550100", api, storage, pollIntervalMs = 20L).also { activeViewModels += it }
        val loaded = withTimeout(5_000) { vm.uiState.first { !it.loading } }
        assertEquals(1, loaded.residentsReply?.rows?.size)
        assertEquals(1, loaded.transactions.size)
        assertEquals(1, loaded.visibleTransactions.size)
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

    @Test
    fun parsesJobProgressPercentAndSessionCompleteSuccess() {
        val connecting = parseJobProgress(
            JobDto(state = "running", what = "transactions", log = listOf("5:24:07 PM  Request reply: OK", "5:24:07 PM  Processing")),
        )
        assertEquals(null, connecting.percent)
        assertFalse(connecting.isComplete)

        val mid = parseJobProgress(
            JobDto(
                state = "running",
                what = "transactions",
                log = listOf("5:24:17 PM  Receive Tran: 10% complete", "5:24:59 PM  Receive Tran: 76% complete"),
            ),
        )
        assertEquals(76, mid.percent)
        assertEquals("Receive Tran", mid.statusLabel)
        assertFalse(mid.isComplete)

        val completeByLog = parseJobProgress(
            JobDto(
                state = "running",
                what = "transactions",
                log = listOf("5:25:10 PM  Receive Tran: 94% complete", "5:25:15 PM  Session Complete: Success"),
            ),
        )
        assertEquals(100, completeByLog.percent)
        assertTrue(completeByLog.isComplete)
    }

    @Test
    fun formatsFailedEntryCodeHeadlineWithAttemptedCode() {
        val failedCode = TransactionRowDto(
            ts = 1795542936000L,
            eventType = "Entry Code",
            access = "Deny",
            name = "",
            directoryCode = "",
            number = "9876",
        )
        assertEquals("Entry Code · #9876", formatTransactionHeadline(failedCode))

        val residentEntry = TransactionRowDto(
            ts = 1795542936000L,
            eventType = "Entry Code",
            access = "Admit",
            name = "Alice",
            directoryCode = "101",
            number = "4821",
        )
        assertEquals("Alice · #101", formatTransactionHeadline(residentEntry))
    }

    @Test
    fun hidesMatchingHoldBeginAndHoldEndForPortalOpenOnly() {
        val baseTs = 1795542936000L
        val rows = listOf(
            // Portal Open with matching Hold End + Hold Begin at same time -> hides both Hold rows
            TransactionRowDto(ts = baseTs, minute = "20261124-1155", eventType = "Portal Open", access = "Admit", name = "Cody", directoryCode = "701", relay = "Entry", source = "portal"),
            TransactionRowDto(ts = baseTs - 36_000L, minute = "20261124-1155", eventType = "Hold End", access = "Rels", relay = "Entry"),
            TransactionRowDto(ts = baseTs - 36_000L, minute = "20261124-1155", eventType = "Hold Begin", access = "Hold", relay = "Entry"),
            // Standalone manual 30-min Hold Open -> stays visible
            TransactionRowDto(ts = baseTs - 3_600_000L, minute = "20261124-1055", eventType = "Hold End", access = "Rels", relay = "Entry"),
            TransactionRowDto(ts = baseTs - 5_400_000L, minute = "20261124-1025", eventType = "Hold Begin", access = "Hold", relay = "Entry"),
        )
        val filtered = hidePortalHoldNoise(rows)
        assertEquals(3, filtered.size)
        assertEquals("Portal Open", filtered[0].eventType)
        assertEquals("Hold End", filtered[1].eventType)
        assertEquals("Hold Begin", filtered[2].eventType)
    }

    @Test
    fun hidesReservedUnit000AndEmptyDirectorySlots() {
        assertFalse(isVisibleDirectoryRow(ResidentRowDto(directoryCode = "000", name = "", type = "Resident", hidden = true)))
        assertFalse(isVisibleDirectoryRow(ResidentRowDto(directoryCode = "000", name = "System", type = "Resident")))
        assertFalse(isVisibleDirectoryRow(ResidentRowDto(directoryCode = "102", name = "", phone = "", entryCode = "", cards = emptyList())))
        assertTrue(isVisibleDirectoryRow(ResidentRowDto(directoryCode = "101", name = "Alice", type = "Resident")))
        assertTrue(isVisibleDirectoryRow(ResidentRowDto(directoryCode = "020", name = "", cards = listOf("19917"))))
    }

    @Test
    fun jobPollingPausesWhenInactiveAndCapsConsecutiveErrors() = runBlocking {
        val jobCalls = AtomicInteger(0)
        val failJobs = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/gates/2145550100/residents" -> json(
                    200,
                    """{"rows":[],"cache":{"exists":true,"edited":false,"conflicts":[]}}""",
                )
                request.path!!.startsWith("/api/gates/2145550100/data") -> json(
                    200,
                    """{"transactions":{"updated":"2026-11-22T00:00:00Z","rows":[],"next":null}}""",
                )
                request.path == "/api/gates/2145550100/prefs" -> json(200, """{}""")
                request.path == "/api/gates/2145550100/cameras" -> json(200, """{"settings":null}""")
                request.path == "/api/gates/2145550100/snapshots" -> json(200, """{"snapshots":{}}""")
                request.path == "/api/gates/2145550100/job" -> {
                    jobCalls.incrementAndGet()
                    if (failJobs.get() > 0) {
                        json(500, """{"error":"server error"}""")
                    } else {
                        json(200, """{"state":"running","what":"residents","log":["Working…"]}""")
                    }
                }
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"5".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = StaffGateViewModel("2145550100", api, storage, pollIntervalMs = 15L, snapPollIntervalMs = 15L).also { activeViewModels += it }
        withTimeout(5_000) { vm.uiState.first { !it.loading && it.job?.state == "running" } }

        vm.setActive(false)
        val countWhenPaused = jobCalls.get()
        delay(80L)
        assertTrue(jobCalls.get() <= countWhenPaused + 1)

        failJobs.set(1)
        vm.setActive(true)
        val errored = withTimeout(5_000) { vm.uiState.first { it.error != null } }
        assertNotNull(errored.error)
        assertTrue(errored.error!!.contains("Lost connection"))
    }

    @Test
    fun throttlesIdleResumeJobRefreshToMinimumInterval() = runBlocking {
        val jobCalls = AtomicInteger(0)
        val snapCalls = AtomicInteger(0)
        var nowMs = 10_000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/gates/2145550100/residents" -> json(
                    200,
                    """{"rows":[],"cache":{"exists":true,"edited":false,"conflicts":[]}}""",
                )
                request.path!!.startsWith("/api/gates/2145550100/data") -> json(
                    200,
                    """{"transactions":{"updated":"2026-11-22T00:00:00Z","rows":[],"next":null}}""",
                )
                request.path == "/api/gates/2145550100/prefs" -> json(200, """{}""")
                request.path == "/api/gates/2145550100/cameras" -> json(
                    200,
                    """{"settings":{"enabled":true,"mode":"video","channels":[]}}""",
                )
                request.path == "/api/gates/2145550100/snapshots" -> {
                    snapCalls.incrementAndGet()
                    json(200, """{"snapshots":{}}""")
                }
                request.path == "/api/gates/2145550100/job" -> {
                    jobCalls.incrementAndGet()
                    json(200, """{"state":"idle","log":[]}""")
                }
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"6".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = StaffGateViewModel(
            gatePhone = "2145550100",
            api = api,
            sessionStorage = storage,
            resumeRefreshMinIntervalMs = 15_000L,
            clockMs = { nowMs },
        ).also { activeViewModels += it }
        withTimeout(5_000) { vm.uiState.first { !it.loading } }
        val jobsAfterLoad = jobCalls.get()
        val snapsAfterLoad = snapCalls.get()

        // Resuming within 15s while idle must be throttled.
        vm.setActive(false)
        nowMs += 5_000L
        vm.setActive(true)
        delay(80L)
        assertEquals(jobsAfterLoad, jobCalls.get())
        assertEquals(snapsAfterLoad, snapCalls.get())

        // Resuming once >= 15s has elapsed refreshes /job and /snapshots once.
        vm.setActive(false)
        nowMs += 10_000L
        vm.setActive(true)
        withTimeout(5_000) {
            while (jobCalls.get() <= jobsAfterLoad || snapCalls.get() <= snapsAfterLoad) delay(10L)
        }
        assertEquals(jobsAfterLoad + 1, jobCalls.get())
        assertEquals(snapsAfterLoad + 1, snapCalls.get())

        // Another quick pause/resume resets the 15s throttle window.
        vm.setActive(false)
        nowMs += 2_000L
        vm.setActive(true)
        delay(80L)
        assertEquals(jobsAfterLoad + 1, jobCalls.get())
        assertEquals(snapsAfterLoad + 1, snapCalls.get())
    }

    @Test
    fun precomputesVisibleTransactionsOnLoadAndPagination() = runBlocking {
        val baseTs = 1795542936000L
        val olderTs = baseTs - 300_000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/gates/2145550100/residents" -> json(
                    200,
                    """{"rows":[],"cache":{"exists":true,"edited":false,"conflicts":[]}}""",
                )
                request.path == "/api/gates/2145550100/data" -> json(
                    200,
                    """
                    {
                      "transactions":{
                        "updated":"2026-11-24T12:00:00Z",
                        "rows":[
                          {"ts":$baseTs,"minute":"20261124-1155","date":"11/24","time":"11:55","eventType":"Portal Open","access":"Admit","name":"Cody","directoryCode":"701","relay":"Entry","source":"portal"},
                          {"ts":${baseTs - 20_000L},"minute":"20261124-1155","date":"11/24","time":"11:55","eventType":"Hold End","access":"Rels","relay":"Entry"},
                          {"ts":${baseTs - 20_000L},"minute":"20261124-1155","date":"11/24","time":"11:55","eventType":"Hold Begin","access":"Hold","relay":"Entry"},
                          {"ts":${baseTs - 60_000L},"minute":"20261124-1154","date":"11/24","time":"11:54","eventType":"Entry Code","access":"Admit","name":"Alice","directoryCode":"101"}
                        ],
                        "next":$olderTs
                      }
                    }
                    """.trimIndent(),
                )
                request.path!!.startsWith("/api/gates/2145550100/data?before=") -> json(
                    200,
                    """
                    {
                      "transactions":{
                        "updated":"2026-11-24T12:00:00Z",
                        "rows":[
                          {"ts":$olderTs,"minute":"20261124-1150","date":"11/24","time":"11:50","eventType":"Portal Open","access":"Admit","name":"Bob","directoryCode":"202","relay":"Entry","source":"portal"},
                          {"ts":${olderTs - 15_000L},"minute":"20261124-1150","date":"11/24","time":"11:50","eventType":"Hold End","access":"Rels","relay":"Entry"},
                          {"ts":${olderTs - 15_000L},"minute":"20261124-1150","date":"11/24","time":"11:50","eventType":"Hold Begin","access":"Hold","relay":"Entry"}
                        ],
                        "next":null
                      }
                    }
                    """.trimIndent(),
                )
                request.path == "/api/gates/2145550100/prefs" -> json(200, """{}""")
                request.path == "/api/gates/2145550100/cameras" -> json(200, """{"settings":null}""")
                request.path == "/api/gates/2145550100/snapshots" -> json(200, """{"snapshots":{}}""")
                request.path == "/api/gates/2145550100/job" -> json(200, """{"state":"idle","log":[]}""")
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"7".repeat(64)}")
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            baseUrl = "$baseUrl/",
        )

        val vm = StaffGateViewModel("2145550100", api, storage).also { activeViewModels += it }
        val loaded = withTimeout(5_000) { vm.uiState.first { !it.loading } }
        assertEquals(4, loaded.transactions.size)
        assertEquals(2, loaded.visibleTransactions.size)

        vm.loadOlderTransactions()
        val paginated = withTimeout(5_000) { vm.uiState.first { it.txNextBefore == null && !it.loadingOlderTx } }
        assertEquals(7, paginated.transactions.size)
        assertEquals(3, paginated.visibleTransactions.size)
    }
}

package com.doorking.gate.widget

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.DoorkingAuthInterceptor
import com.doorking.gate.core.network.StepUpCoordinator
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        assertEquals(0, snap.cooldownSeconds)

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

    @Test
    fun populatesAllVisibleRelaysInSnapshotWhileKeepingPrimaryRelay() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/session" -> json(
                    200,
                    """{"loggedIn":true,"user":"res_101","isResidentOnly":true,"gate":{"phoneNumber":"2145550100","siteName":"Oakridge","role":"resident"}}""",
                )
                request.path!!.startsWith("/api/portal/gates/2145550100/me") -> json(
                    200,
                    """
                    {
                      "gate":{"phoneNumber":"2145550100","siteName":"Oakridge"},
                      "unit":{"directoryCode":"101","name":"Jordan"},
                      "portal":{
                        "enabled":true,
                        "buzzEnabled":true,
                        "buzzRelays":[0,1,2],
                        "buzzRelayLabels":[
                          {"relay":0,"name":"Main Gate"},
                          {"relay":1,"name":"Exit Gate"},
                          {"relay":2,"name":"Walk Gate"}
                        ]
                      },
                      "reroll":{"remainingToday":1}
                    }
                    """.trimIndent(),
                )
                request.path == "/api/portal/gates/2145550100/status" -> json(
                    200,
                    """{"available":true,"cooldownSeconds":0}""",
                )
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"7".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        val snap = GateWidgetRepository.fetchSnapshot(storage, api)
        assertTrue(snap.signedIn)
        assertTrue(snap.canBuzz)
        assertEquals(0, snap.buzzRelay)
        assertEquals("Main Gate", snap.buzzRelayName)
        assertEquals(
            listOf(
                WidgetRelayEntry(0, "Main Gate"),
                WidgetRelayEntry(1, "Exit Gate"),
                WidgetRelayEntry(2, "Walk Gate"),
            ),
            snap.relays,
        )
    }

    @Test
    fun buzzSkipsInteractiveStepUpPromptAndReturnsFailureMessage() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/portal/gates/2145550100/buzz" -> json(
                    403,
                    """{"error":"Step-up confirmation required","stepUp":true}""",
                )
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"8".repeat(64)}")
        var stepUpPrompted = false
        val interceptor = DoorkingAuthInterceptor(
            sessionStorage = storage,
            authEvents = AuthEventBus(),
            json = ApiClientFactory.json,
            stepUpCoordinator = StepUpCoordinator {
                stepUpPrompted = true
                false
            },
        )
        val okHttp = ApiClientFactory.createOkHttp(storage, AuthEventBus(), authInterceptor = interceptor)
        val api = ApiClientFactory.createApi(okHttp, "$baseUrl/")

        val outcome = GateWidgetRepository.buzz(
            api = api,
            gatePhone = "2145550100",
            relay = 0,
            relayName = "Main Gate",
            unitCode = "101",
            noInteractiveStepUp = "1",
        )
        assertFalse("Background widget buzz must not trigger interactive step-up UI", stepUpPrompted)
        assertTrue(outcome is WidgetBuzzOutcome.Failed)
        assertTrue((outcome as WidgetBuzzOutcome.Failed).message.contains("Step-up"))
    }

    @Test
    fun buzzReportsAlreadyOpenMessageCleanly() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/portal/gates/2145550100/buzz" -> json(
                    200,
                    """{"ok":true,"alreadyOpen":true}""",
                )
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"9".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        val outcome = GateWidgetRepository.buzz(api, "2145550100", 0, "Main Gate", "101")
        assertTrue(outcome is WidgetBuzzOutcome.Opened)
        assertEquals("Main Gate already open", (outcome as WidgetBuzzOutcome.Opened).message)
    }

    @Test
    fun fetchSnapshotClearsTokenWhenSessionReportsLoggedOut() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/session" -> json(200, """{"loggedIn":false,"user":""}""")
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"a".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        val snap = GateWidgetRepository.fetchSnapshot(storage, api)
        assertFalse(snap.signedIn)
        assertNull(storage.awaitLoaded().sessionToken)
    }

    @Test
    fun pendingPinConfigRoundTripsThroughJson() {
        val original = PendingPinConfig(
            gatePhone = "2145550100",
            gateName = "Oakridge",
            relay = 2,
            relayName = "Walk Gate",
            unitCode = "101",
            createdAtMs = 1_700_000_000_000L,
        )
        val encoded = ApiClientFactory.json.encodeToString(original)
        val decoded = ApiClientFactory.json.decodeFromString<PendingPinConfig>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun buzzPropagatesCancellationException() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
                return json(200, """{"ok":true}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"b".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        var caughtCancellation = false
        var returnedOutcome: WidgetBuzzOutcome? = null
        val job = launch(Dispatchers.IO) {
            try {
                returnedOutcome = GateWidgetRepository.buzz(api, "2145550100", 0, "Main Gate", "101")
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        try {
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("CancellationException must propagate instead of returning Failed", caughtCancellation)
            assertNull(returnedOutcome)
        } finally {
            releaseServer.countDown()
        }
    }

    @Test
    fun resolveTinyTileStateUsesMutedStylingWhenSignedOutOrUnavailable() {
        val signedOut = resolveTinyTileState(GateWidgetSnapshot(signedIn = false, canBuzz = false))
        assertEquals(TinyTileStyle.MUTED, signedOut.style)
        assertEquals("Sign in", signedOut.label)

        val unavailable = resolveTinyTileState(
            GateWidgetSnapshot(signedIn = true, canBuzz = false, statusText = "Busy 45%", buzzRelayName = "Main Gate"),
        )
        assertEquals(TinyTileStyle.MUTED, unavailable.style)
        assertEquals("Busy 45%", unavailable.label)

        val unavailableBlankStatus = resolveTinyTileState(
            GateWidgetSnapshot(signedIn = true, canBuzz = false, statusText = "  ", buzzRelayName = "Main Gate"),
        )
        assertEquals(TinyTileStyle.MUTED, unavailableBlankStatus.style)
        assertEquals("Gate", unavailableBlankStatus.label)

        val active = resolveTinyTileState(
            GateWidgetSnapshot(signedIn = true, canBuzz = true, buzzRelayName = "Main Gate"),
        )
        assertEquals(TinyTileStyle.ACTIVE, active.style)
        assertEquals("Main Gate", active.label)

        val busy = resolveTinyTileState(
            GateWidgetSnapshot(signedIn = true, canBuzz = true, busy = true, buzzRelayName = "Main Gate"),
        )
        assertEquals(TinyTileStyle.BUSY, busy.style)
        assertEquals("Opening…", busy.label)
    }

    @Test
    fun decodeRelaysCachesParsedListAcrossRepeatedExactSizePasses() {
        val raw = """[{"relay":0,"name":"Main Gate"},{"relay":1,"name":"Walk Gate"}]"""
        val first = GateQuickWidget.decodeRelays(raw)
        val second = GateQuickWidget.decodeRelays(raw)
        assertEquals(
            listOf(WidgetRelayEntry(0, "Main Gate"), WidgetRelayEntry(1, "Walk Gate")),
            first,
        )
        assertSame("Identical raw JSON must reuse cached relay list instance", first, second)

        val changed = GateQuickWidget.decodeRelays("""[{"relay":2,"name":"Side Gate"}]""")
        assertEquals(listOf(WidgetRelayEntry(2, "Side Gate")), changed)

        assertTrue(GateQuickWidget.decodeRelays(null).isEmpty())
        assertTrue(GateQuickWidget.decodeRelays("   ").isEmpty())
        assertTrue(GateQuickWidget.decodeRelays("not-json").isEmpty())
    }

    @Test
    fun populatesCooldownSecondsAndDisablesBuzzDuringCooldown() = runBlocking {
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
                    """{"available":true,"cooldownSeconds":18}""",
                )
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"c".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        val snap = GateWidgetRepository.fetchSnapshot(storage, api)
        assertTrue(snap.signedIn)
        assertFalse(snap.canBuzz)
        assertEquals("Wait 18s", snap.statusText)
        assertEquals(GateWidgetSnapshot.TONE_NEUTRAL, snap.statusTone)
        assertEquals(18, snap.cooldownSeconds)
    }

    @Test
    fun fetchSnapshotPropagatesCancellationExceptionDuringListGates() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/session" -> json(200, """{"loggedIn":true,"user":"admin"}""")
                "/api/gates" -> {
                    requestStarted.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                    json(200, """{"gates":[{"phoneNumber":"2145550100","siteName":"Oakridge"}]}""")
                }
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"d".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        var caughtCancellation = false
        var returnedSnapshot: GateWidgetSnapshot? = null
        val job = launch(Dispatchers.IO) {
            try {
                returnedSnapshot = GateWidgetRepository.fetchSnapshot(storage, api)
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        try {
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("CancellationException during listGates must propagate", caughtCancellation)
            assertNull(returnedSnapshot)
        } finally {
            releaseServer.countDown()
        }
    }

    @Test
    fun fetchSnapshotPropagatesCancellationExceptionDuringGetPortalMe() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/session" -> json(
                    200,
                    """{"loggedIn":true,"user":"res_101","isResidentOnly":true,"gate":{"phoneNumber":"2145550100","siteName":"Oakridge","role":"resident"}}""",
                )
                request.path!!.startsWith("/api/portal/gates/2145550100/me") -> {
                    requestStarted.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                    json(
                        200,
                        """{"gate":{"phoneNumber":"2145550100","siteName":"Oakridge"},"unit":{"directoryCode":"101","name":"Jordan"},"portal":{"enabled":true,"buzzEnabled":true,"buzzRelays":[0],"buzzRelayLabels":[{"relay":0,"name":"Main Gate"}]},"reroll":{"remainingToday":1}}""",
                    )
                }
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"e".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        var caughtCancellation = false
        var returnedSnapshot: GateWidgetSnapshot? = null
        val job = launch(Dispatchers.IO) {
            try {
                returnedSnapshot = GateWidgetRepository.fetchSnapshot(storage, api)
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        try {
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("CancellationException during getPortalMe must propagate", caughtCancellation)
            assertNull(returnedSnapshot)
        } finally {
            releaseServer.countDown()
        }
    }

    @Test
    fun fetchSnapshotPropagatesCancellationExceptionDuringGetPortalStatus() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
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
                request.path == "/api/portal/gates/2145550100/status" -> {
                    requestStarted.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                    json(200, """{"available":true,"cooldownSeconds":0}""")
                }
                else -> json(404, """{"error":"not found"}""")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"f".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        var caughtCancellation = false
        var returnedSnapshot: GateWidgetSnapshot? = null
        val job = launch(Dispatchers.IO) {
            try {
                returnedSnapshot = GateWidgetRepository.fetchSnapshot(storage, api)
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }
        try {
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("CancellationException during getPortalStatus must propagate", caughtCancellation)
            assertNull(returnedSnapshot)
        } finally {
            releaseServer.countDown()
        }
    }

    @Test
    fun previewSnapshotContainsCleanSampleRelaysWithoutSideEffects() {
        val preview = GateQuickWidget.previewSnapshot()
        assertTrue(preview.signedIn)
        assertTrue(preview.canBuzz)
        assertEquals("", preview.gatePhone)
        assertEquals(
            listOf(WidgetRelayEntry(0, "Main Gate"), WidgetRelayEntry(1, "Walk Gate")),
            preview.relays,
        )
    }
}

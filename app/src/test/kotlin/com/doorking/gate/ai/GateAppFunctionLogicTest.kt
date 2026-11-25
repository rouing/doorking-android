package com.doorking.gate.ai

import com.doorking.gate.car.CarRelayTile
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

class GateAppFunctionLogicTest {

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

    private fun tile(
        phone: String,
        gateName: String,
        relay: Int,
        relayName: String,
        canBuzz: Boolean = true,
        requirePasskey: Boolean = false,
        statusText: String = "Tap to open",
        cooldownSeconds: Int = 0,
    ) = CarRelayTile(
        key = "$phone:$relay",
        gatePhone = phone,
        gateName = gateName,
        unitCode = "101",
        relay = relay,
        relayName = relayName,
        statusText = statusText,
        canBuzz = canBuzz,
        requirePasskey = requirePasskey,
        cooldownSeconds = cooldownSeconds,
    )

    @Test
    fun matchesRelayByExactAndPartialGateAndRelayNames() {
        val tiles = listOf(
            tile("2145550100", "Oakridge Condos", 0, "Entry"),
            tile("2145550100", "Oakridge Condos", 1, "Exit"),
            tile("2145550200", "Cedar Creek", 0, "Entry"),
        )
        assertEquals("2145550100:0", GateAppFunctionLogic.matchRelayTile(tiles, "entry", "oakridge").key)
        assertEquals("2145550200:0", GateAppFunctionLogic.matchRelayTile(tiles, "Entry", "Cedar Creek").key)
        assertEquals("2145550100:1", GateAppFunctionLogic.matchRelayTile(tiles, "exit", null).key)
    }

    @Test(expected = GateAppFunctionException.InvalidArgument::class)
    fun throwsAmbiguousErrorWhenMultipleGatesShareRelayName() {
        val tiles = listOf(
            tile("2145550100", "Oakridge Condos", 0, "Entry"),
            tile("2145550200", "Cedar Creek", 0, "Entry"),
        )
        GateAppFunctionLogic.matchRelayTile(tiles, "Entry", null)
    }

    @Test(expected = GateAppFunctionException.ElementNotFound::class)
    fun throwsNotFoundForUnknownRelay() {
        val tiles = listOf(tile("2145550100", "Oakridge Condos", 0, "Entry"))
        GateAppFunctionLogic.matchRelayTile(tiles, "Pool Gate", null)
    }

    @Test
    fun openRelayBuzzesMatchedEntranceAndReturnsConfirmation() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/session" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"alice","gate":{"phoneNumber":"2145550100","siteName":"Oakridge Condos","role":"resident"}}""")
                "/api/gates" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"gates":[{"phoneNumber":"2145550100","siteName":"Oakridge Condos","role":"resident"}]}""")
                "/api/portal/gates/2145550100/me" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"gate":{"phoneNumber":"2145550100","siteName":"Oakridge Condos"},"unit":{"directoryCode":"101","name":"Alice"},"portal":{"enabled":true,"buzzEnabled":true,"buzzRelayLabels":[{"relay":0,"name":"Entry"},{"relay":1,"name":"Exit"}]},"reroll":{"remainingToday":1}}""")
                "/api/portal/gates/2145550100/status" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"available":true,"cooldownSeconds":0}""")
                "/api/portal/gates/2145550100/buzz" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"ok":true,"alreadyOpen":false}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"9".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        val result = GateAppFunctionLogic.openRelay(
            sessionStorage = storage,
            api = api,
            relayName = "Entry",
            gateName = "Oakridge",
            openAppIntentProvider = { null },
        )
        assertTrue(result.opened)
        assertFalse(result.alreadyOpen)
        assertEquals("Entry", result.relayName)
        assertEquals("Oakridge Condos", result.gateName)
    }

    @Test(expected = GateAppFunctionException.PermissionRequired::class)
    fun signedOutUserReceivesPermissionDeniedException() {
        runBlocking {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = null)
            val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")
            GateAppFunctionLogic.loadReadyTiles(storage, api)
        }
    }

    @Test
    fun openRelayRespectsBiometricAppLockWithoutBuzzing() = runBlocking {
        var buzzCalled = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/session" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"loggedIn":true,"user":"alice","gate":{"phoneNumber":"2145550100","siteName":"Oakridge Condos","role":"resident"}}""")
                "/api/gates" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"gates":[{"phoneNumber":"2145550100","siteName":"Oakridge Condos","role":"resident"}]}""")
                "/api/portal/gates/2145550100/me" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"gate":{"phoneNumber":"2145550100","siteName":"Oakridge Condos"},"unit":{"directoryCode":"101","name":"Alice"},"portal":{"enabled":true,"buzzEnabled":true,"buzzRelayLabels":[{"relay":0,"name":"Entry"}]},"reroll":{"remainingToday":1}}""")
                "/api/portal/gates/2145550100/status" -> MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"available":true,"cooldownSeconds":0}""")
                "/api/portal/gates/2145550100/buzz" -> {
                    buzzCalled = true
                    MockResponse().setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody("""{"ok":true,"alreadyOpen":false}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(
            initialServerUrl = baseUrl,
            initialToken = "dks_${"9".repeat(64)}",
            initialBiometricLockEnabled = true,
        )
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        var intentRequested = false
        val result = GateAppFunctionLogic.openRelay(
            sessionStorage = storage,
            api = api,
            relayName = "Entry",
            gateName = "Oakridge",
            openAppIntentProvider = {
                intentRequested = true
                null
            },
        )
        assertFalse(result.opened)
        assertFalse(buzzCalled)
        assertTrue(intentRequested)
        assertEquals("Oakridge Condos", result.gateName)
        assertEquals("Entry", result.relayName)
    }
}

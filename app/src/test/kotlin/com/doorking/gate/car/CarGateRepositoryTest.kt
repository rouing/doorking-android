package com.doorking.gate.car

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.DoorkingAuthInterceptor
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.test.runTest
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

class CarGateRepositoryTest {
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

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun createClient(token: String? = "dks_testtoken"): Pair<FakeSessionStorage, com.doorking.gate.core.network.DoorkingApi> {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(
            initialServerUrl = baseUrl,
            initialToken = token,
        )
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, AuthEventBus()),
            "$baseUrl/",
        )
        return storage to api
    }

    @Test
    fun signedOutReturnsSignedOutState() = runTest {
        val (storage, api) = createClient(token = null)
        assertEquals(CarGateUiState.SignedOut, CarGateRepository.fetchState(storage, api))
    }

    @Test
    fun residentWithMultipleRelaysReturnsReadyTilesAndBuzzes() = runTest {
        server.enqueue(
            json(
                """
                {
                  "loggedIn": true,
                  "user": "jordan@example.com",
                  "isResidentOnly": true,
                  "hasPortalUnit": true,
                  "gate": { "phoneNumber": "2145550100", "siteName": "Oakridge Condos", "role": "resident" }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            json(
                """{"gates":[{"phoneNumber":"2145550100","siteName":"Oakridge Condos","role":"resident"}]}""",
            ),
        )
        server.enqueue(
            json(
                """
                {
                  "gate": { "phoneNumber": "2145550100", "siteName": "Oakridge Condos" },
                  "unit": { "directoryCode": "101", "name": "Jordan Rivera" },
                  "portal": {
                    "enabled": true,
                    "buzzEnabled": true,
                    "buzzRelays": [0, 2],
                    "buzzRelayLabels": [
                      { "relay": 0, "name": "Main Gate" },
                      { "relay": 2, "name": "Walk Gate" }
                    ]
                  },
                  "reroll": { "usedToday": 0, "maxPerDay": 1, "remainingToday": 1 }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"available":true,"cooldownSeconds":0}"""))
        server.enqueue(json("""{"ok":true,"alreadyOpen":false}"""))

        val (storage, api) = createClient()
        val state = CarGateRepository.fetchState(storage, api) as CarGateUiState.Ready
        assertEquals("Oakridge Condos", state.title)
        assertFalse(state.multipleGates)
        assertEquals(2, state.relays.size)
        assertEquals("Main Gate", state.relays[0].relayName)
        assertTrue(state.relays[0].canBuzz)
        assertEquals("Walk Gate", state.relays[1].relayName)

        val buzz = CarGateRepository.buzz(api, state.relays[0])
        assertEquals(CarBuzzResult.Opened("Main Gate opened"), buzz)
    }

    @Test
    fun passkeyRequiredGateReportsRequiresPhonePasskey() = runTest {
        val (_, api) = createClient()
        val tile = CarRelayTile(
            key = "2145550100:0",
            gatePhone = "2145550100",
            gateName = "Oakridge Condos",
            unitCode = "101",
            relay = 0,
            relayName = "Main Gate",
            statusText = "Passkey on phone",
            canBuzz = false,
            requirePasskey = true,
        )
        assertEquals(CarBuzzResult.RequiresPhonePasskey, CarGateRepository.buzz(api, tile))
    }

    @Test
    fun cooldownStateMarksTileNonBuzzable() = runTest {
        server.enqueue(
            json(
                """
                {
                  "loggedIn": true,
                  "user": "jordan@example.com",
                  "isResidentOnly": true,
                  "hasPortalUnit": true,
                  "gate": { "phoneNumber": "2145550100", "siteName": "Oakridge Condos", "role": "resident" }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"gates":[]}"""))
        server.enqueue(
            json(
                """
                {
                  "gate": { "phoneNumber": "2145550100", "siteName": "Oakridge Condos" },
                  "unit": { "directoryCode": "101", "name": "Jordan Rivera" },
                  "portal": {
                    "enabled": true,
                    "buzzEnabled": true,
                    "buzzRelays": [0],
                    "buzzRelayLabels": [{ "relay": 0, "name": "Main Gate" }]
                  },
                  "reroll": { "usedToday": 0, "maxPerDay": 1, "remainingToday": 1 }
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"available":true,"cooldownSeconds":18}"""))

        val (storage, api) = createClient()
        val state = CarGateRepository.fetchState(storage, api) as CarGateUiState.Ready
        assertEquals("Wait 18s", state.relays.single().statusText)
        assertFalse(state.relays.single().canBuzz)
    }

    @Test
    fun refreshStatusesOnlyCallsPortalStatusOncePerDistinctGateAndUpdatesTiles() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/portal/gates/2145550100/status" -> json("""{"available":true,"cooldownSeconds":24}""")
                "/api/portal/gates/2145550200/status" -> json("""{"available":false,"busyReason":"pulling","percent":60,"cooldownSeconds":0}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (_, api) = createClient()
        val initial = CarGateUiState.Ready(
            title = "Doorking Gate",
            multipleGates = true,
            relays = listOf(
                CarRelayTile(
                    key = "2145550100:0",
                    gatePhone = "2145550100",
                    gateName = "Oakridge Condos",
                    unitCode = "101",
                    relay = 0,
                    relayName = "Main Gate",
                    statusText = "Tap to open",
                    canBuzz = true,
                    requirePasskey = false,
                ),
                CarRelayTile(
                    key = "2145550100:2",
                    gatePhone = "2145550100",
                    gateName = "Oakridge Condos",
                    unitCode = "101",
                    relay = 2,
                    relayName = "Walk Gate",
                    statusText = "Tap to open",
                    canBuzz = true,
                    requirePasskey = false,
                ),
                CarRelayTile(
                    key = "2145550200:0",
                    gatePhone = "2145550200",
                    gateName = "West Annex",
                    unitCode = "202",
                    relay = 0,
                    relayName = "North Entry",
                    statusText = "Tap to open",
                    canBuzz = true,
                    requirePasskey = false,
                ),
            ),
        )

        val refreshed = CarGateRepository.refreshStatusesOnly(api, initial)

        // Only 2 HTTP calls total (one /status per distinct gatePhone — fetched in parallel).
        assertEquals(2, server.requestCount)
        val paths = setOf(server.takeRequest().path, server.takeRequest().path)
        assertEquals(
            setOf("/api/portal/gates/2145550100/status", "/api/portal/gates/2145550200/status"),
            paths,
        )

        // Both relays on gate 2145550100 updated to cooldown state.
        assertEquals("Wait 24s", refreshed.relays[0].statusText)
        assertFalse(refreshed.relays[0].canBuzz)
        assertFalse(refreshed.relays[0].isBusy)
        assertEquals(24, refreshed.relays[0].cooldownSeconds)

        assertEquals("Wait 24s", refreshed.relays[1].statusText)
        assertFalse(refreshed.relays[1].canBuzz)
        assertEquals(24, refreshed.relays[1].cooldownSeconds)

        // Relay on gate 2145550200 updated to busy 60%.
        assertEquals("Busy 60%", refreshed.relays[2].statusText)
        assertFalse(refreshed.relays[2].canBuzz)
        assertTrue(refreshed.relays[2].isBusy)
        assertEquals(0, refreshed.relays[2].cooldownSeconds)
    }

    @Test
    fun tickLocalCooldownCountsDownAndReenablesBuzzAtZero() {
        val state = CarGateUiState.Ready(
            title = "Oakridge Condos",
            multipleGates = false,
            relays = listOf(
                CarRelayTile(
                    key = "2145550100:0",
                    gatePhone = "2145550100",
                    gateName = "Oakridge Condos",
                    unitCode = "101",
                    relay = 0,
                    relayName = "Main Gate",
                    statusText = "Wait 2s",
                    canBuzz = false,
                    requirePasskey = false,
                    cooldownSeconds = 2,
                ),
            ),
        )

        val afterOne = CarGateRepository.tickLocalCooldown(state, 1)
        assertEquals("Wait 1s", afterOne.relays.single().statusText)
        assertEquals(1, afterOne.relays.single().cooldownSeconds)
        assertFalse(afterOne.relays.single().canBuzz)

        val afterTwo = CarGateRepository.tickLocalCooldown(afterOne, 1)
        assertEquals("Tap to open", afterTwo.relays.single().statusText)
        assertEquals(0, afterTwo.relays.single().cooldownSeconds)
        assertTrue(afterTwo.relays.single().canBuzz)
    }

    @Test
    fun buzzOn403StepUpFailsFastWithoutInvokingInteractiveCoordinator() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_testtoken")
        val events = AuthEventBus()
        var prompted = 0
        val interceptor = DoorkingAuthInterceptor(storage, events, ApiClientFactory.json) {
            prompted++
            true
        }
        val api = ApiClientFactory.createApi(
            ApiClientFactory.createOkHttp(storage, events, authInterceptor = interceptor),
            "$baseUrl/",
        )
        server.enqueue(json("""{"stepUp":true,"hasPasskey":true,"error":"Confirm on phone"}""", code = 403))

        val tile = CarRelayTile(
            key = "2145550100:0",
            gatePhone = "2145550100",
            gateName = "Oakridge Condos",
            unitCode = "101",
            relay = 0,
            relayName = "Main Gate",
            statusText = "Tap to open",
            canBuzz = true,
            requirePasskey = false,
        )
        assertEquals(CarBuzzResult.RequiresPhonePasskey, CarGateRepository.buzz(api, tile))
        assertEquals(0, prompted)
    }

    @Test
    fun refreshStatusesOnlyPreservesPasskeyRequirement() = runTest {
        server.enqueue(json("""{"available":true,"cooldownSeconds":0}"""))

        val (_, api) = createClient()
        val initial = CarGateUiState.Ready(
            title = "Oakridge Condos",
            multipleGates = false,
            relays = listOf(
                CarRelayTile(
                    key = "2145550100:0",
                    gatePhone = "2145550100",
                    gateName = "Oakridge Condos",
                    unitCode = "101",
                    relay = 0,
                    relayName = "Main Gate",
                    statusText = "Passkey on phone",
                    canBuzz = false,
                    requirePasskey = true,
                ),
            ),
        )

        val refreshed = CarGateRepository.refreshStatusesOnly(api, initial)
        assertEquals(1, server.requestCount)
        assertEquals("Passkey on phone", refreshed.relays.single().statusText)
        assertFalse(refreshed.relays.single().canBuzz)
    }
}

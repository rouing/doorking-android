package com.doorking.gate.car

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
}

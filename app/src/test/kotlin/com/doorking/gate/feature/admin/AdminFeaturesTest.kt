package com.doorking.gate.feature.admin

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.ConflictDecisionRequest
import com.doorking.gate.core.network.ConfirmRequest
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminFeaturesTest {

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
    fun togglesBitStringsAccurately() {
        assertEquals("1000000", toggleBitString("0000000", 0, 7))
        assertEquals("0000000", toggleBitString("1000000", 0, 7))
        assertEquals("0010000", toggleBitString("", 2, 7))
    }

    @Test
    fun computesFloatingUsHolidaysFor2026() {
        val holidays = usHolidaysForYear(2026).toMap()
        assertEquals("1/1", holidays["New Year's Day"])
        assertEquals("1/19", holidays["MLK Jr. Day"])
        assertEquals("5/25", holidays["Memorial Day"])
        assertEquals("11/26", holidays["Thanksgiving"])
        assertEquals("12/25", holidays["Christmas Day"])
    }

    @Test
    fun sendAndConflictEndpointsSendExactServerBodies() = runBlocking {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"7".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        server.enqueue(MockResponse().setResponseCode(202).addHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))

        api.startSend("2145550100", ConfirmRequest(true))
        val sendReq = server.takeRequest()
        assertTrue(sendReq.body.readUtf8().contains(""""confirm":true"""))

        api.decideConflict("2145550100", ConflictDecisionRequest(kind = "resident", key = "101", choice = "mine"))
        val conflictReq = server.takeRequest()
        assertTrue(conflictReq.body.readUtf8().contains(""""choice":"mine""""))
    }
}

package com.doorking.gate.feature.admin

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.ConflictDecisionRequest
import com.doorking.gate.core.network.ConfirmRequest
import com.doorking.gate.core.network.GateSettingRowDto
import com.doorking.gate.core.ui.components.formatTime12Hour
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

    @Test
    fun formats24HourTimesTo12HourDisplay() {
        assertEquals("", formatTime12Hour(""))
        assertEquals("12:00 AM", formatTime12Hour("00:00"))
        assertEquals("7:05 AM", formatTime12Hour("07:05"))
        assertEquals("12:30 PM", formatTime12Hour("12:30"))
        assertEquals("7:00 PM", formatTime12Hour("19:00"))
        assertEquals("11:59 PM", formatTime12Hour("23:59"))
    }

    @Test
    fun detectsInitialDisplayLayoutFromGateValues() {
        val twoLineOnly = listOf(
            GateSettingRowDto("systemMessage", "Display (single line)", "System message", 0, 27, "WELCOME", "WELCOME", true),
            GateSettingRowDto("displayLine1", "Display (8 line)", "Line 1", 0, 20, "", "", true),
        )
        assertEquals(DisplayLayoutMode.TwoLine, detectInitialDisplayLayout(twoLineOnly))

        val eightLineOnly = listOf(
            GateSettingRowDto("systemMessage", "Display (single line)", "System message", 0, 27, "", "", true),
            GateSettingRowDto("displayLine1", "Display (8 line)", "Line 1", 0, 20, "OAKRIDGE CONDOS", "OAKRIDGE CONDOS", true),
        )
        assertEquals(DisplayLayoutMode.EightLine, detectInitialDisplayLayout(eightLineOnly))

        // Real-world 2-line gate where DoorKing pre-filled all 8-line slots with factory defaults
        val twoLineWithFactoryEightLineDefaults = listOf(
            GateSettingRowDto("systemMessage", "Display (single line)", "System message", 0, 27, "Welcome to Oakridge", "Welcome to Oakridge", true),
            GateSettingRowDto("systemInstruction", "Display (single line)", "System instruction", 0, 52, "Push A-Z To Find Name Or No. Enter Code Or Push Call", "", true),
            GateSettingRowDto("displayLine1", "Display (8 line)", "User message line 1", 0, 20, "   This Space is", "", true),
            GateSettingRowDto("displayLine2", "Display (8 line)", "User message line 2", 0, 20, "   For Your User", "", true),
            GateSettingRowDto("displayLine3", "Display (8 line)", "User message line 3", 0, 20, "    Programmable", "", true),
            GateSettingRowDto("displayLine4", "Display (8 line)", "User message line 4", 0, 20, "      Message", "", true),
            GateSettingRowDto("displayLine5", "Display (8 line)", "Instructions line 1", 0, 20, "Use A-Z to Find Name", "", true),
            GateSettingRowDto("displayLine6", "Display (8 line)", "Instructions line 2", 0, 20, "Or Number-Enter Code", "", true),
            GateSettingRowDto("displayLine7", "Display (8 line)", "Instructions line 3", 0, 20, "Or Push Call-If Busy", "", true),
            GateSettingRowDto("displayLine8", "Display (8 line)", "Instructions line 4", 0, 20, "Press # - Try Again.", "", true),
        )
        assertEquals(DisplayLayoutMode.TwoLine, detectInitialDisplayLayout(twoLineWithFactoryEightLineDefaults))
    }
}

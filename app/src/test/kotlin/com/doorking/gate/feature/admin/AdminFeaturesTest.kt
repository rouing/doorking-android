package com.doorking.gate.feature.admin

import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.AuthEventBus
import com.doorking.gate.core.network.CameraChannelDto
import com.doorking.gate.core.network.CameraSettingsDto
import com.doorking.gate.core.network.ConflictDecisionRequest
import com.doorking.gate.core.network.ConfirmRequest
import com.doorking.gate.core.network.GateSettingRowDto
import com.doorking.gate.core.ui.components.formatTime12Hour
import com.doorking.gate.fakes.FakeSessionStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun buildsCameraSettingsPayloadWithNormalizedFieldsAndOptionalPassword() {
        val formWithoutPassword = CameraFormState(
            enabled = true,
            serial = "  ABC123456  ",
            username = " admin ",
            password = "",
            clockOffset = "-120",
            mode = "video",
            frames = 4,
            framesAfter = 2,
            clipTries = 3,
            events = "all",
            keepDays = 30,
            backfillHours = "48",
            channels = listOf(
                CameraChannelFormRow(channel = "1", name = " Main Entry ", relays = listOf(2, 0, 2)),
                CameraChannelFormRow(channel = "", name = "Unused", relays = listOf(1)),
                CameraChannelFormRow(channel = "4", name = "Exit", relays = emptyList()),
            ),
        )
        val payload = buildCameraSettingsPayload(formWithoutPassword)
        assertTrue(payload["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("ABC123456", payload["serial"]!!.jsonPrimitive.content)
        assertEquals("admin", payload["username"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("password"))
        assertEquals(-120, payload["clockOffset"]!!.jsonPrimitive.int)
        assertEquals("video", payload["mode"]!!.jsonPrimitive.content)
        assertEquals(4, payload["frames"]!!.jsonPrimitive.int)
        assertEquals(2, payload["framesAfter"]!!.jsonPrimitive.int)
        assertEquals(3, payload["clipTries"]!!.jsonPrimitive.int)
        assertEquals("all", payload["events"]!!.jsonPrimitive.content)
        assertEquals(30, payload["keepDays"]!!.jsonPrimitive.int)
        assertEquals(48, payload["backfillHours"]!!.jsonPrimitive.int)

        val channels = payload["channels"]!!.jsonArray
        assertEquals(2, channels.size)
        val ch0 = channels[0].jsonObject
        assertEquals(1, ch0["channel"]!!.jsonPrimitive.int)
        assertEquals("Main Entry", ch0["name"]!!.jsonPrimitive.content)
        assertEquals(listOf(0, 2), ch0["relays"]!!.jsonArray.map { it.jsonPrimitive.int })

        val ch1 = channels[1].jsonObject
        assertEquals(4, ch1["channel"]!!.jsonPrimitive.int)
        assertEquals("Exit", ch1["name"]!!.jsonPrimitive.content)
        assertTrue(ch1["relays"]!!.jsonArray.isEmpty())

        val withPassword = buildCameraSettingsPayload(formWithoutPassword.copy(password = "s3cret"))
        assertEquals("s3cret", withPassword["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun confirmsCameraRetentionOnlyWhenShorteningToNonZero() {
        assertTrue(shouldConfirmCameraRetention(previousKeepDays = 0, nextKeepDays = 30))
        assertTrue(shouldConfirmCameraRetention(previousKeepDays = 90, nextKeepDays = 15))
        assertFalse(shouldConfirmCameraRetention(previousKeepDays = 30, nextKeepDays = 30))
        assertFalse(shouldConfirmCameraRetention(previousKeepDays = 30, nextKeepDays = 60))
        assertFalse(shouldConfirmCameraRetention(previousKeepDays = 30, nextKeepDays = 0))
        assertFalse(shouldConfirmCameraRetention(previousKeepDays = 0, nextKeepDays = 0))
    }

    @Test
    fun cameraFormStateDefaultsAndRestoresSavedChannels() {
        val emptyState = cameraFormStateFrom(null)
        assertEquals(4, emptyState.channels.size)
        assertTrue(emptyState.channels.all { it.channel.isEmpty() && it.name.isEmpty() })

        val saved = CameraSettingsDto(
            enabled = false,
            serial = "SN987654",
            username = "camadmin",
            mode = "video",
            clockOffset = 15,
            frames = 2,
            framesAfter = 1,
            clipTries = 4,
            events = "all",
            backfillHours = 72,
            keepDays = 60,
            channels = listOf(CameraChannelDto(channel = 2, name = "Rear", relays = listOf(1, 0, 1))),
        )
        val restored = cameraFormStateFrom(saved)
        assertFalse(restored.enabled)
        assertEquals("SN987654", restored.serial)
        assertEquals("", restored.password)
        assertEquals("15", restored.clockOffset)
        assertEquals("72", restored.backfillHours)
        assertEquals(1, restored.channels.size)
        assertEquals("2", restored.channels[0].channel)
        assertEquals(listOf(0, 1), restored.channels[0].relays)
    }

    @Test
    fun cameraTestAndDeleteSnapshotsEndpointsHitExactPaths() = runBlocking {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val storage = FakeSessionStorage(initialServerUrl = baseUrl, initialToken = "dks_${"7".repeat(64)}")
        val api = ApiClientFactory.createApi(ApiClientFactory.createOkHttp(storage, AuthEventBus()), "$baseUrl/")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"channels":[{"channel":1,"name":"Gate","file":"ch1.jpg"},{"channel":2,"name":"Side","error":"Offline"}]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"files":18,"minutes":6}"""),
        )

        val testRes = api.testCameras("2145550100")
        assertTrue(testRes.isSuccessful)
        assertEquals(2, testRes.body()?.channels?.size)
        assertEquals("ch1.jpg", testRes.body()?.channels?.get(0)?.file)
        assertEquals("Offline", testRes.body()?.channels?.get(1)?.error)
        val testReq = server.takeRequest()
        assertEquals("POST", testReq.method)
        assertEquals("/api/gates/2145550100/cameras/test", testReq.path)

        val delRes = api.deleteAllSnapshots("2145550100")
        assertTrue(delRes.isSuccessful)
        assertEquals(18, delRes.body()?.files)
        assertEquals(6, delRes.body()?.minutes)
        val delReq = server.takeRequest()
        assertEquals("DELETE", delReq.method)
        assertEquals("/api/gates/2145550100/snapshots", delReq.path)
    }
}

package com.doorking.gate.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {

    private val json = ApiClientFactory.json

    @Test
    fun decodesLoggedOutSessionPayload() {
        val raw = """{"loggedIn":false,"emailEnabled":true,"sso":[{"id":"okta","name":"Work SSO"}],"setup":false}"""
        val decoded = json.decodeFromString<SessionResponse>(raw)
        assertFalse(decoded.loggedIn)
        assertTrue(decoded.emailEnabled)
        assertEquals(1, decoded.sso.size)
        assertEquals("okta", decoded.sso.first().id)
    }

    @Test
    fun decodesLoggedInResidentSessionPayload() {
        val raw = """
            {
              "loggedIn": true,
              "user": "res_101",
              "email": "smith@unit.test",
              "isResidentOnly": true,
              "hasPortalUnit": true,
              "mustChangePassword": false,
              "gate": { "phoneNumber": "2145550100", "siteName": "Oakridge Estates", "addedAt": "2026-01-01T00:00:00Z", "role": "resident" },
              "unknownFutureField": 42
            }
        """.trimIndent()
        val decoded = json.decodeFromString<SessionResponse>(raw)
        assertTrue(decoded.loggedIn)
        assertTrue(decoded.isResidentOnly)
        assertEquals("2145550100", decoded.gate?.phoneNumber)
        assertEquals("resident", decoded.gate?.role)
    }

    @Test
    fun decodesPortalMePayload() {
        val raw = """
            {
              "gate": { "phoneNumber": "2145550100", "siteName": "Oakridge Estates", "adminPhone": "2145550199", "grantAccessDigit": "9" },
              "units": [{ "directoryCode": "101", "name": "Smith" }],
              "unit": { "directoryCode": "101", "name": "Smith", "phone": "2145550177", "entryCode": "4821", "hidden": false, "cards": [{ "number": "1234", "device": "Fob" }], "email": "smith@unit.test" },
              "portal": { "enabled": true, "buzzEnabled": true, "buzzRelays": [0], "pulseSeconds": 2, "maxRerollsPerDay": 1, "buzzRelayLabels": [{ "relay": 0, "name": "Main Gate" }] },
              "reroll": { "usedToday": 0, "maxPerDay": 1, "remainingToday": 1, "pendingSend": false, "nextScheduledSend": "2026-11-23T08:00:00.000Z" }
            }
        """.trimIndent()
        val decoded = json.decodeFromString<PortalMeReply>(raw)
        assertEquals("Oakridge Estates", decoded.gate.siteName)
        assertEquals("4821", decoded.unit.entryCode)
        assertEquals("Main Gate", decoded.portal.buzzRelayLabels.first().name)
        assertEquals(1, decoded.reroll.remainingToday)
    }
}

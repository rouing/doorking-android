package com.doorking.gate.widget

import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.BuzzRequest
import com.doorking.gate.core.network.DoorkingApi

data class GateWidgetSnapshot(
    val signedIn: Boolean = false,
    val gatePhone: String = "",
    val gateName: String = "Doorking Gate",
    val subtitle: String = "Tap to sign in",
    val statusText: String = "Signed out",
    val statusTone: String = TONE_NEUTRAL,
    val canBuzz: Boolean = false,
    val buzzRelay: Int = 0,
    val buzzRelayName: String = "Gate",
    val unitCode: String? = null,
    val busy: Boolean = false,
) {
    companion object {
        const val TONE_NEUTRAL = "neutral"
        const val TONE_SUCCESS = "success"
        const val TONE_WARNING = "warning"
        const val TONE_ERROR = "error"
    }
}

sealed interface WidgetBuzzOutcome {
    data class Opened(val message: String) : WidgetBuzzOutcome
    data class Failed(val message: String) : WidgetBuzzOutcome
}

/**
 * Pure network/state resolver used by Glance `CoroutineWorker`s and tested directly against `MockWebServer`.
 */
object GateWidgetRepository {

    suspend fun fetchSnapshot(
        sessionStorage: SessionStorage,
        api: DoorkingApi,
    ): GateWidgetSnapshot {
        val stored = sessionStorage.awaitLoaded()
        if (stored.sessionToken.isNullOrBlank()) {
            return GateWidgetSnapshot()
        }

        val session = runCatching { api.getSession() }.getOrElse { err ->
            return GateWidgetSnapshot(
                signedIn = true,
                gatePhone = stored.lastGatePhone.orEmpty(),
                gateName = "Doorking Gate",
                subtitle = ApiClientFactory.parseErrorMessage(err),
                statusText = "Offline",
                statusTone = GateWidgetSnapshot.TONE_WARNING,
            )
        }

        if (!session.loggedIn) {
            sessionStorage.clearSessionToken()
            return GateWidgetSnapshot()
        }

        val phone = session.gate?.phoneNumber
            ?: stored.lastGatePhone
            ?: runCatching { api.listGates().gates.firstOrNull()?.phoneNumber }.getOrNull()

        if (phone.isNullOrBlank()) {
            return GateWidgetSnapshot(
                signedIn = true,
                gateName = "Doorking Gate",
                subtitle = session.user,
                statusText = "No gate",
                statusTone = GateWidgetSnapshot.TONE_NEUTRAL,
            )
        }

        val portalMe = if (session.isResidentOnly || session.hasPortalUnit) {
            runCatching { api.getPortalMe(phone) }.getOrNull()
        } else {
            null
        }

        if (portalMe != null && portalMe.portal.buzzEnabled && portalMe.portal.buzzRelayLabels.isNotEmpty()) {
            val primaryRelay = portalMe.portal.buzzRelayLabels.first()
            val status = runCatching { api.getPortalStatus(phone) }.getOrNull()
            val (statusText, statusTone) = when {
                status != null && !status.available -> {
                    val pct = status.percent?.let { " $it%" } ?: ""
                    "Busy$pct" to GateWidgetSnapshot.TONE_WARNING
                }
                status != null && status.cooldownSeconds > 0 -> {
                    "Wait ${status.cooldownSeconds}s" to GateWidgetSnapshot.TONE_NEUTRAL
                }
                else -> "Ready" to GateWidgetSnapshot.TONE_SUCCESS
            }
            return GateWidgetSnapshot(
                signedIn = true,
                gatePhone = phone,
                gateName = portalMe.gate.siteName.ifBlank { phone },
                subtitle = "Unit ${portalMe.unit.directoryCode} · ${portalMe.unit.name}",
                statusText = statusText,
                statusTone = statusTone,
                canBuzz = status?.available != false && (status?.cooldownSeconds ?: 0) == 0,
                buzzRelay = primaryRelay.relay,
                buzzRelayName = primaryRelay.name,
                unitCode = portalMe.unit.directoryCode,
            )
        }

        val gateTitle = session.gate?.siteName?.ifBlank { phone } ?: phone
        val roleLabel = session.gate?.role.orEmpty().replaceFirstChar(Char::uppercase).ifBlank { "Staff" }
        return GateWidgetSnapshot(
            signedIn = true,
            gatePhone = phone,
            gateName = gateTitle,
            subtitle = "${session.user} · $roleLabel",
            statusText = "Open app",
            statusTone = GateWidgetSnapshot.TONE_NEUTRAL,
            canBuzz = false,
        )
    }

    suspend fun buzz(
        api: DoorkingApi,
        gatePhone: String,
        relay: Int,
        relayName: String,
        unitCode: String? = null,
    ): WidgetBuzzOutcome {
        return runCatching {
            val res = api.portalBuzz(gatePhone, BuzzRequest(relay = relay, code = unitCode))
            if (!res.isSuccessful) {
                val raw = res.errorBody()?.string().orEmpty()
                val msg = runCatching {
                    ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw).error
                }.getOrNull() ?: "Failed (HTTP ${res.code()})"
                return WidgetBuzzOutcome.Failed(msg)
            }
            val alreadyOpen = res.body()?.alreadyOpen == true
            WidgetBuzzOutcome.Opened(if (alreadyOpen) "$relayName already open" else "$relayName opened!")
        }.getOrElse { err ->
            WidgetBuzzOutcome.Failed(ApiClientFactory.parseErrorMessage(err))
        }
    }
}

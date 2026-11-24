package com.doorking.gate.car

import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.BuzzRequest
import com.doorking.gate.core.network.DoorkingApi

data class CarRelayTile(
    val key: String,
    val gatePhone: String,
    val gateName: String,
    val unitCode: String?,
    val relay: Int,
    val relayName: String,
    val statusText: String,
    val canBuzz: Boolean,
    val requirePasskey: Boolean,
)

sealed interface CarGateUiState {
    data object Loading : CarGateUiState
    data object SignedOut : CarGateUiState
    data class NoBuzzableGates(val reason: String) : CarGateUiState
    data class Error(val message: String) : CarGateUiState
    data class Ready(
        val title: String,
        val relays: List<CarRelayTile>,
        val multipleGates: Boolean,
        val buzzingKey: String? = null,
    ) : CarGateUiState
}

sealed interface CarBuzzResult {
    data class Opened(val message: String) : CarBuzzResult
    data object RequiresPhonePasskey : CarBuzzResult
    data class Failed(val message: String) : CarBuzzResult
}

/**
 * Pure suspend loader/executor for Android Auto (`androidx.car.app.category.IOT`).
 * Resolves all resident-buzzable gate relays accessible to the signed-in account and pulses relays
 * via the existing `/api/gates/{phone}/portal/open` endpoint.
 */
object CarGateRepository {

    const val MAX_CAR_GRID_TILES = 6

    suspend fun fetchState(
        sessionStorage: SessionStorage,
        api: DoorkingApi,
    ): CarGateUiState {
        val stored = sessionStorage.awaitLoaded()
        if (stored.sessionToken.isNullOrBlank()) {
            return CarGateUiState.SignedOut
        }

        val session = runCatching { api.getSession() }.getOrElse { err ->
            return CarGateUiState.Error(ApiClientFactory.parseErrorMessage(err))
        }

        if (!session.loggedIn) {
            sessionStorage.clearSessionToken()
            return CarGateUiState.SignedOut
        }

        val candidatePhones = linkedSetOf<String>()
        session.gate?.phoneNumber?.takeIf { it.isNotBlank() }?.let(candidatePhones::add)
        stored.lastGatePhone?.takeIf { it.isNotBlank() }?.let(candidatePhones::add)
        runCatching { api.listGates().gates }.getOrNull()?.forEach { gate ->
            if (gate.phoneNumber.isNotBlank()) candidatePhones.add(gate.phoneNumber)
        }

        if (candidatePhones.isEmpty()) {
            return CarGateUiState.NoBuzzableGates(
                "No gates are assigned to your account. Open Doorking Gate on your phone to check access.",
            )
        }

        val tiles = mutableListOf<CarRelayTile>()
        val gateNames = linkedSetOf<String>()

        for (phone in candidatePhones) {
            val portalMe = runCatching { api.getPortalMe(phone) }.getOrNull() ?: continue
            if (!portalMe.portal.buzzEnabled || portalMe.portal.buzzRelayLabels.isEmpty()) continue

            val gateName = portalMe.gate.siteName.ifBlank { phone }
            gateNames.add(gateName)
            val status = runCatching { api.getPortalStatus(phone) }.getOrNull()
            val (statusText, canBuzz) = when {
                portalMe.portal.requirePasskeyForBuzz -> "Passkey on phone" to false
                status != null && !status.available -> {
                    val pct = status.percent?.let { " $it%" } ?: ""
                    "Busy$pct" to false
                }
                status != null && status.cooldownSeconds > 0 -> {
                    "Wait ${status.cooldownSeconds}s" to false
                }
                else -> "Tap to open" to true
            }

            for (relay in portalMe.portal.buzzRelayLabels) {
                tiles.add(
                    CarRelayTile(
                        key = "$phone:${relay.relay}",
                        gatePhone = phone,
                        gateName = gateName,
                        unitCode = portalMe.unit.directoryCode.ifBlank { null },
                        relay = relay.relay,
                        relayName = relay.name.ifBlank { "Gate ${relay.relay + 1}" },
                        statusText = statusText,
                        canBuzz = canBuzz,
                        requirePasskey = portalMe.portal.requirePasskeyForBuzz,
                    ),
                )
            }
        }

        if (tiles.isEmpty()) {
            return CarGateUiState.NoBuzzableGates(
                "Buzz Me In is not enabled for your account on any gate.",
            )
        }

        val title = if (gateNames.size == 1) gateNames.first() else "Doorking Gate"
        return CarGateUiState.Ready(
            title = title,
            relays = tiles.take(MAX_CAR_GRID_TILES),
            multipleGates = gateNames.size > 1,
        )
    }

    suspend fun buzz(
        api: DoorkingApi,
        tile: CarRelayTile,
    ): CarBuzzResult {
        if (tile.requirePasskey) {
            return CarBuzzResult.RequiresPhonePasskey
        }
        return runCatching {
            val res = api.portalBuzz(tile.gatePhone, BuzzRequest(relay = tile.relay, code = tile.unitCode))
            if (!res.isSuccessful) {
                val raw = res.errorBody()?.string().orEmpty()
                val parsed = runCatching {
                    ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw)
                }.getOrNull()
                if (res.code() == 403 && parsed?.stepUp == true) {
                    return CarBuzzResult.RequiresPhonePasskey
                }
                return CarBuzzResult.Failed(parsed?.error ?: "Failed (HTTP ${res.code()})")
            }
            val alreadyOpen = res.body()?.alreadyOpen == true
            val label = tile.relayName
            CarBuzzResult.Opened(
                if (alreadyOpen) "$label is already open" else "$label opened",
            )
        }.getOrElse { err ->
            CarBuzzResult.Failed(ApiClientFactory.parseErrorMessage(err))
        }
    }
}

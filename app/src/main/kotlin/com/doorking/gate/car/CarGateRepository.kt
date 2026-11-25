package com.doorking.gate.car

import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.BuzzRequest
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.PortalStatusReply
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    val isBusy: Boolean = false,
    val cooldownSeconds: Int = 0,
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
 * via `/api/portal/gates/{phone}/buzz` with `X-Doorking-No-Interactive-StepUp: 1`.
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

        val perGateResults = coroutineScope {
            candidatePhones.map { phone ->
                async {
                    val portalMe = runCatching { api.getPortalMe(phone) }.getOrNull() ?: return@async null
                    if (!portalMe.portal.buzzEnabled || portalMe.portal.buzzRelayLabels.isEmpty()) {
                        return@async null
                    }
                    val gateName = portalMe.gate.siteName.ifBlank { phone }
                    val status = runCatching { api.getPortalStatus(phone) }.getOrNull()
                    val resolved = resolveTileStatus(portalMe.portal.requirePasskeyForBuzz, status)
                    val gateTiles = portalMe.portal.buzzRelayLabels.map { relay ->
                        CarRelayTile(
                            key = "$phone:${relay.relay}",
                            gatePhone = phone,
                            gateName = gateName,
                            unitCode = portalMe.unit.directoryCode.ifBlank { null },
                            relay = relay.relay,
                            relayName = relay.name.ifBlank { "Gate ${relay.relay + 1}" },
                            statusText = resolved.statusText,
                            canBuzz = resolved.canBuzz,
                            requirePasskey = portalMe.portal.requirePasskeyForBuzz,
                            isBusy = resolved.isBusy,
                            cooldownSeconds = resolved.cooldownSeconds,
                        )
                    }
                    gateName to gateTiles
                }
            }.awaitAll().filterNotNull()
        }

        val tiles = mutableListOf<CarRelayTile>()
        val gateNames = linkedSetOf<String>()
        for ((gateName, gateTiles) in perGateResults) {
            gateNames.add(gateName)
            tiles.addAll(gateTiles)
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

    /**
     * Lightweight polling path: calls ONLY `GET /api/portal/gates/{phone}/status` in parallel once
     * per distinct gate phone in [current], updating `statusText`, `canBuzz`, `isBusy`, and
     * `cooldownSeconds` in place.
     */
    suspend fun refreshStatusesOnly(
        api: DoorkingApi,
        current: CarGateUiState.Ready,
    ): CarGateUiState.Ready {
        val phones = current.relays.map { it.gatePhone }.distinct().filter { it.isNotBlank() }
        val statusByPhone: Map<String, PortalStatusReply?> = coroutineScope {
            phones.map { phone ->
                async { phone to runCatching { api.getPortalStatus(phone) }.getOrNull() }
            }.awaitAll().toMap()
        }
        val updatedRelays = current.relays.map { tile ->
            val status = statusByPhone[tile.gatePhone] ?: return@map tile
            val resolved = resolveTileStatus(tile.requirePasskey, status)
            tile.copy(
                statusText = resolved.statusText,
                canBuzz = resolved.canBuzz,
                isBusy = resolved.isBusy,
                cooldownSeconds = resolved.cooldownSeconds,
            )
        }
        return current.copy(relays = updatedRelays)
    }

    /**
     * Decrements any active local cooldown countdown without hitting the network so Android Auto
     * displays smooth per-second updates between server polls.
     */
    fun tickLocalCooldown(
        current: CarGateUiState.Ready,
        elapsedSeconds: Int = 1,
    ): CarGateUiState.Ready {
        if (elapsedSeconds <= 0) return current
        val updated = current.relays.map { tile ->
            if (tile.isBusy || tile.requirePasskey || tile.cooldownSeconds <= 0) return@map tile
            val remaining = (tile.cooldownSeconds - elapsedSeconds).coerceAtLeast(0)
            tile.copy(
                cooldownSeconds = remaining,
                statusText = if (remaining > 0) "Wait ${remaining}s" else "Tap to open",
                canBuzz = remaining == 0,
            )
        }
        return current.copy(relays = updated)
    }

    private data class TileStatus(
        val statusText: String,
        val canBuzz: Boolean,
        val isBusy: Boolean,
        val cooldownSeconds: Int,
    )

    private fun resolveTileStatus(
        requirePasskey: Boolean,
        status: PortalStatusReply?,
    ): TileStatus {
        val isBusy = status != null && !status.available
        val cooldownSec = status?.cooldownSeconds ?: 0
        val (statusText, canBuzz) = when {
            requirePasskey -> "Passkey on phone" to false
            isBusy -> {
                val pct = status.percent?.let { " $it%" } ?: ""
                "Busy$pct" to false
            }
            cooldownSec > 0 -> "Wait ${cooldownSec}s" to false
            else -> "Tap to open" to true
        }
        return TileStatus(
            statusText = statusText,
            canBuzz = canBuzz,
            isBusy = isBusy,
            cooldownSeconds = cooldownSec,
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
            val res = api.portalBuzz(
                phone = tile.gatePhone,
                body = BuzzRequest(relay = tile.relay, code = tile.unitCode),
                noInteractiveStepUp = "1",
            )
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

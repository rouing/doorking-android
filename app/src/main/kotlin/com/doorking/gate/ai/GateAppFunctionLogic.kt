package com.doorking.gate.ai

import android.app.PendingIntent
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionPermissionRequiredException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionSystemUnknownException
import com.doorking.gate.car.CarBuzzResult
import com.doorking.gate.car.CarGateRepository
import com.doorking.gate.car.CarGateUiState
import com.doorking.gate.car.CarRelayTile
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.network.DoorkingApi

/** An authorized gate entrance relay available to the signed-in user. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class OpenableGateRelay(
    /** The display name of the gate or community property. */
    val gateName: String,
    /** The display name of the relay or entrance, such as Entry or Exit. */
    val relayName: String,
    /** Current availability summary such as Tap to open, Busy 40%, or Wait 18s. */
    val status: String,
    /** True when the relay can be opened immediately without waiting or confirmation. */
    val canOpen: Boolean,
    /** True when the gate requires interactive passkey confirmation in the app before opening. */
    val requiresPasskeyConfirmation: Boolean,
)

/** Availability and cooldown status of a single gate. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class GateStatusInfo(
    /** The display name of the gate or community property. */
    val gateName: String,
    /** True when the gate is ready to accept an open command right now. */
    val available: Boolean,
    /** Human-readable availability message such as Ready, Busy 50%, or Wait 20s. */
    val statusMessage: String,
    /** Remaining cooldown seconds before the next opening is allowed, or 0 when ready. */
    val cooldownSeconds: Int,
)

/** Outcome of an attempt to momentarily open a gate entrance relay. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class OpenGateResult(
    /** The display name of the gate or community property. */
    val gateName: String,
    /** The display name of the relay or entrance that was targeted. */
    val relayName: String,
    /** True when the gate entrance opened or was already open. */
    val opened: Boolean,
    /** True when the relay was already held open before this request. */
    val alreadyOpen: Boolean,
    /** Human-readable confirmation or instruction message for the user. */
    val message: String,
    /** Optional PendingIntent that opens Doorking Gate when interactive confirmation is required. */
    val openAppIntent: PendingIntent? = null,
)

/**
 * Pure-JVM domain exceptions thrown by [GateAppFunctionLogic] and mapped to [AppFunctionException]
 * at the [BaseDoorkingAppFunctionService] boundary (since `androidx.appfunctions` exceptions touch
 * `android.os.Bundle.EMPTY`, which is null in JVM unit tests).
 */
sealed class GateAppFunctionException(message: String) : RuntimeException(message) {
    class PermissionRequired(message: String) : GateAppFunctionException(message)
    class ElementNotFound(message: String) : GateAppFunctionException(message)
    class InvalidArgument(message: String) : GateAppFunctionException(message)
    class SystemUnknown(message: String) : GateAppFunctionException(message)

    fun toAppFunctionException(): AppFunctionException = when (this) {
        is PermissionRequired -> AppFunctionPermissionRequiredException(message)
        is ElementNotFound -> AppFunctionElementNotFoundException(message)
        is InvalidArgument -> AppFunctionInvalidArgumentException(message)
        is SystemUnknown -> AppFunctionSystemUnknownException(message)
    }
}

/**
 * Pure domain logic behind Doorking Gate AppFunctions so all matching, authorization, and error-mapping
 * rules can be unit-tested on the JVM without requiring Android 16 runtime bindings.
 */
object GateAppFunctionLogic {

    suspend fun loadReadyTiles(
        sessionStorage: SessionStorage,
        api: DoorkingApi,
    ): List<CarRelayTile> {
        return when (val state = CarGateRepository.fetchState(sessionStorage, api)) {
            CarGateUiState.Loading -> throw GateAppFunctionException.SystemUnknown("Gate state is still loading.")
            CarGateUiState.SignedOut -> throw GateAppFunctionException.PermissionRequired(
                "Sign in to Doorking Gate before opening gates with voice commands.",
            )
            is CarGateUiState.NoBuzzableGates -> throw GateAppFunctionException.ElementNotFound(state.reason)
            is CarGateUiState.Error -> throw GateAppFunctionException.SystemUnknown(state.message)
            is CarGateUiState.Ready -> state.relays
        }
    }

    fun toOpenableRelays(tiles: List<CarRelayTile>): List<OpenableGateRelay> =
        tiles.map { tile ->
            OpenableGateRelay(
                gateName = tile.gateName,
                relayName = tile.relayName,
                status = tile.statusText,
                canOpen = tile.canBuzz,
                requiresPasskeyConfirmation = tile.requirePasskey,
            )
        }

    fun resolveGateStatus(tiles: List<CarRelayTile>, gateName: String?): GateStatusInfo {
        val candidates = filterByGate(tiles, gateName)
        val distinctGates = candidates.map { it.gatePhone to it.gateName }.distinct()
        if (distinctGates.isEmpty()) {
            val available = tiles.map { it.gateName }.distinct().joinToString(", ")
            throw GateAppFunctionException.ElementNotFound(
                "No gate matched '${gateName.orEmpty()}'. Available gates: $available.",
            )
        }
        if (distinctGates.size > 1) {
            val names = distinctGates.joinToString(", ") { it.second }
            throw GateAppFunctionException.InvalidArgument(
                "Multiple gates match. Specify one of: $names.",
            )
        }
        val first = candidates.first()
        return GateStatusInfo(
            gateName = first.gateName,
            available = first.canBuzz,
            statusMessage = if (first.canBuzz) "Ready" else first.statusText,
            cooldownSeconds = first.cooldownSeconds,
        )
    }

    fun matchRelayTile(
        tiles: List<CarRelayTile>,
        relayName: String,
        gateName: String?,
    ): CarRelayTile {
        val wantedRelay = relayName.trim()
        if (wantedRelay.isEmpty()) {
            throw GateAppFunctionException.InvalidArgument(
                "Specify the relay or entrance name to open, such as Entry.",
            )
        }

        val gateFiltered = filterByGate(tiles, gateName)
        if (gateFiltered.isEmpty()) {
            val availableGates = tiles.map { it.gateName }.distinct().joinToString(", ")
            throw GateAppFunctionException.ElementNotFound(
                "No gate matched '${gateName.orEmpty()}'. Available gates: $availableGates.",
            )
        }

        val exact = gateFiltered.filter { it.relayName.equals(wantedRelay, ignoreCase = true) }
        val matches = exact.ifEmpty {
            gateFiltered.filter { tile ->
                tile.relayName.contains(wantedRelay, ignoreCase = true) ||
                    wantedRelay.contains(tile.relayName, ignoreCase = true)
            }
        }

        if (matches.isEmpty()) {
            val available = gateFiltered.joinToString(", ") { "${it.relayName} (${it.gateName})" }
            throw GateAppFunctionException.ElementNotFound(
                "No relay matched '$wantedRelay'. Available entrances: $available.",
            )
        }
        if (matches.size > 1) {
            val options = matches.joinToString(", ") { "${it.relayName} at ${it.gateName}" }
            throw GateAppFunctionException.InvalidArgument(
                "Multiple entrances matched '$wantedRelay'. Specify the gate name: $options.",
            )
        }
        return matches.first()
    }

    suspend fun openRelay(
        sessionStorage: SessionStorage,
        api: DoorkingApi,
        relayName: String,
        gateName: String?,
        openAppIntentProvider: () -> PendingIntent?,
    ): OpenGateResult {
        val tiles = loadReadyTiles(sessionStorage, api)
        val tile = matchRelayTile(tiles, relayName, gateName)

        if (tile.requirePasskey) {
            return OpenGateResult(
                gateName = tile.gateName,
                relayName = tile.relayName,
                opened = false,
                alreadyOpen = false,
                message = "${tile.gateName} requires confirmation in Doorking Gate before opening ${tile.relayName}.",
                openAppIntent = openAppIntentProvider(),
            )
        }
        if (!tile.canBuzz) {
            throw GateAppFunctionException.InvalidArgument(
                "${tile.relayName} at ${tile.gateName} is currently unavailable: ${tile.statusText}.",
            )
        }

        return when (val outcome = CarGateRepository.buzz(api, tile)) {
            is CarBuzzResult.Opened -> OpenGateResult(
                gateName = tile.gateName,
                relayName = tile.relayName,
                opened = true,
                alreadyOpen = outcome.message.contains("already open", ignoreCase = true),
                message = outcome.message,
            )
            CarBuzzResult.RequiresPhonePasskey -> OpenGateResult(
                gateName = tile.gateName,
                relayName = tile.relayName,
                opened = false,
                alreadyOpen = false,
                message = "${tile.gateName} requires confirmation in Doorking Gate before opening ${tile.relayName}.",
                openAppIntent = openAppIntentProvider(),
            )
            is CarBuzzResult.Failed -> throw GateAppFunctionException.SystemUnknown(outcome.message)
        }
    }

    private fun filterByGate(tiles: List<CarRelayTile>, gateName: String?): List<CarRelayTile> {
        val wantedGate = gateName?.trim().orEmpty()
        if (wantedGate.isEmpty()) return tiles
        val exact = tiles.filter { it.gateName.equals(wantedGate, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        return tiles.filter { tile ->
            tile.gateName.contains(wantedGate, ignoreCase = true) ||
                wantedGate.contains(tile.gateName, ignoreCase = true)
        }
    }
}

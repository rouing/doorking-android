package com.doorking.gate.ai

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.AppFunctionSystemUnknownException
import com.doorking.gate.DoorkingApp
import com.doorking.gate.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 16+ AppFunctionService entry point exposing safe DoorKing Gate voice/agent actions
 * (listing openable relays, checking gate status, and momentarily opening an authorized entrance).
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "DoorkingAppFunctionService",
    appFunctionXmlFileName = "doorking_app_function_service",
)
abstract class BaseDoorkingAppFunctionService : AppFunctionService() {

    private fun container() =
        (applicationContext as? DoorkingApp)?.takeIf { it.startupCrash == null }?.container
            ?: throw AppFunctionSystemUnknownException("Doorking Gate is not ready. Open the app first.")

    private fun launchAppPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private inline fun <T> mapAppFunctionErrors(block: () -> T): T = try {
        block()
    } catch (e: GateAppFunctionException) {
        throw e.toAppFunctionException()
    }

    /**
     * Lists gate entrances and relays the signed-in user is authorized to open, along with each entrance's current availability.
     *
     * @return List of authorized gate relays and their current status.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listOpenableGateRelays(): List<OpenableGateRelay> = withContext(Dispatchers.IO) {
        mapAppFunctionErrors {
            val c = container()
            val tiles = GateAppFunctionLogic.loadReadyTiles(c.sessionStorage, c.api)
            GateAppFunctionLogic.toOpenableRelays(tiles)
        }
    }

    /**
     * Checks whether a DoorKing gate is ready to open, busy syncing, or cooling down between openings.
     *
     * @param gateName Optional gate or property name. If null or blank, resolves automatically when the user has one gate.
     * @return Availability status and remaining cooldown seconds for the matched gate.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getGateStatus(
        gateName: String? = null,
    ): GateStatusInfo = withContext(Dispatchers.IO) {
        mapAppFunctionErrors {
            val c = container()
            val tiles = GateAppFunctionLogic.loadReadyTiles(c.sessionStorage, c.api)
            GateAppFunctionLogic.resolveGateStatus(tiles, gateName)
        }
    }

    /**
     * Momentarily opens an authorized DoorKing gate entrance relay.
     *
     * @param relayName Name of the relay or entrance to open, such as Entry, Exit, or Pedestrian.
     * @param gateName Optional gate or property name. Required when multiple gates share the same relay name.
     * @return Result indicating whether the entrance opened, was already open, or requires confirmation in the app.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun openGateRelay(
        relayName: String,
        gateName: String? = null,
    ): OpenGateResult = withContext(Dispatchers.IO) {
        mapAppFunctionErrors {
            val c = container()
            GateAppFunctionLogic.openRelay(
                sessionStorage = c.sessionStorage,
                api = c.api,
                relayName = relayName,
                gateName = gateName,
                openAppIntentProvider = ::launchAppPendingIntent,
            )
        }
    }
}

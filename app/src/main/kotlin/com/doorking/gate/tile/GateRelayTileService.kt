package com.doorking.gate.tile

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.doorking.gate.DoorkingApp
import com.doorking.gate.R
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.widget.GateWidgetRepository
import com.doorking.gate.widget.WidgetBuzzOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GateRelayTileConfig(
    val gatePhone: String,
    val gateName: String,
    val relay: Int,
    val relayName: String,
    val unitCode: String? = null,
)

/**
 * Persists up to 4 visible gate relays for Android Quick Settings tiles and dynamically enables
 * only the exact slots corresponding to visible relays.
 */
object GateRelayTileManager {

    const val MAX_SLOTS = 4
    private const val PREFS_NAME = "doorking_qs_tiles"
    private const val KEY_SLOTS_JSON = "slots_json"

    private val serviceClasses: List<Class<out BaseGateRelayTileService>> = listOf(
        GateRelayTile1Service::class.java,
        GateRelayTile2Service::class.java,
        GateRelayTile3Service::class.java,
        GateRelayTile4Service::class.java,
    )

    fun loadSlots(context: Context): List<GateRelayTileConfig> {
        val raw = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SLOTS_JSON, null)
        }.getOrNull() ?: return emptyList()
        return runCatching {
            ApiClientFactory.json.decodeFromString<List<GateRelayTileConfig>>(raw).take(MAX_SLOTS)
        }.getOrDefault(emptyList())
    }

    fun getSlot(context: Context, slotIndex: Int): GateRelayTileConfig? =
        loadSlots(context).getOrNull(slotIndex)

    fun syncVisibleRelays(context: Context, relays: List<GateRelayTileConfig>) {
        val appCtx = context.applicationContext
        val clamped = relays.take(MAX_SLOTS)
        runCatching {
            val encoded = ApiClientFactory.json.encodeToString(clamped)
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SLOTS_JSON, encoded)
                .apply()
        }
        val pm = runCatching { appCtx.packageManager }.getOrNull() ?: return
        for (index in 0 until MAX_SLOTS) {
            val component = ComponentName(appCtx, serviceClasses[index])
            val enabled = index < clamped.size
            val desiredState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                if (pm.getComponentEnabledSetting(component) != desiredState) {
                    pm.setComponentEnabledSetting(
                        component,
                        desiredState,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
            if (enabled) {
                runCatching { TileService.requestListeningState(appCtx, component) }
            }
        }
    }

    fun clearAll(context: Context) {
        syncVisibleRelays(context, emptyList())
    }

    fun requestAddTile(
        context: Context,
        slotIndex: Int,
        onResult: ((Int) -> Unit)? = null,
    ): Boolean {
        val cfg = getSlot(context, slotIndex) ?: return false
        val cls = serviceClasses.getOrNull(slotIndex) ?: return false
        val appCtx = context.applicationContext
        val component = ComponentName(appCtx, cls)
        return runCatching {
            val pm = appCtx.packageManager
            if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            val sbm = appCtx.getSystemService(StatusBarManager::class.java) ?: return false
            val label = cfg.relayName.ifBlank { "Relay ${cfg.relay}" }
            val icon = Icon.createWithResource(appCtx, R.drawable.ic_widget_lock)
            sbm.requestAddTileService(
                component,
                label,
                icon,
                ContextCompat.getMainExecutor(appCtx),
            ) { resultCode ->
                onResult?.invoke(resultCode)
            }
            true
        }.getOrDefault(false)
    }
}

abstract class BaseGateRelayTileService(private val slotIndex: Int) : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var buzzJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val cfg = GateRelayTileManager.getSlot(this, slotIndex)
        if (cfg == null) {
            tile.label = getString(R.string.qs_tile_gate_label)
            tile.subtitle = "Unavailable"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_widget_lock)
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()
            return
        }
        if (buzzJob?.isActive == true) return
        tile.label = cfg.relayName.ifBlank { "Relay ${cfg.relay}" }
        tile.subtitle = cfg.gateName.ifBlank { "Tap to open" }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_widget_lock)
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val cfg = GateRelayTileManager.getSlot(this, slotIndex) ?: return
        if (buzzJob?.isActive == true) return
        unlockAndRun {
            val app = applicationContext as? DoorkingApp
            if (app == null || app.startupCrash != null) {
                qsTile?.let { t ->
                    t.state = Tile.STATE_INACTIVE
                    t.subtitle = "Open app"
                    t.updateTile()
                }
                return@unlockAndRun
            }
            qsTile?.let { t ->
                t.label = cfg.relayName.ifBlank { "Relay ${cfg.relay}" }
                t.state = Tile.STATE_ACTIVE
                t.subtitle = "Opening…"
                t.updateTile()
            }
            buzzJob = scope.launch {
                val outcome = GateWidgetRepository.buzz(
                    api = app.container.api,
                    gatePhone = cfg.gatePhone,
                    relay = cfg.relay,
                    relayName = cfg.relayName.ifBlank { "Relay ${cfg.relay}" },
                    unitCode = cfg.unitCode,
                )
                withContext(Dispatchers.Main) {
                    qsTile?.let { t ->
                        when (outcome) {
                            is WidgetBuzzOutcome.Opened -> {
                                t.state = Tile.STATE_ACTIVE
                                t.subtitle = "Opened!"
                            }
                            is WidgetBuzzOutcome.Failed -> {
                                t.state = Tile.STATE_INACTIVE
                                t.subtitle = outcome.message.take(28)
                            }
                        }
                        t.updateTile()
                    }
                }
                delay(3_000L)
                withContext(Dispatchers.Main) {
                    qsTile?.let { t ->
                        t.state = Tile.STATE_INACTIVE
                        t.subtitle = "Tap to open"
                        t.updateTile()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class GateRelayTile1Service : BaseGateRelayTileService(0)
class GateRelayTile2Service : BaseGateRelayTileService(1)
class GateRelayTile3Service : BaseGateRelayTileService(2)
class GateRelayTile4Service : BaseGateRelayTileService(3)

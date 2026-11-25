package com.doorking.gate.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.doorking.gate.DoorkingApp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.tile.GateRelayTileConfig
import com.doorking.gate.tile.GateRelayTileManager
import kotlinx.coroutines.delay

/**
 * Receiver registered in `AndroidManifest.xml`; triggers an immediate WorkManager sync when the widget is placed or enabled.
 */
class GateQuickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GateQuickWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        GateWidgetUpdater.enqueueSync(context)
    }
}

object GateWidgetUpdater {
    private const val WORK_SYNC = "doorking_widget_sync"
    private const val WORK_BUZZ = "doorking_widget_buzz"

    fun enqueueSync(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncGateWidgetWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_SYNC,
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    fun enqueueBuzz(context: Context, phone: String, relay: Int, relayName: String, unitCode: String) {
        val req = OneTimeWorkRequestBuilder<BuzzGateWidgetWorker>()
            .setInputData(
                workDataOf(
                    DATA_PHONE to phone,
                    DATA_RELAY to relay,
                    DATA_RELAY_NAME to relayName,
                    DATA_UNIT_CODE to unitCode,
                ),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_BUZZ,
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    suspend fun writeSnapshotToAll(context: Context, snapshot: GateWidgetSnapshot) {
        val relaysJson = ApiClientFactory.json.encodeToString(snapshot.relays)
        val widget = GateQuickWidget()
        val ids = GlanceAppWidgetManager(context).getGlanceIds(GateQuickWidget::class.java)
        for (glanceId in ids) {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[GateQuickWidget.KEY_SIGNED_IN] = snapshot.signedIn
                prefs[GateQuickWidget.KEY_GATE_PHONE] = snapshot.gatePhone
                prefs[GateQuickWidget.KEY_GATE_NAME] = snapshot.gateName
                prefs[GateQuickWidget.KEY_SUBTITLE] = snapshot.subtitle
                prefs[GateQuickWidget.KEY_STATUS_TEXT] = snapshot.statusText
                prefs[GateQuickWidget.KEY_STATUS_TONE] = snapshot.statusTone
                prefs[GateQuickWidget.KEY_CAN_BUZZ] = snapshot.canBuzz
                prefs[GateQuickWidget.KEY_BUZZ_RELAY] = snapshot.buzzRelay
                prefs[GateQuickWidget.KEY_BUZZ_RELAY_NAME] = snapshot.buzzRelayName
                prefs[GateQuickWidget.KEY_RELAYS_JSON] = relaysJson
                prefs[GateQuickWidget.KEY_UNIT_CODE] = snapshot.unitCode.orEmpty()
                prefs[GateQuickWidget.KEY_BUSY] = snapshot.busy
            }
            // Explicit update required after updateAppWidgetState (per glance-appwidget-updates-and-memory).
            widget.update(context, glanceId)
        }
        if (!snapshot.signedIn) {
            GateRelayTileManager.syncVisibleRelays(context, emptyList())
        } else if (snapshot.statusText != "Offline") {
            val tileConfigs = snapshot.relays.map { entry ->
                GateRelayTileConfig(
                    gatePhone = snapshot.gatePhone,
                    gateName = snapshot.gateName,
                    relay = entry.relay,
                    relayName = entry.name,
                    unitCode = snapshot.unitCode,
                )
            }
            GateRelayTileManager.syncVisibleRelays(context, tileConfigs)
        }
    }

    internal const val DATA_PHONE = "phone"
    internal const val DATA_RELAY = "relay"
    internal const val DATA_RELAY_NAME = "relay_name"
    internal const val DATA_UNIT_CODE = "unit_code"
}

class SyncGateWidgetWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DoorkingApp ?: return Result.failure()
        val snapshot = GateWidgetRepository.fetchSnapshot(app.container.sessionStorage, app.container.api)
        GateWidgetUpdater.writeSnapshotToAll(applicationContext, snapshot)
        return Result.success()
    }
}

class BuzzGateWidgetWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DoorkingApp ?: return Result.failure()
        val phone = inputData.getString(GateWidgetUpdater.DATA_PHONE).orEmpty()
        val relay = inputData.getInt(GateWidgetUpdater.DATA_RELAY, 0)
        val relayName = inputData.getString(GateWidgetUpdater.DATA_RELAY_NAME).orEmpty().ifBlank { "Gate" }
        val unitCode = inputData.getString(GateWidgetUpdater.DATA_UNIT_CODE)?.ifBlank { null }
        if (phone.isBlank()) return Result.failure()

        // Mark widget busy immediately so the user gets visual confirmation.
        val widget = GateQuickWidget()
        val ids = GlanceAppWidgetManager(applicationContext).getGlanceIds(GateQuickWidget::class.java)
        for (glanceId in ids) {
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[GateQuickWidget.KEY_BUSY] = true
                prefs[GateQuickWidget.KEY_STATUS_TEXT] = "Opening…"
                prefs[GateQuickWidget.KEY_STATUS_TONE] = GateWidgetSnapshot.TONE_WARNING
            }
            widget.update(applicationContext, glanceId)
        }

        val outcome = GateWidgetRepository.buzz(app.container.api, phone, relay, relayName, unitCode)
        val (statusText, tone) = when (outcome) {
            is WidgetBuzzOutcome.Opened -> outcome.message to GateWidgetSnapshot.TONE_SUCCESS
            is WidgetBuzzOutcome.Failed -> outcome.message.take(28) to GateWidgetSnapshot.TONE_ERROR
        }

        for (glanceId in ids) {
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[GateQuickWidget.KEY_BUSY] = false
                prefs[GateQuickWidget.KEY_STATUS_TEXT] = statusText
                prefs[GateQuickWidget.KEY_STATUS_TONE] = tone
            }
            widget.update(applicationContext, glanceId)
        }

        // After brief confirmation, refresh live cooldown/status from the server.
        delay(3_000L)
        val fresh = GateWidgetRepository.fetchSnapshot(app.container.sessionStorage, app.container.api)
        GateWidgetUpdater.writeSnapshotToAll(applicationContext, fresh)
        return Result.success()
    }
}

class RefreshGateWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        GateWidgetUpdater.enqueueSync(context)
    }
}

class BuzzGateWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val phone = parameters[KEY_PHONE].orEmpty()
        if (phone.isBlank()) {
            GateWidgetUpdater.enqueueSync(context)
            return
        }
        val relay = parameters[KEY_RELAY] ?: 0
        val relayName = parameters[KEY_RELAY_NAME].orEmpty().ifBlank { "Gate" }
        val unitCode = parameters[KEY_UNIT_CODE].orEmpty()
        GateWidgetUpdater.enqueueBuzz(context, phone, relay, relayName, unitCode)
    }

    companion object {
        val KEY_PHONE = ActionParameters.Key<String>("phone")
        val KEY_RELAY = ActionParameters.Key<Int>("relay")
        val KEY_RELAY_NAME = ActionParameters.Key<String>("relay_name")
        val KEY_UNIT_CODE = ActionParameters.Key<String>("unit_code")
    }
}

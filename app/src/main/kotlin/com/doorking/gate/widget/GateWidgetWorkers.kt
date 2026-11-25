package com.doorking.gate.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.doorking.gate.DoorkingApp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.tile.GateRelayTileConfig
import com.doorking.gate.tile.GateRelayTileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receiver registered in `AndroidManifest.xml`; triggers an immediate WorkManager sync when the widget is updated or placed.
 */
class GateQuickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GateQuickWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        GateWidgetUpdater.enqueueSync(context, ExistingWorkPolicy.KEEP)
    }
}

object GateWidgetUpdater {
    private const val WORK_SYNC = "doorking_widget_sync"
    private const val WORK_BUZZ = "doorking_widget_buzz"
    private val previewsPublished = AtomicBoolean(false)

    fun enqueueSync(
        context: Context,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        requireNetwork: Boolean = true,
        delaySeconds: Long = 0L,
    ) {
        val builder = OneTimeWorkRequestBuilder<SyncGateWidgetWorker>()
        if (requireNetwork) {
            builder.setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
        }
        if (delaySeconds > 0L) {
            builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_SYNC,
            policy,
            builder.build(),
        )
    }

    fun enqueueBuzz(context: Context, phone: String, relay: Int, relayName: String, unitCode: String) {
        val req = OneTimeWorkRequestBuilder<BuzzGateWidgetWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
        val ids = runCatching {
            GlanceAppWidgetManager(context).getGlanceIds(GateQuickWidget::class.java)
        }.onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        for (glanceId in ids) {
            runCatching {
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
            }.onFailure { if (it is CancellationException) throw it }
        }
        if (!snapshot.signedIn) {
            RelaySingleWidget.clearAll(context)
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
        publishWidgetPreviewsOnce(context)
    }

    private suspend fun publishWidgetPreviewsOnce(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (!previewsPublished.compareAndSet(false, true)) return
        val manager = GlanceAppWidgetManager(context)
        val quickOk = runCatching {
            manager.setWidgetPreviews(GateQuickWidgetReceiver::class)
        }.onFailure { err ->
            if (err is CancellationException) {
                previewsPublished.set(false)
                throw err
            }
        }.isSuccess
        val singleOk = runCatching {
            manager.setWidgetPreviews(RelaySingleWidgetReceiver::class)
        }.onFailure { err ->
            if (err is CancellationException) {
                previewsPublished.set(false)
                throw err
            }
        }.isSuccess
        if (!quickOk || !singleOk) {
            previewsPublished.set(false)
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
        if (app.startupCrash != null) return Result.failure()
        val snapshot = GateWidgetRepository.fetchSnapshot(app.container.sessionStorage, app.container.api)
        GateWidgetUpdater.writeSnapshotToAll(applicationContext, snapshot)
        if (snapshot.cooldownSeconds > 0) {
            GateWidgetUpdater.enqueueSync(
                applicationContext,
                policy = ExistingWorkPolicy.REPLACE,
                requireNetwork = true,
                delaySeconds = snapshot.cooldownSeconds + 1L,
            )
        }
        return Result.success()
    }
}

class BuzzGateWidgetWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DoorkingApp ?: return Result.failure()
        if (app.startupCrash != null) return Result.failure()
        val phone = inputData.getString(GateWidgetUpdater.DATA_PHONE).orEmpty()
        val relay = inputData.getInt(GateWidgetUpdater.DATA_RELAY, 0)
        val relayName = inputData.getString(GateWidgetUpdater.DATA_RELAY_NAME).orEmpty().ifBlank { "Gate" }
        val unitCode = inputData.getString(GateWidgetUpdater.DATA_UNIT_CODE)?.ifBlank { null }
        if (phone.isBlank()) return Result.failure()

        if (app.container.sessionStorage.awaitLoaded().sessionToken.isNullOrBlank()) {
            GateWidgetUpdater.writeSnapshotToAll(applicationContext, GateWidgetSnapshot())
            return Result.failure()
        }

        // Mark widget busy immediately so the user gets visual confirmation.
        val widget = GateQuickWidget()
        val manager = GlanceAppWidgetManager(applicationContext)
        val initialIds = runCatching {
            manager.getGlanceIds(GateQuickWidget::class.java)
        }.onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        for (glanceId in initialIds) {
            runCatching {
                updateAppWidgetState(applicationContext, glanceId) { prefs ->
                    prefs[GateQuickWidget.KEY_BUSY] = true
                    prefs[GateQuickWidget.KEY_STATUS_TEXT] = "Opening…"
                    prefs[GateQuickWidget.KEY_STATUS_TONE] = GateWidgetSnapshot.TONE_WARNING
                }
                widget.update(applicationContext, glanceId)
            }.onFailure { if (it is CancellationException) throw it }
        }

        var refreshed = false
        try {
            val outcome = GateWidgetRepository.buzz(
                api = app.container.api,
                gatePhone = phone,
                relay = relay,
                relayName = relayName,
                unitCode = unitCode,
                noInteractiveStepUp = "1",
            )
            val (statusText, tone) = when (outcome) {
                is WidgetBuzzOutcome.Opened -> outcome.message to GateWidgetSnapshot.TONE_SUCCESS
                is WidgetBuzzOutcome.Failed -> outcome.message.take(28) to GateWidgetSnapshot.TONE_ERROR
            }

            val postBuzzIds = runCatching {
                manager.getGlanceIds(GateQuickWidget::class.java)
            }.onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
            for (glanceId in postBuzzIds) {
                runCatching {
                    updateAppWidgetState(applicationContext, glanceId) { prefs ->
                        prefs[GateQuickWidget.KEY_BUSY] = false
                        prefs[GateQuickWidget.KEY_STATUS_TEXT] = statusText
                        prefs[GateQuickWidget.KEY_STATUS_TONE] = tone
                    }
                    widget.update(applicationContext, glanceId)
                }.onFailure { if (it is CancellationException) throw it }
            }

            // After brief confirmation, refresh live cooldown/status from the server.
            delay(3_000L)
            val fresh = GateWidgetRepository.fetchSnapshot(app.container.sessionStorage, app.container.api)
            GateWidgetUpdater.writeSnapshotToAll(applicationContext, fresh)
            refreshed = true
            if (fresh.cooldownSeconds > 0) {
                GateWidgetUpdater.enqueueSync(
                    applicationContext,
                    policy = ExistingWorkPolicy.REPLACE,
                    requireNetwork = true,
                    delaySeconds = fresh.cooldownSeconds + 1L,
                )
            }
        } finally {
            withContext(NonCancellable) {
                if (!refreshed) {
                    val cleanupIds = runCatching {
                        manager.getGlanceIds(GateQuickWidget::class.java)
                    }.onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(emptyList())
                    for (glanceId in cleanupIds) {
                        runCatching {
                            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                                prefs[GateQuickWidget.KEY_BUSY] = false
                                prefs[GateQuickWidget.KEY_STATUS_TEXT] = "Tap refresh"
                                prefs[GateQuickWidget.KEY_STATUS_TONE] = GateWidgetSnapshot.TONE_NEUTRAL
                            }
                            widget.update(applicationContext, glanceId)
                        }.onFailure { if (it is CancellationException) throw it }
                    }
                }
            }
        }
        return Result.success()
    }
}

class RefreshGateWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        GateWidgetUpdater.enqueueSync(context, requireNetwork = false)
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

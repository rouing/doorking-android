package com.doorking.gate.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.doorking.gate.DoorkingApp
import com.doorking.gate.MainActivity
import com.doorking.gate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated 1×1 single-relay Buzz Me In home-screen widget.
 * Users can place one widget per relay from the Android widget catalog (via `RelayWidgetConfigActivity`)
 * or pin directly from User Settings (`RelayWidgetPinHelper`).
 */
class RelaySingleWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val prefs = currentState<Preferences>()
            val gatePhone = prefs[KEY_GATE_PHONE].orEmpty()
            val relay = prefs[KEY_RELAY] ?: 0
            val relayName = prefs[KEY_RELAY_NAME]?.ifBlank { null } ?: "Open Gate"
            val unitCode = prefs[KEY_UNIT_CODE].orEmpty()
            val status = prefs[KEY_STATUS].orEmpty()
            val busy = prefs[KEY_BUSY] ?: false
            val isError = prefs[KEY_IS_ERROR] ?: false
            GlanceTheme {
                SingleRelayTileContent(
                    appWidgetId = appWidgetId,
                    gatePhone = gatePhone,
                    relay = relay,
                    relayName = relayName,
                    unitCode = unitCode,
                    status = status,
                    busy = busy,
                    isError = isError,
                )
            }
        }
    }

    companion object {
        val KEY_GATE_PHONE = stringPreferencesKey("single_gate_phone")
        val KEY_GATE_NAME = stringPreferencesKey("single_gate_name")
        val KEY_RELAY = intPreferencesKey("single_relay")
        val KEY_RELAY_NAME = stringPreferencesKey("single_relay_name")
        val KEY_UNIT_CODE = stringPreferencesKey("single_unit_code")
        val KEY_STATUS = stringPreferencesKey("single_status")
        val KEY_BUSY = booleanPreferencesKey("single_busy")
        val KEY_IS_ERROR = booleanPreferencesKey("single_is_error")

        suspend fun configureWidget(
            context: Context,
            appWidgetId: Int,
            gatePhone: String,
            gateName: String,
            relay: Int,
            relayName: String,
            unitCode: String?,
        ) {
            val manager = GlanceAppWidgetManager(context)
            val glanceId = manager.getGlanceIdBy(appWidgetId)
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[KEY_GATE_PHONE] = gatePhone
                prefs[KEY_GATE_NAME] = gateName
                prefs[KEY_RELAY] = relay
                prefs[KEY_RELAY_NAME] = relayName
                prefs[KEY_UNIT_CODE] = unitCode.orEmpty()
                prefs[KEY_STATUS] = ""
                prefs[KEY_BUSY] = false
                prefs[KEY_IS_ERROR] = false
            }
            RelaySingleWidget().update(context, glanceId)
        }
    }
}

@Composable
private fun SingleRelayTileContent(
    appWidgetId: Int,
    gatePhone: String,
    relay: Int,
    relayName: String,
    unitCode: String,
    status: String,
    busy: Boolean,
    isError: Boolean,
) {
    val bg = when {
        isError -> GlanceTheme.colors.errorContainer
        busy -> GlanceTheme.colors.tertiaryContainer
        else -> GlanceTheme.colors.primaryContainer
    }
    val fg = when {
        isError -> GlanceTheme.colors.onErrorContainer
        busy -> GlanceTheme.colors.onTertiaryContainer
        else -> GlanceTheme.colors.onPrimaryContainer
    }
    val clickAction = if (gatePhone.isNotBlank() && !busy) {
        actionRunCallback<BuzzSingleRelayAction>(
            actionParametersOf(
                BuzzSingleRelayAction.KEY_APP_WIDGET_ID to appWidgetId,
                BuzzSingleRelayAction.KEY_PHONE to gatePhone,
                BuzzSingleRelayAction.KEY_RELAY to relay,
                BuzzSingleRelayAction.KEY_RELAY_NAME to relayName,
                BuzzSingleRelayAction.KEY_UNIT_CODE to unitCode,
            ),
        )
    } else {
        actionStartActivity<MainActivity>()
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(bg)
            .clickable(clickAction)
            .padding(6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_lock),
            contentDescription = "Open $relayName",
            colorFilter = ColorFilter.tint(fg),
            modifier = GlanceModifier.size(22.dp),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = status.ifBlank { relayName },
            maxLines = 2,
            style = TextStyle(
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

class RelaySingleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RelaySingleWidget()
}

class BuzzSingleRelayAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appWidgetId = parameters[KEY_APP_WIDGET_ID] ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val phone = parameters[KEY_PHONE].orEmpty()
        val relay = parameters[KEY_RELAY] ?: 0
        val relayName = parameters[KEY_RELAY_NAME].orEmpty().ifBlank { "Gate" }
        val unitCode = parameters[KEY_UNIT_CODE].orEmpty()
        if (phone.isBlank() || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val req = OneTimeWorkRequestBuilder<BuzzSingleRelayWorker>()
            .setInputData(
                workDataOf(
                    BuzzSingleRelayWorker.DATA_WIDGET_ID to appWidgetId,
                    BuzzSingleRelayWorker.DATA_PHONE to phone,
                    BuzzSingleRelayWorker.DATA_RELAY to relay,
                    BuzzSingleRelayWorker.DATA_RELAY_NAME to relayName,
                    BuzzSingleRelayWorker.DATA_UNIT_CODE to unitCode,
                ),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "doorking_single_relay_buzz_$appWidgetId",
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    companion object {
        val KEY_APP_WIDGET_ID = ActionParameters.Key<Int>("app_widget_id")
        val KEY_PHONE = ActionParameters.Key<String>("phone")
        val KEY_RELAY = ActionParameters.Key<Int>("relay")
        val KEY_RELAY_NAME = ActionParameters.Key<String>("relay_name")
        val KEY_UNIT_CODE = ActionParameters.Key<String>("unit_code")
    }
}

class BuzzSingleRelayWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DoorkingApp ?: return Result.failure()
        val appWidgetId = inputData.getInt(DATA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val phone = inputData.getString(DATA_PHONE).orEmpty()
        val relay = inputData.getInt(DATA_RELAY, 0)
        val relayName = inputData.getString(DATA_RELAY_NAME).orEmpty().ifBlank { "Gate" }
        val unitCode = inputData.getString(DATA_UNIT_CODE)?.ifBlank { null }
        if (phone.isBlank() || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return Result.failure()

        val manager = GlanceAppWidgetManager(applicationContext)
        val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull() ?: return Result.failure()
        val widget = RelaySingleWidget()

        updateAppWidgetState(applicationContext, glanceId) { prefs ->
            prefs[RelaySingleWidget.KEY_BUSY] = true
            prefs[RelaySingleWidget.KEY_IS_ERROR] = false
            prefs[RelaySingleWidget.KEY_STATUS] = "Opening…"
        }
        widget.update(applicationContext, glanceId)

        val outcome = GateWidgetRepository.buzz(app.container.api, phone, relay, relayName, unitCode)
        val (statusText, isError) = when (outcome) {
            is WidgetBuzzOutcome.Opened -> "Opened!" to false
            is WidgetBuzzOutcome.Failed -> outcome.message.take(24) to true
        }
        updateAppWidgetState(applicationContext, glanceId) { prefs ->
            prefs[RelaySingleWidget.KEY_BUSY] = false
            prefs[RelaySingleWidget.KEY_IS_ERROR] = isError
            prefs[RelaySingleWidget.KEY_STATUS] = statusText
        }
        widget.update(applicationContext, glanceId)

        delay(4_000L)
        updateAppWidgetState(applicationContext, glanceId) { prefs ->
            prefs[RelaySingleWidget.KEY_BUSY] = false
            prefs[RelaySingleWidget.KEY_IS_ERROR] = false
            prefs[RelaySingleWidget.KEY_STATUS] = ""
        }
        widget.update(applicationContext, glanceId)
        return Result.success()
    }

    companion object {
        const val DATA_WIDGET_ID = "widget_id"
        const val DATA_PHONE = "phone"
        const val DATA_RELAY = "relay"
        const val DATA_RELAY_NAME = "relay_name"
        const val DATA_UNIT_CODE = "unit_code"
    }
}

object RelayWidgetPinHelper {
    fun canPin(context: Context): Boolean =
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    fun requestPin(
        context: Context,
        gatePhone: String,
        gateName: String,
        relay: Int,
        relayName: String,
        unitCode: String?,
    ): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        val provider = ComponentName(context, RelaySingleWidgetReceiver::class.java)
        val callbackIntent = Intent(context, RelayWidgetPinCallbackReceiver::class.java).apply {
            action = RelayWidgetPinCallbackReceiver.ACTION_PINNED
            putExtra(RelayWidgetPinCallbackReceiver.EXTRA_GATE_PHONE, gatePhone)
            putExtra(RelayWidgetPinCallbackReceiver.EXTRA_GATE_NAME, gateName)
            putExtra(RelayWidgetPinCallbackReceiver.EXTRA_RELAY, relay)
            putExtra(RelayWidgetPinCallbackReceiver.EXTRA_RELAY_NAME, relayName)
            putExtra(RelayWidgetPinCallbackReceiver.EXTRA_UNIT_CODE, unitCode.orEmpty())
        }
        val pi = PendingIntent.getBroadcast(
            context,
            (gatePhone.hashCode() * 31) + relay,
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return manager.requestPinAppWidget(provider, null, pi)
    }
}

class RelayWidgetPinCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PINNED) return
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val gatePhone = intent.getStringExtra(EXTRA_GATE_PHONE).orEmpty()
        val gateName = intent.getStringExtra(EXTRA_GATE_NAME).orEmpty()
        val relay = intent.getIntExtra(EXTRA_RELAY, 0)
        val relayName = intent.getStringExtra(EXTRA_RELAY_NAME).orEmpty().ifBlank { "Relay $relay" }
        val unitCode = intent.getStringExtra(EXTRA_UNIT_CODE)?.ifBlank { null }
        if (gatePhone.isBlank()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RelaySingleWidget.configureWidget(context.applicationContext, appWidgetId, gatePhone, gateName, relay, relayName, unitCode)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PINNED = "com.doorking.gate.widget.ACTION_RELAY_WIDGET_PINNED"
        const val EXTRA_GATE_PHONE = "gate_phone"
        const val EXTRA_GATE_NAME = "gate_name"
        const val EXTRA_RELAY = "relay"
        const val EXTRA_RELAY_NAME = "relay_name"
        const val EXTRA_UNIT_CODE = "unit_code"
    }
}

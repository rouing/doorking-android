package com.doorking.gate.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
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
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.doorking.gate.DoorkingApp
import com.doorking.gate.MainActivity
import com.doorking.gate.R
import com.doorking.gate.core.network.ApiClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Dedicated 1×1 single-relay Buzz Me In home-screen widget.
 * Users can place one widget per relay from the Android widget catalog (via `RelayWidgetConfigActivity`)
 * or pin directly from User Settings (`RelayWidgetPinHelper`).
 */
class RelaySingleWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
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

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent {
            GlanceTheme {
                SingleRelayTileContent(
                    gatePhone = "",
                    relay = 0,
                    relayName = "Main Gate",
                    unitCode = "",
                    status = "",
                    busy = false,
                    isError = false,
                )
            }
        }
    }

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        val openIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fallback = RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "Tap to open Doorking Gate")
            setOnClickPendingIntent(android.R.id.text1, openIntent)
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, fallback)
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

        suspend fun clearAll(context: Context) {
            withContext(Dispatchers.IO) {
                RelayWidgetPinHelper.clearPendingPins(context)
            }
            val widget = RelaySingleWidget()
            val ids = runCatching {
                GlanceAppWidgetManager(context).getGlanceIds(RelaySingleWidget::class.java)
            }.onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
            for (glanceId in ids) {
                runCatching {
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs.remove(KEY_GATE_PHONE)
                        prefs.remove(KEY_GATE_NAME)
                        prefs.remove(KEY_RELAY)
                        prefs.remove(KEY_RELAY_NAME)
                        prefs.remove(KEY_UNIT_CODE)
                        prefs[KEY_BUSY] = false
                        prefs[KEY_STATUS] = ""
                        prefs[KEY_IS_ERROR] = false
                    }
                    widget.update(context, glanceId)
                }.onFailure { if (it is CancellationException) throw it }
            }
        }
    }
}

@Composable
private fun SingleRelayTileContent(
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
        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        }.onFailure { if (it is CancellationException) throw it }
            .getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
        val phone = parameters[KEY_PHONE].orEmpty()
        val relay = parameters[KEY_RELAY] ?: 0
        val relayName = parameters[KEY_RELAY_NAME].orEmpty().ifBlank { "Gate" }
        val unitCode = parameters[KEY_UNIT_CODE].orEmpty()
        if (phone.isBlank() || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val req = OneTimeWorkRequestBuilder<BuzzSingleRelayWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
        if (app.startupCrash != null) return Result.failure()
        val appWidgetId = inputData.getInt(DATA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val phone = inputData.getString(DATA_PHONE).orEmpty()
        val relay = inputData.getInt(DATA_RELAY, 0)
        val relayName = inputData.getString(DATA_RELAY_NAME).orEmpty().ifBlank { "Gate" }
        val unitCode = inputData.getString(DATA_UNIT_CODE)?.ifBlank { null }
        if (phone.isBlank() || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return Result.failure()

        if (app.container.sessionStorage.awaitLoaded().sessionToken.isNullOrBlank()) {
            RelaySingleWidget.clearAll(applicationContext)
            return Result.failure()
        }

        val manager = GlanceAppWidgetManager(applicationContext)
        val initialGlanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return Result.failure()
        val widget = RelaySingleWidget()

        runCatching {
            updateAppWidgetState(applicationContext, initialGlanceId) { prefs ->
                prefs[RelaySingleWidget.KEY_BUSY] = true
                prefs[RelaySingleWidget.KEY_IS_ERROR] = false
                prefs[RelaySingleWidget.KEY_STATUS] = "Opening…"
            }
            widget.update(applicationContext, initialGlanceId)
        }.onFailure { if (it is CancellationException) throw it }

        try {
            val outcome = GateWidgetRepository.buzz(
                api = app.container.api,
                gatePhone = phone,
                relay = relay,
                relayName = relayName,
                unitCode = unitCode,
                noInteractiveStepUp = "1",
            )
            val (statusText, isError) = when (outcome) {
                is WidgetBuzzOutcome.Opened -> "Opened!" to false
                is WidgetBuzzOutcome.Failed -> outcome.message.take(24) to true
            }
            runCatching {
                val postBuzzId = manager.getGlanceIdBy(appWidgetId)
                updateAppWidgetState(applicationContext, postBuzzId) { prefs ->
                    prefs[RelaySingleWidget.KEY_BUSY] = false
                    prefs[RelaySingleWidget.KEY_IS_ERROR] = isError
                    prefs[RelaySingleWidget.KEY_STATUS] = statusText
                }
                widget.update(applicationContext, postBuzzId)
            }.onFailure { if (it is CancellationException) throw it }

            delay(4_000L)
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    val resetId = manager.getGlanceIdBy(appWidgetId)
                    updateAppWidgetState(applicationContext, resetId) { prefs ->
                        prefs[RelaySingleWidget.KEY_BUSY] = false
                        prefs[RelaySingleWidget.KEY_IS_ERROR] = false
                        prefs[RelaySingleWidget.KEY_STATUS] = ""
                    }
                    widget.update(applicationContext, resetId)
                }.onFailure { if (it is CancellationException) throw it }
            }
        }
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

@Serializable
internal data class PendingPinConfig(
    val gatePhone: String,
    val gateName: String,
    val relay: Int,
    val relayName: String,
    val unitCode: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)

object RelayWidgetPinHelper {
    private const val PREFS_PENDING_PINS = "doorking_pending_pins"
    private const val PENDING_PIN_TTL_MS = 15L * 60L * 1000L
    private val pinLock = Any()

    fun canPin(context: Context): Boolean =
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    suspend fun requestPin(
        context: Context,
        gatePhone: String,
        gateName: String,
        relay: Int,
        relayName: String,
        unitCode: String?,
    ): Boolean {
        val manager = runCatching { AppWidgetManager.getInstance(context) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return false
        val supported = runCatching { manager.isRequestPinAppWidgetSupported }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)
        if (!supported) return false

        val token = UUID.randomUUID().toString()
        val config = PendingPinConfig(
            gatePhone = gatePhone,
            gateName = gateName,
            relay = relay,
            relayName = relayName,
            unitCode = unitCode?.ifBlank { null },
        )
        withContext(Dispatchers.IO) {
            storePendingPin(context, token, config)
        }

        val provider = ComponentName(context, RelaySingleWidgetReceiver::class.java)
        val callbackIntent = Intent(context, RelayWidgetPinCallbackReceiver::class.java).apply {
            action = RelayWidgetPinCallbackReceiver.ACTION_PINNED
            putExtra(RelayWidgetPinCallbackReceiver.EXTRA_PIN_TOKEN, token)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            token.hashCode(),
            callbackIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
        )
        val extras = Bundle().apply {
            putParcelable(
                AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                RemoteViews(context.packageName, R.layout.widget_preview_relay_single).apply {
                    setTextViewText(R.id.preview_relay_title, relayName)
                },
            )
        }
        val requested = withContext(Dispatchers.Main.immediate) {
            runCatching {
                manager.requestPinAppWidget(provider, extras, pi)
            }.onFailure { if (it is CancellationException) throw it }
                .getOrDefault(false)
        }
        if (!requested) {
            withContext(Dispatchers.IO + NonCancellable) {
                removePendingPin(context, token)
            }
        }
        return requested
    }

    private fun storePendingPin(context: Context, token: String, config: PendingPinConfig) {
        synchronized(pinLock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_PINS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val editor = prefs.edit()
            for ((key, value) in prefs.all) {
                val raw = value as? String ?: continue
                val parsed = runCatching {
                    ApiClientFactory.json.decodeFromString<PendingPinConfig>(raw)
                }.getOrNull()
                if (parsed == null || now - parsed.createdAtMs > PENDING_PIN_TTL_MS) {
                    editor.remove(key)
                }
            }
            editor.putString(token, ApiClientFactory.json.encodeToString(config))
            editor.apply()
        }
    }

    private fun removePendingPin(context: Context, token: String) {
        synchronized(pinLock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_PINS, Context.MODE_PRIVATE)
            prefs.edit().remove(token).apply()
        }
    }

    internal fun consumePendingPin(context: Context, token: String): PendingPinConfig? {
        synchronized(pinLock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_PINS, Context.MODE_PRIVATE)
            val raw = prefs.getString(token, null) ?: return null
            prefs.edit().remove(token).apply()
            val parsed = runCatching {
                ApiClientFactory.json.decodeFromString<PendingPinConfig>(raw)
            }.getOrNull() ?: return null
            if (System.currentTimeMillis() - parsed.createdAtMs > PENDING_PIN_TTL_MS) {
                return null
            }
            return parsed
        }
    }

    internal fun clearPendingPins(context: Context) {
        synchronized(pinLock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_PENDING_PINS, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }
    }
}

class RelayWidgetPinCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PINNED) return
        val appWidgetId = runCatching {
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val pinToken = runCatching {
            intent.getStringExtra(EXTRA_PIN_TOKEN)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        val appContext = context.applicationContext
        val expectedProvider = ComponentName(appContext, RelaySingleWidgetReceiver::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    withTimeout(8_000L) {
                        val actualProvider = runCatching {
                            AppWidgetManager.getInstance(appContext).getAppWidgetInfo(appWidgetId)?.provider
                        }.onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                        if (actualProvider != expectedProvider) return@withTimeout

                        val config = RelayWidgetPinHelper.consumePendingPin(appContext, pinToken)
                            ?: return@withTimeout
                        if (config.gatePhone.isBlank()) return@withTimeout

                        RelaySingleWidget.configureWidget(
                            context = appContext,
                            appWidgetId = appWidgetId,
                            gatePhone = config.gatePhone,
                            gateName = config.gateName,
                            relay = config.relay,
                            relayName = config.relayName.ifBlank { "Relay ${config.relay}" },
                            unitCode = config.unitCode?.ifBlank { null },
                        )
                    }
                }.onFailure { if (it is CancellationException) throw it }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PINNED = "com.doorking.gate.widget.ACTION_RELAY_WIDGET_PINNED"
        const val EXTRA_PIN_TOKEN = "pin_token"
    }
}

package com.doorking.gate.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.doorking.gate.MainActivity
import com.doorking.gate.R
import com.doorking.gate.core.network.ApiClientFactory

/**
 * Material 3 Jetpack Glance home-screen widget for 1-tap "Buzz Me In" and live gate status.
 * Uses `SizeMode.Exact` (per `glance-canonical-layouts`) and pure vector drawables (zero large bitmaps).
 */
class GateQuickWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode: PreviewSizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 110.dp), DpSize(250.dp, 110.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val snapshot = remember(prefs) { snapshotFromPrefs(prefs) }
            GlanceTheme {
                GateWidgetContent(snapshot = snapshot)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent {
            GlanceTheme {
                GateWidgetContent(snapshot = previewSnapshot(), isPreview = true)
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
        val KEY_SIGNED_IN = booleanPreferencesKey("signed_in")
        val KEY_GATE_PHONE = stringPreferencesKey("gate_phone")
        val KEY_GATE_NAME = stringPreferencesKey("gate_name")
        val KEY_SUBTITLE = stringPreferencesKey("subtitle")
        val KEY_STATUS_TEXT = stringPreferencesKey("status_text")
        val KEY_STATUS_TONE = stringPreferencesKey("status_tone")
        val KEY_CAN_BUZZ = booleanPreferencesKey("can_buzz")
        val KEY_BUZZ_RELAY = intPreferencesKey("buzz_relay")
        val KEY_BUZZ_RELAY_NAME = stringPreferencesKey("buzz_relay_name")
        val KEY_RELAYS_JSON = stringPreferencesKey("relays_json")
        val KEY_UNIT_CODE = stringPreferencesKey("unit_code")
        val KEY_BUSY = booleanPreferencesKey("busy")

        @Volatile
        private var cachedRelays: Pair<String, List<WidgetRelayEntry>>? = null

        internal fun decodeRelays(raw: String?): List<WidgetRelayEntry> {
            if (raw.isNullOrBlank()) return emptyList()
            cachedRelays?.let { (cachedRaw, entries) ->
                if (cachedRaw == raw) return entries
            }
            val decoded = runCatching {
                ApiClientFactory.json.decodeFromString<List<WidgetRelayEntry>>(raw)
            }.getOrNull().orEmpty()
            cachedRelays = raw to decoded
            return decoded
        }

        internal fun previewSnapshot(): GateWidgetSnapshot = GateWidgetSnapshot(
            signedIn = true,
            gatePhone = "",
            gateName = "Gate",
            subtitle = "Unit 101 · Ready",
            statusText = "Ready",
            statusTone = GateWidgetSnapshot.TONE_SUCCESS,
            canBuzz = true,
            buzzRelay = 0,
            buzzRelayName = "Main Gate",
            relays = listOf(
                WidgetRelayEntry(relay = 0, name = "Main Gate"),
                WidgetRelayEntry(relay = 1, name = "Walk Gate"),
            ),
            unitCode = "101",
            busy = false,
        )

        fun snapshotFromPrefs(prefs: Preferences): GateWidgetSnapshot {
            val decodedRelays = decodeRelays(prefs[KEY_RELAYS_JSON])
            return GateWidgetSnapshot(
                signedIn = prefs[KEY_SIGNED_IN] ?: false,
                gatePhone = prefs[KEY_GATE_PHONE].orEmpty(),
                gateName = prefs[KEY_GATE_NAME] ?: "Doorking Gate",
                subtitle = prefs[KEY_SUBTITLE] ?: "Tap to open app",
                statusText = prefs[KEY_STATUS_TEXT] ?: "Tap refresh",
                statusTone = prefs[KEY_STATUS_TONE] ?: GateWidgetSnapshot.TONE_NEUTRAL,
                canBuzz = prefs[KEY_CAN_BUZZ] ?: false,
                buzzRelay = prefs[KEY_BUZZ_RELAY] ?: 0,
                buzzRelayName = prefs[KEY_BUZZ_RELAY_NAME] ?: "Gate",
                relays = decodedRelays,
                unitCode = prefs[KEY_UNIT_CODE]?.ifBlank { null },
                busy = prefs[KEY_BUSY] ?: false,
            )
        }
    }
}

internal enum class TinyTileStyle {
    BUSY,
    ACTIVE,
    MUTED,
}

internal data class TinyTileState(
    val label: String,
    val contentDescription: String,
    val style: TinyTileStyle,
)

internal fun resolveTinyTileState(snapshot: GateWidgetSnapshot): TinyTileState = when {
    snapshot.busy -> TinyTileState(
        label = "Opening…",
        contentDescription = "Opening ${snapshot.buzzRelayName}",
        style = TinyTileStyle.BUSY,
    )
    !snapshot.signedIn -> TinyTileState(
        label = "Sign in",
        contentDescription = "Sign in to Doorking Gate",
        style = TinyTileStyle.MUTED,
    )
    !snapshot.canBuzz -> {
        val status = snapshot.statusText.ifBlank { "Gate" }
        TinyTileState(
            label = status,
            contentDescription = status,
            style = TinyTileStyle.MUTED,
        )
    }
    else -> TinyTileState(
        label = snapshot.buzzRelayName,
        contentDescription = "Open ${snapshot.buzzRelayName}",
        style = TinyTileStyle.ACTIVE,
    )
}

@Composable
private fun GateWidgetContent(
    snapshot: GateWidgetSnapshot,
    isPreview: Boolean = false,
) {
    val size = LocalSize.current
    val isTiny = size.width < 120.dp || size.height < 90.dp
    val compact = size.height < 120.dp

    if (isTiny) {
        val tinyState = resolveTinyTileState(snapshot)
        val tileBg = when (tinyState.style) {
            TinyTileStyle.BUSY -> GlanceTheme.colors.tertiaryContainer
            TinyTileStyle.ACTIVE -> GlanceTheme.colors.primaryContainer
            TinyTileStyle.MUTED -> GlanceTheme.colors.surfaceVariant
        }
        val tileFg = when (tinyState.style) {
            TinyTileStyle.BUSY -> GlanceTheme.colors.onTertiaryContainer
            TinyTileStyle.ACTIVE -> GlanceTheme.colors.onPrimaryContainer
            TinyTileStyle.MUTED -> GlanceTheme.colors.onSurfaceVariant
        }
        val action = if (!isPreview && snapshot.signedIn && snapshot.canBuzz && snapshot.gatePhone.isNotBlank() && !snapshot.busy) {
            actionRunCallback<BuzzGateWidgetAction>(
                actionParametersOf(
                    BuzzGateWidgetAction.KEY_PHONE to snapshot.gatePhone,
                    BuzzGateWidgetAction.KEY_RELAY to snapshot.buzzRelay,
                    BuzzGateWidgetAction.KEY_RELAY_NAME to snapshot.buzzRelayName,
                    BuzzGateWidgetAction.KEY_UNIT_CODE to snapshot.unitCode.orEmpty(),
                ),
            )
        } else {
            actionStartActivity<MainActivity>()
        }
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(tileBg)
                .clickable(action)
                .padding(6.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_lock),
                contentDescription = tinyState.contentDescription,
                colorFilter = ColorFilter.tint(tileFg),
                modifier = GlanceModifier.size(22.dp),
            )
            Spacer(GlanceModifier.height(3.dp))
            Text(
                text = tinyState.label,
                maxLines = 1,
                style = TextStyle(
                    color = tileFg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        return
    }

    val statusBg = when (snapshot.statusTone) {
        GateWidgetSnapshot.TONE_SUCCESS -> GlanceTheme.colors.primaryContainer
        GateWidgetSnapshot.TONE_WARNING -> GlanceTheme.colors.tertiaryContainer
        GateWidgetSnapshot.TONE_ERROR -> GlanceTheme.colors.errorContainer
        else -> GlanceTheme.colors.surfaceVariant
    }
    val statusFg = when (snapshot.statusTone) {
        GateWidgetSnapshot.TONE_SUCCESS -> GlanceTheme.colors.onPrimaryContainer
        GateWidgetSnapshot.TONE_WARNING -> GlanceTheme.colors.onTertiaryContainer
        GateWidgetSnapshot.TONE_ERROR -> GlanceTheme.colors.onErrorContainer
        else -> GlanceTheme.colors.onSurfaceVariant
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(34.dp)
                        .cornerRadius(10.dp)
                        .background(GlanceTheme.colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_gate),
                        contentDescription = "Doorking Gate",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                        modifier = GlanceModifier.size(20.dp),
                    )
                }
                Spacer(GlanceModifier.width(10.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = snapshot.gateName,
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    if (!compact && snapshot.subtitle.isNotBlank()) {
                        Text(
                            text = snapshot.subtitle,
                            maxLines = 1,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 12.sp,
                            ),
                        )
                    }
                }
            }
            Spacer(GlanceModifier.width(6.dp))
            Box(
                modifier = GlanceModifier
                    .background(statusBg)
                    .cornerRadius(999.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (snapshot.busy) "Opening…" else snapshot.statusText,
                    maxLines = 1,
                    style = TextStyle(
                        color = statusFg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            // Dedicated 48dp touch target for refreshing widget state (Android accessibility minimum).
            Box(
                modifier = GlanceModifier
                    .size(48.dp)
                    .cornerRadius(24.dp)
                    .clickable(
                        if (isPreview) actionStartActivity<MainActivity>() else actionRunCallback<RefreshGateWidgetAction>(),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_refresh),
                    contentDescription = "Refresh gate status",
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.size(20.dp),
                )
            }
        }

        Spacer(GlanceModifier.height(10.dp))

        when {
            !snapshot.signedIn -> {
                Button(
                    text = "Sign in to Doorking Gate",
                    onClick = actionStartActivity<MainActivity>(),
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
            snapshot.canBuzz && (isPreview || snapshot.gatePhone.isNotBlank()) -> {
                val visibleRelays = snapshot.relays
                    .ifEmpty { listOf(WidgetRelayEntry(snapshot.buzzRelay, snapshot.buzzRelayName)) }
                    .take(6)
                when {
                    visibleRelays.size == 1 -> {
                        val entry = visibleRelays.first()
                        RelayBuzzButton(
                            snapshot = snapshot,
                            entry = entry,
                            label = if (snapshot.busy) "Opening ${entry.name}…" else "Open ${entry.name}",
                            modifier = GlanceModifier.fillMaxWidth(),
                            isPreview = isPreview,
                        )
                    }
                    visibleRelays.size in 2..3 && size.width < 220.dp -> {
                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                            visibleRelays.forEachIndexed { idx, entry ->
                                if (idx > 0) Spacer(GlanceModifier.height(6.dp))
                                RelayBuzzButton(
                                    snapshot = snapshot,
                                    entry = entry,
                                    label = if (snapshot.busy) "Opening…" else entry.name,
                                    modifier = GlanceModifier.fillMaxWidth(),
                                    isPreview = isPreview,
                                )
                            }
                        }
                    }
                    visibleRelays.size in 2..3 -> {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            visibleRelays.forEachIndexed { idx, entry ->
                                if (idx > 0) Spacer(GlanceModifier.width(6.dp))
                                RelayBuzzButton(
                                    snapshot = snapshot,
                                    entry = entry,
                                    label = if (snapshot.busy) "Opening…" else entry.name,
                                    modifier = GlanceModifier.defaultWeight(),
                                    isPreview = isPreview,
                                )
                            }
                        }
                    }
                    else -> {
                        val rows = visibleRelays.chunked(2)
                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                            rows.forEachIndexed { rowIdx, pair ->
                                if (rowIdx > 0) Spacer(GlanceModifier.height(6.dp))
                                Row(modifier = GlanceModifier.fillMaxWidth()) {
                                    pair.forEachIndexed { colIdx, entry ->
                                        if (colIdx > 0) Spacer(GlanceModifier.width(6.dp))
                                        RelayBuzzButton(
                                            snapshot = snapshot,
                                            entry = entry,
                                            label = if (snapshot.busy) "Opening…" else entry.name,
                                            modifier = GlanceModifier.defaultWeight(),
                                            isPreview = isPreview,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                Button(
                    text = "Open Doorking Gate",
                    onClick = actionStartActivity<MainActivity>(),
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RelayBuzzButton(
    snapshot: GateWidgetSnapshot,
    entry: WidgetRelayEntry,
    label: String,
    modifier: GlanceModifier,
    isPreview: Boolean = false,
) {
    val onClick = if (isPreview) {
        actionStartActivity<MainActivity>()
    } else {
        actionRunCallback<BuzzGateWidgetAction>(
            actionParametersOf(
                BuzzGateWidgetAction.KEY_PHONE to snapshot.gatePhone,
                BuzzGateWidgetAction.KEY_RELAY to entry.relay,
                BuzzGateWidgetAction.KEY_RELAY_NAME to entry.name,
                BuzzGateWidgetAction.KEY_UNIT_CODE to snapshot.unitCode.orEmpty(),
            ),
        )
    }
    Button(
        text = label,
        enabled = !snapshot.busy,
        maxLines = 1,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = GlanceTheme.colors.primary,
            contentColor = GlanceTheme.colors.onPrimary,
        ),
        onClick = onClick,
        modifier = modifier,
    )
}

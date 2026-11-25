package com.doorking.gate.feature.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.doorking.gate.core.ui.components.CollapsibleSectionCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.ApiKeyInfoDto
import com.doorking.gate.core.network.CameraSettingsDto
import com.doorking.gate.core.network.CameraTestChannelDto
import com.doorking.gate.core.network.CreateApiKeyRequest
import com.doorking.gate.core.network.CreateWebhookRequest
import com.doorking.gate.core.network.DeviceRangeDto
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.GateLoginDto
import com.doorking.gate.core.network.GateLoginUpdateRequest
import com.doorking.gate.core.network.GatePrefsDto
import com.doorking.gate.core.network.GateUserDto
import com.doorking.gate.core.network.LoginAuditEntryDto
import com.doorking.gate.core.network.ResidentRequestDto
import com.doorking.gate.core.network.ResolveReportRequest
import com.doorking.gate.core.network.ReviewResidentRequestBody
import com.doorking.gate.core.network.ShareGateRequest
import com.doorking.gate.core.network.SuspiciousReportDto
import com.doorking.gate.core.network.WebhookInfoDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PasswordOutlinedField
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.StatusPill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val NO_SEGMENT_BORDER = BorderStroke(0.dp, Color.Transparent)
private val GATE_ROLE_OPTIONS = listOf("readonly", "editor", "admin")
private val API_KEY_SCOPE_PRESETS = listOf(
    "full" to "Full role",
    "mcpRead" to "MCP Read-Only",
    "mcpStaging" to "MCP Staging",
)
private val MCP_READ_SCOPES = listOf(
    "gate:read", "directory:read", "transactions:read", "insights:read",
    "history:read", "settings:read", "relays:read", "schedules:read", "cameras:read",
)
private val MCP_STAGING_SCOPES = listOf(
    "gate:read", "directory:read", "directory:write", "transactions:read",
    "insights:read", "history:read", "settings:read", "settings:write",
    "relays:read", "schedules:read", "cameras:read", "gate:pull",
)
private val DEFAULT_WEBHOOK_EVENTS = listOf("send.completed", "relay.changed")
private val DEFAULT_BUZZ_RELAYS = listOf(0)
private val CAMERA_MODE_OPTIONS = listOf("snapshots" to "Snapshots", "video" to "Video")
private val CAMERA_FRAME_OPTIONS = listOf(1, 2, 3, 4, 6)
private val CAMERA_FRAMES_AFTER_OPTIONS = listOf(0, 1, 2, 3, 4, 6)
private val CAMERA_CLIP_TRIES_OPTIONS = listOf(1, 2, 3, 4, 5)
private val CAMERA_EVENT_OPTIONS = listOf("access" to "Access events", "all" to "All events")
private val CAMERA_KEEP_DAYS_OPTIONS = listOf(0 to "Forever", 15 to "15d", 30 to "30d", 60 to "60d", 90 to "90d")
private const val MAX_CAMERA_CHANNELS = 8
private const val DEFAULT_CAMERA_CHANNEL_ROWS = 4

internal data class CameraChannelFormRow(
    val channel: String = "",
    val name: String = "",
    val relays: List<Int> = emptyList(),
)

internal data class CameraFormState(
    val enabled: Boolean = true,
    val serial: String = "",
    val username: String = "",
    val password: String = "",
    val clockOffset: String = "0",
    val mode: String = "snapshots",
    val frames: Int = 3,
    val framesAfter: Int = 0,
    val clipTries: Int = 2,
    val events: String = "access",
    val keepDays: Int = 0,
    val backfillHours: String = "24",
    val channels: List<CameraChannelFormRow> = defaultCameraChannelRows(null),
)

internal fun defaultCameraChannelRows(saved: CameraSettingsDto?): List<CameraChannelFormRow> {
    val existing = saved?.channels.orEmpty()
    if (existing.isNotEmpty()) {
        return existing.take(MAX_CAMERA_CHANNELS).map { ch ->
            CameraChannelFormRow(
                channel = ch.channel.toString(),
                name = ch.name,
                relays = ch.relays.distinct().sorted(),
            )
        }
    }
    return List(DEFAULT_CAMERA_CHANNEL_ROWS) { CameraChannelFormRow() }
}

internal fun cameraFormStateFrom(saved: CameraSettingsDto?): CameraFormState =
    if (saved == null) {
        CameraFormState()
    } else {
        CameraFormState(
            enabled = saved.enabled,
            serial = saved.serial,
            username = saved.username,
            password = "",
            clockOffset = saved.clockOffset.toString(),
            mode = if (saved.mode == "video") "video" else "snapshots",
            frames = saved.frames,
            framesAfter = saved.framesAfter,
            clipTries = saved.clipTries,
            events = if (saved.events == "all") "all" else "access",
            keepDays = saved.keepDays,
            backfillHours = saved.backfillHours.toString(),
            channels = defaultCameraChannelRows(saved),
        )
    }

internal fun shouldConfirmCameraRetention(previousKeepDays: Int, nextKeepDays: Int): Boolean =
    nextKeepDays > 0 && (previousKeepDays == 0 || nextKeepDays < previousKeepDays)

internal fun buildCameraSettingsPayload(form: CameraFormState): JsonObject {
    val channelsJson = JsonArray(
        form.channels.mapNotNull { row ->
            val trimmedChannel = row.channel.trim()
            if (trimmedChannel.isEmpty()) return@mapNotNull null
            buildJsonObject {
                val parsed = trimmedChannel.toIntOrNull()
                if (parsed != null) {
                    put("channel", JsonPrimitive(parsed))
                } else {
                    put("channel", JsonPrimitive(trimmedChannel))
                }
                put("name", JsonPrimitive(row.name.trim().take(40)))
                put("relays", JsonArray(row.relays.distinct().sorted().map { JsonPrimitive(it) }))
            }
        },
    )
    val clockTrimmed = form.clockOffset.trim()
    val backfillTrimmed = form.backfillHours.trim()
    return buildJsonObject {
        put("enabled", JsonPrimitive(form.enabled))
        put("serial", JsonPrimitive(form.serial.trim()))
        put("username", JsonPrimitive(form.username.trim()))
        if (form.password.isNotEmpty()) {
            put("password", JsonPrimitive(form.password))
        }
        val clockInt = if (clockTrimmed.isEmpty()) 0 else clockTrimmed.toIntOrNull()
        if (clockInt != null) put("clockOffset", JsonPrimitive(clockInt)) else put("clockOffset", JsonPrimitive(clockTrimmed))
        put("mode", JsonPrimitive(form.mode))
        put("frames", JsonPrimitive(form.frames))
        put("framesAfter", JsonPrimitive(form.framesAfter))
        put("clipTries", JsonPrimitive(form.clipTries))
        put("events", JsonPrimitive(form.events))
        val backfillInt = if (backfillTrimmed.isEmpty()) 24 else backfillTrimmed.toIntOrNull()
        if (backfillInt != null) put("backfillHours", JsonPrimitive(backfillInt)) else put("backfillHours", JsonPrimitive(backfillTrimmed))
        put("keepDays", JsonPrimitive(form.keepDays))
        put("channels", channelsJson)
    }
}

private suspend inline fun <T> safeSystemCall(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private fun parseSystemApiError(raw: String?, code: Int, fallback: String): String {
    if (!raw.isNullOrBlank()) {
        val parsed = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw) }.getOrNull()
        if (parsed != null && parsed.error.isNotBlank()) return parsed.error
    }
    return "$fallback (HTTP $code)"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SystemSettingsScreen(
    gatePhone: String,
    api: DoorkingApi,
    isAdmin: Boolean,
    meUsername: String,
    cameraTestApi: DoorkingApi = api,
    onMessage: (String) -> Unit = {},
    onDeletedGate: () -> Unit,
    onReloadGate: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<GateLoginDto?>(null) }
    var loginSyncVersion by remember { mutableIntStateOf(0) }
    var users by remember { mutableStateOf<List<GateUserDto>>(emptyList()) }
    var apiKeys by remember { mutableStateOf<List<ApiKeyInfoDto>>(emptyList()) }
    var webhooks by remember { mutableStateOf<List<WebhookInfoDto>>(emptyList()) }
    var audit by remember { mutableStateOf<List<LoginAuditEntryDto>>(emptyList()) }
    var reports by remember { mutableStateOf<List<SuspiciousReportDto>>(emptyList()) }
    var requests by remember { mutableStateOf<List<ResidentRequestDto>>(emptyList()) }
    var prefs by remember { mutableStateOf(GatePrefsDto()) }
    var cameras by remember { mutableStateOf<CameraSettingsDto?>(null) }
    var camerasSyncVersion by remember { mutableIntStateOf(0) }
    var createdKey by remember(gatePhone) { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Destructive confirmation dialog targets (P0 safety)
    var removeUserTarget by remember { mutableStateOf<GateUserDto?>(null) }
    var revokeKeyTarget by remember { mutableStateOf<ApiKeyInfoDto?>(null) }
    var deleteWebhookTarget by remember { mutableStateOf<WebhookInfoDto?>(null) }

    // Collapsible sections
    var openLogin by remember { mutableStateOf(true) }
    var openUsers by remember { mutableStateOf(false) }
    var openPortal by remember { mutableStateOf(false) }
    var openAudit by remember { mutableStateOf(false) }
    var openKeys by remember { mutableStateOf(false) }
    var openWebhooks by remember { mutableStateOf(false) }
    var openCameras by remember { mutableStateOf(false) }
    var openDevices by remember { mutableStateOf(false) }
    var openDanger by remember { mutableStateOf(false) }
    var confirmDeleteGate by remember { mutableStateOf(false) }

    val deviceStates = remember { List(10) { mutableStateOf(DeviceRangeDto()) } }
    var hasLoadedOnce by remember(gatePhone) { mutableStateOf(false) }

    val pendingRequests = remember(requests) { requests.filter { it.status == "pending" } }
    val openReports = remember(reports) { reports.filter { it.status == "open" } }
    val gateRelays = remember(prefs.tracker) { openableRelayIndices(prefs.tracker) }

    val scope = rememberCoroutineScope()

    suspend fun refreshPrefs() {
        safeSystemCall { api.getPrefs(gatePhone) }?.let { loaded ->
            prefs = loaded
            repeat(10) { i -> deviceStates[i].value = loaded.deviceAssociation.getOrElse(i) { DeviceRangeDto() } }
        }
    }

    suspend fun refreshLogin() {
        safeSystemCall { api.getGateLogin(gatePhone) }?.let { loaded ->
            login = loaded
            loginSyncVersion++
        }
    }

    suspend fun refreshUsers() {
        safeSystemCall { api.getGateUsers(gatePhone).users }?.let { users = it }
    }

    suspend fun refreshApiKeys() {
        safeSystemCall { api.getApiKeys(gatePhone).apiKeys }?.let { apiKeys = it }
    }

    suspend fun refreshWebhooks() {
        safeSystemCall { api.getWebhooks(gatePhone).webhooks }?.let { webhooks = it }
    }

    suspend fun refreshRequests() {
        safeSystemCall { api.getAdminResidentRequests(gatePhone).requests }?.let { requests = it }
    }

    suspend fun refreshReports() {
        safeSystemCall { api.getSuspiciousReports(gatePhone).reports }?.let { reports = it }
    }

    suspend fun refreshCameras() {
        val loaded = safeSystemCall { api.getCameras(gatePhone) }
        if (loaded != null) {
            cameras = loaded.settings
            camerasSyncVersion++
        }
    }

    suspend fun load(initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        error = null
        runCatching {
            val p = api.getPrefs(gatePhone)
            prefs = p
            repeat(10) { i -> deviceStates[i].value = p.deviceAssociation.getOrElse(i) { DeviceRangeDto() } }
            if (isAdmin) {
                coroutineScope {
                    val loginDef = async { safeSystemCall { api.getGateLogin(gatePhone) } }
                    val usersDef = async { safeSystemCall { api.getGateUsers(gatePhone).users } ?: emptyList() }
                    val keysDef = async { safeSystemCall { api.getApiKeys(gatePhone).apiKeys } ?: emptyList() }
                    val webhooksDef = async { safeSystemCall { api.getWebhooks(gatePhone).webhooks } ?: emptyList() }
                    val auditDef = async { safeSystemCall { api.getLoginAudit(gatePhone).entries } ?: emptyList() }
                    val reportsDef = async { safeSystemCall { api.getSuspiciousReports(gatePhone).reports } ?: emptyList() }
                    val requestsDef = async { safeSystemCall { api.getAdminResidentRequests(gatePhone).requests } ?: emptyList() }
                    val camsDef = async { safeSystemCall { api.getCameras(gatePhone).settings } }

                    login = loginDef.await()
                    loginSyncVersion++
                    users = usersDef.await()
                    apiKeys = keysDef.await()
                    webhooks = webhooksDef.await()
                    audit = auditDef.await()
                    reports = reportsDef.await()
                    requests = requestsDef.await()
                    cameras = camsDef.await()
                    camerasSyncVersion++
                }
            }
        }
            .onSuccess { hasLoadedOnce = true; loading = false; refreshing = false }
            .onFailure {
                if (it is CancellationException) throw it
                error = ApiClientFactory.parseErrorMessage(it)
                loading = false
                refreshing = false
            }
    }

    LaunchedEffect(gatePhone) { load(initial = true) }

    if (removeUserTarget != null) {
        val target = removeUserTarget!!
        AlertDialog(
            onDismissRequest = { removeUserTarget = null },
            title = { Text("Remove ${target.username}?") },
            text = { Text("Revokes ${target.username}'s access to this gate immediately.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        removeUserTarget = null
                        scope.launch {
                            runCatching {
                                val res = api.removeGateUser(gatePhone, target.username)
                                if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Remove failed"))
                            }
                                .onSuccess { onMessage("Removed ${target.username}."); refreshUsers() }
                                .onFailure { error = it.message ?: "Remove failed" }
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeUserTarget = null }) { Text("Cancel") } },
        )
    }

    if (revokeKeyTarget != null) {
        val target = revokeKeyTarget!!
        AlertDialog(
            onDismissRequest = { revokeKeyTarget = null },
            title = { Text("Revoke API key \"${target.name}\"?") },
            text = { Text("Anything using this key stops working immediately. This cannot be undone.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        revokeKeyTarget = null
                        scope.launch {
                            runCatching {
                                val res = api.revokeApiKey(gatePhone, target.id)
                                if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Revoke failed"))
                            }
                                .onSuccess { onMessage("Revoked \"${target.name}\"."); refreshApiKeys() }
                                .onFailure { error = it.message ?: "Revoke failed" }
                        }
                    },
                ) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revokeKeyTarget = null }) { Text("Cancel") } },
        )
    }

    if (deleteWebhookTarget != null) {
        val target = deleteWebhookTarget!!
        AlertDialog(
            onDismissRequest = { deleteWebhookTarget = null },
            title = { Text("Delete webhook \"${target.name}\"?") },
            text = { Text("Stops event deliveries to ${target.url}.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        deleteWebhookTarget = null
                        scope.launch {
                            runCatching {
                                val res = api.deleteWebhook(gatePhone, target.id)
                                if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Delete failed"))
                            }
                                .onSuccess { onMessage("Deleted webhook \"${target.name}\"."); refreshWebhooks() }
                                .onFailure { error = it.message ?: "Delete failed" }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteWebhookTarget = null }) { Text("Cancel") } },
        )
    }

    if (confirmDeleteGate) {
        AlertDialog(
            onDismissRequest = { confirmDeleteGate = false },
            title = { Text("Delete all stored gate data?") },
            text = { Text("Permanently removes this gate, history, snapshots, and all user access from this server. Nothing on the physical gate changes.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        confirmDeleteGate = false
                        scope.launch {
                            runCatching {
                                val res = api.deleteGateData(gatePhone)
                                if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Delete failed"))
                            }
                                .onSuccess { onDeletedGate() }
                                .onFailure { error = it.message ?: "Delete failed" }
                        }
                    },
                ) { Text("Delete all data") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteGate = false }) { Text("Cancel") } },
        )
    }

    if (loading && !hasLoadedOnce) {
        FullScreenLoading("Loading system settings…")
        return
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { load(initial = false) } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item(key = "err", contentType = "error") {
                    ErrorBanner(error!!, onRetry = { scope.launch { load(initial = false) } })
                }
            }

            if (isAdmin) {
                // 1. Gate Login
                item(key = "login", contentType = "section_login") {
                    CollapsibleSection(
                        title = "Gate Login (DKS Cloud)",
                        expanded = openLogin,
                        onToggle = { openLogin = !openLogin },
                        helpText = "The DKS Cloud username, password, and 4-digit gate master code used for every pull, send, live relay action, and scheduled transfer. Stored encrypted on your server.",
                    ) {
                        GateLoginSectionContent(
                            gatePhone = gatePhone,
                            api = api,
                            login = login,
                            syncVersion = loginSyncVersion,
                            onError = { error = it },
                            onMessage = onMessage,
                            onSaved = { refreshLogin() },
                        )
                    }
                }

                // 2. Gate Users & Share
                item(key = "users", contentType = "section_users") {
                    CollapsibleSection("Gate Users (${users.size})", openUsers, { openUsers = !openUsers }) {
                        GateUsersSectionContent(
                            gatePhone = gatePhone,
                            api = api,
                            users = users,
                            meUsername = meUsername,
                            onRequestRemoveUser = { removeUserTarget = it },
                            onError = { error = it },
                            onMessage = onMessage,
                            onShared = { refreshUsers() },
                        )
                    }
                }

                // 3. Security, Portal & Review Queues
                item(key = "portal", contentType = "section_portal") {
                    val pendingCount = pendingRequests.size + openReports.size
                    CollapsibleSection(
                        title = "Security & Resident Portal" + if (pendingCount > 0) " ($pendingCount)" else "",
                        expanded = openPortal,
                        onToggle = { openPortal = !openPortal },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Require strong sign-in for admin actions", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = prefs.requireStrongAdmin,
                                onCheckedChange = { next ->
                                    scope.launch {
                                        val body = buildJsonObject { put("requireStrongAdmin", JsonPrimitive(next)) }
                                        runCatching {
                                            val res = api.updatePrefs(gatePhone, body)
                                            if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Save failed"))
                                        }
                                            .onSuccess { refreshPrefs(); onReloadGate() }
                                            .onFailure { error = it.message ?: ApiClientFactory.parseErrorMessage(it) }
                                    }
                                },
                            )
                        }

                        val portalCfg = prefs.residentPortal
                        val portalEnabled = portalCfg?.enabled == true
                        val buzzEnabled = portalCfg?.buzzEnabled == true
                        val activeBuzzRelays = (portalCfg?.buzzRelays ?: DEFAULT_BUZZ_RELAYS).ifEmpty { DEFAULT_BUZZ_RELAYS }

                        fun savePortalPatch(
                            nextEnabled: Boolean = portalEnabled,
                            nextBuzzEnabled: Boolean = buzzEnabled,
                            nextBuzzRelays: List<Int> = activeBuzzRelays,
                        ) {
                            scope.launch {
                                val body = buildJsonObject {
                                    put(
                                        "residentPortal",
                                        buildJsonObject {
                                            put("enabled", JsonPrimitive(nextEnabled))
                                            put("buzzEnabled", JsonPrimitive(nextBuzzEnabled))
                                            put(
                                                "buzzRelays",
                                                JsonArray(nextBuzzRelays.distinct().sorted().map { JsonPrimitive(it) }),
                                            )
                                            put("pulseSeconds", JsonPrimitive(portalCfg?.pulseSeconds ?: 4))
                                            put("maxRerollsPerDay", JsonPrimitive(portalCfg?.maxRerollsPerDay ?: 2))
                                            put("autoSendReroll", JsonPrimitive(portalCfg?.autoSendReroll == true))
                                            put("showSnapshots", JsonPrimitive(portalCfg?.showSnapshots == true))
                                            put("allowRequests", JsonPrimitive(portalCfg?.allowRequests != false))
                                            put("requirePasskeyForBuzz", JsonPrimitive(portalCfg?.requirePasskeyForBuzz == true))
                                        },
                                    )
                                }
                                runCatching {
                                    val res = api.updatePrefs(gatePhone, body)
                                    if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Save failed"))
                                }
                                    .onSuccess { refreshPrefs(); onReloadGate() }
                                    .onFailure { error = it.message ?: ApiClientFactory.parseErrorMessage(it) }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Enable Resident Portal", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = portalEnabled, onCheckedChange = { savePortalPatch(nextEnabled = it) })
                        }
                        if (portalEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Allow residents to Buzz Me In", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(checked = buzzEnabled, onCheckedChange = { savePortalPatch(nextBuzzEnabled = it) })
                            }
                            if (buzzEnabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Relays residents can open (admins always see all relays)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        gateRelays.forEach { relay ->
                                            val selected = relay in activeBuzzRelays
                                            val label = relayDisplayName(prefs.relayLabels, relay)
                                            FilterChip(
                                                selected = selected,
                                                onClick = {
                                                    val next = if (selected) activeBuzzRelays.filterNot { it == relay } else activeBuzzRelays + relay
                                                    savePortalPatch(nextBuzzRelays = next.ifEmpty { listOf(gateRelays.first()) })
                                                },
                                                label = { Text(label) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        pendingRequests.forEach { req ->
                            BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Text("Unit ${req.directoryCode} · ${req.kind}", style = MaterialTheme.typography.titleSmall)
                                if (req.note.isNotBlank()) Text(req.note, style = MaterialTheme.typography.bodySmall)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            scope.launch {
                                                runCatching { api.reviewResidentRequest(gatePhone, req.id, ReviewResidentRequestBody("rejected")) }
                                                    .onSuccess { onMessage("Request rejected."); refreshRequests() }
                                                    .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                                            }
                                        },
                                    ) { Text("Reject") }
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            scope.launch {
                                                runCatching { api.reviewResidentRequest(gatePhone, req.id, ReviewResidentRequestBody("approved", apply = true)) }
                                                    .onSuccess { onMessage("Request approved."); refreshRequests(); onReloadGate() }
                                                    .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                                            }
                                        },
                                    ) { Text("Approve") }
                                }
                            }
                        }
                        openReports.forEach { rep ->
                            BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Text("Suspicious · Unit ${rep.directoryCode}", style = MaterialTheme.typography.titleSmall)
                                Text(rep.txSummary, style = MaterialTheme.typography.bodySmall)
                                if (rep.note.isNotBlank()) Text(rep.note, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        scope.launch {
                                            runCatching { api.resolveSuspiciousReport(gatePhone, rep.id, ResolveReportRequest("Reviewed in app")) }
                                                .onSuccess { onMessage("Report resolved."); refreshReports() }
                                                .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                                        }
                                    },
                                ) { Text("Mark resolved") }
                            }
                        }
                    }
                }

                // 4. Login Audit Log
                item(key = "audit", contentType = "section_audit") {
                    CollapsibleSection("Login Audit Log (${audit.size})", openAudit, { openAudit = !openAudit }) {
                        audit.take(25).forEach { e ->
                            val tone = when (e.outcome) {
                                "success" -> PillTone.Success
                                "failed", "rate_limited" -> PillTone.Error
                                else -> PillTone.Neutral
                            }
                            BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(e.username ?: e.identifier, style = MaterialTheme.typography.titleSmall)
                                    StatusPill(e.outcome, tone = tone)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusPill(e.method, tone = PillTone.Primary)
                                    if (e.ip.isNotBlank()) StatusPill(e.ip, tone = PillTone.Neutral)
                                    StatusPill(e.createdAt.take(16).replace('T', ' '), tone = PillTone.Neutral)
                                }
                            }
                        }
                    }
                }

                // 5. API Keys (compact bubble cards + full-width Name input row)
                item(key = "keys", contentType = "section_keys") {
                    CollapsibleSection(
                        title = "API Keys (${apiKeys.size})",
                        expanded = openKeys,
                        onToggle = { openKeys = !openKeys },
                        helpText = "Scoped tokens for scripts, home automation, or MCP clients to access this gate without signing in. Each key is restricted to this gate and its assigned role/scopes.",
                    ) {
                        ApiKeysSectionContent(
                            gatePhone = gatePhone,
                            api = api,
                            apiKeys = apiKeys,
                            createdKey = createdKey,
                            onCreatedKeyChange = { createdKey = it },
                            onRequestRevokeKey = { revokeKeyTarget = it },
                            onError = { error = it },
                            onMessage = onMessage,
                            onCreated = { refreshApiKeys() },
                        )
                    }
                }

                // 6. Webhooks
                item(key = "webhooks", contentType = "section_webhooks") {
                    CollapsibleSection("Webhooks (${webhooks.size})", openWebhooks, { openWebhooks = !openWebhooks }) {
                        WebhooksSectionContent(
                            gatePhone = gatePhone,
                            api = api,
                            webhooks = webhooks,
                            onRequestDeleteWebhook = { deleteWebhookTarget = it },
                            onError = { error = it },
                            onMessage = onMessage,
                            onAdded = { refreshWebhooks() },
                        )
                    }
                }

                // 6b. Cameras (full recorder & channel configuration — always visible to admins)
                item(key = "cameras", contentType = "section_cameras") {
                    val configuredCount = cameras?.channels?.size ?: 0
                    val cameraTitle = if (cameras != null) "Cameras ($configuredCount)" else "Cameras"
                    CollapsibleSection(
                        title = cameraTitle,
                        expanded = openCameras,
                        onToggle = { openCameras = !openCameras },
                        helpText = "A Dahua/Amcrest recorder reached through Dahua's P2P cloud. After each transactions pull, snapshots or 2-minute clips are saved for matching minutes and shown on Activity. Shared by every account managing this gate; the password is encrypted on the server.",
                    ) {
                        CameraSettingsSection(
                            gatePhone = gatePhone,
                            api = api,
                            cameraTestApi = cameraTestApi,
                            cameras = cameras,
                            syncVersion = camerasSyncVersion,
                            gateRelays = gateRelays,
                            relayLabels = prefs.relayLabels,
                            onMessage = onMessage,
                            onCamerasChanged = {
                                refreshCameras()
                                onReloadGate()
                            },
                        )
                    }
                }
            }

            // 7. Device Association (responsive 2-line rows so 5-digit codes & names never clip at large font scales)
            item(key = "devices", contentType = "section_devices") {
                CollapsibleSection(
                    title = "Device Association",
                    expanded = openDevices,
                    onToggle = { openDevices = !openDevices },
                    helpText = "Friendly names for ranges of card or clicker numbers (for example 00001–00500 = Residents). Shown as the Event Type on the Activity feed.",
                ) {
                    repeat(deviceStates.size) { i ->
                        DeviceAssociationRow(
                            index = i,
                            state = deviceStates[i],
                        )
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                val arr = JsonArray(
                                    deviceStates.map { state ->
                                        val d = state.value
                                        buildJsonObject {
                                            put("name", JsonPrimitive(d.name.trim()))
                                            put("from", JsonPrimitive(d.from.trim()))
                                            put("to", JsonPrimitive(d.to.trim()))
                                        }
                                    },
                                )
                                val body = buildJsonObject { put("deviceAssociation", arr) }
                                runCatching {
                                    val res = api.updatePrefs(gatePhone, body)
                                    if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Save failed"))
                                }
                                    .onSuccess { onMessage("Device association saved."); refreshPrefs(); onReloadGate() }
                                    .onFailure { error = it.message ?: "Save failed" }
                            }
                        },
                    ) { Text("Save device association") }
                }
            }

            // 8. Danger Zone
            if (isAdmin) {
                item(key = "danger", contentType = "section_danger") {
                    CollapsibleSection(
                        title = "Danger Zone",
                        expanded = openDanger,
                        onToggle = { openDanger = !openDanger },
                        helpText = "Removes everything your DoorKing server has stored for this gate (working copy, history, users, API keys). The physical gate hardware itself is untouched.",
                    ) {
                        Text(
                            "Removes all locally stored data, history, snapshots, and user access for this gate. Nothing on the physical gate changes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { confirmDeleteGate = true },
                        ) { Text("Delete all data for this gate…") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GateLoginSectionContent(
    gatePhone: String,
    api: DoorkingApi,
    login: GateLoginDto?,
    syncVersion: Int,
    onError: (String?) -> Unit,
    onMessage: (String) -> Unit,
    onSaved: suspend () -> Unit,
) {
    var dksUser by remember(gatePhone, login?.dksUser, syncVersion) { mutableStateOf(login?.dksUser.orEmpty()) }
    var dksPassword by remember(gatePhone) { mutableStateOf("") }
    var masterCode by remember(gatePhone) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    OutlinedTextField(
        value = dksUser,
        onValueChange = { dksUser = it },
        label = { Text("DKS user") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordOutlinedField(
        value = dksPassword,
        onValueChange = { dksPassword = it },
        label = if (login?.dksPasswordSet == true) "DKS password (blank keeps saved)" else "DKS password",
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordOutlinedField(
        value = masterCode,
        onValueChange = { masterCode = it },
        label = "Master code (4 digits, blank keeps saved)",
        digitsOnly = true,
        maxLength = 4,
        showVisibilityToggle = false,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onError(null)
            scope.launch {
                runCatching {
                    val res = api.updateGateLogin(gatePhone, GateLoginUpdateRequest(dksUser.trim(), dksPassword, masterCode))
                    if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Save failed"))
                }
                    .onSuccess {
                        dksPassword = ""
                        masterCode = ""
                        onMessage("Gate login updated.")
                        onSaved()
                    }
                    .onFailure { onError(it.message ?: "Save failed") }
            }
        },
    ) { Text("Save gate login") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GateUsersSectionContent(
    gatePhone: String,
    api: DoorkingApi,
    users: List<GateUserDto>,
    meUsername: String,
    onRequestRemoveUser: (GateUserDto) -> Unit,
    onError: (String?) -> Unit,
    onMessage: (String) -> Unit,
    onShared: suspend () -> Unit,
) {
    var shareUser by remember(gatePhone) { mutableStateOf("") }
    var shareRole by remember(gatePhone) { mutableStateOf("readonly") }
    val scope = rememberCoroutineScope()

    users.forEach { u ->
        val roleTone = when (u.role) {
            "admin" -> PillTone.Primary
            "editor" -> PillTone.Warning
            "resident" -> PillTone.Success
            else -> PillTone.Neutral
        }
        BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(u.username, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(u.role.replaceFirstChar(Char::uppercase), tone = roleTone)
                        if (u.invited) StatusPill("Invited", tone = PillTone.Warning)
                        if (u.email.isNotBlank()) StatusPill(u.email, tone = PillTone.Neutral)
                    }
                }
                if (u.username != meUsername) {
                    IconButton(onClick = { onRequestRemoveUser(u) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remove ${u.username}", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = shareUser,
            onValueChange = { shareUser = it },
            label = { Text("Share with username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = shareUser.isNotBlank(),
            onClick = {
                scope.launch {
                    runCatching {
                        val res = api.shareGate(gatePhone, ShareGateRequest(shareUser.trim(), shareRole))
                        if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Share failed"))
                    }
                        .onSuccess {
                            shareUser = ""
                            onMessage("Gate shared.")
                            onShared()
                        }
                        .onFailure { onError(it.message ?: "Share failed") }
                }
            },
        ) { Text("Share") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GATE_ROLE_OPTIONS.forEach { r ->
            FilterChip(selected = shareRole == r, onClick = { shareRole = r }, label = { Text(r.replaceFirstChar(Char::uppercase)) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiKeysSectionContent(
    gatePhone: String,
    api: DoorkingApi,
    apiKeys: List<ApiKeyInfoDto>,
    createdKey: Pair<String, String>?,
    onCreatedKeyChange: (Pair<String, String>?) -> Unit,
    onRequestRevokeKey: (ApiKeyInfoDto) -> Unit,
    onError: (String?) -> Unit,
    onMessage: (String) -> Unit,
    onCreated: suspend () -> Unit,
) {
    var keyName by remember(gatePhone) { mutableStateOf("") }
    var keyRole by remember(gatePhone) { mutableStateOf("readonly") }
    var keyScopePreset by remember(gatePhone) { mutableStateOf("full") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    if (createdKey != null) {
        BubbleCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Text("Key \"${createdKey.first}\" created — copy now:", style = MaterialTheme.typography.titleSmall)
            Text(createdKey.second, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("DoorKing API Key", createdKey.second))
                    onMessage("API key copied.")
                }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Text("Copy", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { onCreatedKeyChange(null) }) { Text("Done") }
            }
        }
    }
    apiKeys.forEach { k ->
        BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(k.name, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(k.role.replaceFirstChar(Char::uppercase), tone = if (k.role == "admin") PillTone.Primary else PillTone.Neutral)
                        if (!k.scopes.isNullOrEmpty()) {
                            StatusPill("Scoped (${k.scopes.size})", tone = PillTone.Primary)
                        }
                        StatusPill(k.lastUsedAt?.take(10)?.let { "Used $it" } ?: "Unused", tone = PillTone.Neutral)
                    }
                }
                OutlinedButton(
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = { onRequestRevokeKey(k) },
                ) { Text("Revoke") }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = keyName,
            onValueChange = { keyName = it.take(40) },
            label = { Text("New key name") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = keyName.isNotBlank(),
            onClick = {
                scope.launch {
                    runCatching {
                        val selectedScopes = when (keyScopePreset) {
                            "mcpRead" -> MCP_READ_SCOPES
                            "mcpStaging" -> MCP_STAGING_SCOPES
                            else -> null
                        }
                        val res = api.createApiKey(gatePhone, CreateApiKeyRequest(keyName.trim(), keyRole, 0, selectedScopes))
                        if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Create failed"))
                        res.body()!!
                    }
                        .onSuccess { created ->
                            onCreatedKeyChange(created.apiKey.name to created.key)
                            keyName = ""
                            keyScopePreset = "full"
                            onCreated()
                        }
                        .onFailure { onError(it.message ?: "Create failed") }
                }
            },
        ) { Text("Create") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GATE_ROLE_OPTIONS.forEach { r ->
            FilterChip(selected = keyRole == r, onClick = { keyRole = r }, label = { Text(r.replaceFirstChar(Char::uppercase)) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        API_KEY_SCOPE_PRESETS.forEach { (presetKey, label) ->
            FilterChip(
                selected = keyScopePreset == presetKey,
                onClick = {
                    keyScopePreset = presetKey
                    if (presetKey == "mcpStaging" && keyRole == "readonly") keyRole = "editor"
                },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun WebhooksSectionContent(
    gatePhone: String,
    api: DoorkingApi,
    webhooks: List<WebhookInfoDto>,
    onRequestDeleteWebhook: (WebhookInfoDto) -> Unit,
    onError: (String?) -> Unit,
    onMessage: (String) -> Unit,
    onAdded: suspend () -> Unit,
) {
    var whName by remember(gatePhone) { mutableStateOf("") }
    var whUrl by remember(gatePhone) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    webhooks.forEach { w ->
        BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(w.name, style = MaterialTheme.typography.titleSmall)
                    Text(w.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { api.testWebhook(gatePhone, w.id) }
                                .onSuccess { onMessage("Test webhook sent.") }
                                .onFailure { onError(ApiClientFactory.parseErrorMessage(it)) }
                        }
                    }) { Text("Test") }
                    IconButton(onClick = { onRequestDeleteWebhook(w) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete webhook ${w.name}", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    OutlinedTextField(
        value = whName,
        onValueChange = { whName = it },
        label = { Text("Webhook name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = whUrl,
        onValueChange = { whUrl = it },
        label = { Text("HTTPS URL") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = whName.isNotBlank() && whUrl.startsWith("https://"),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                runCatching {
                    val res = api.createWebhook(gatePhone, CreateWebhookRequest(whName.trim(), whUrl.trim(), DEFAULT_WEBHOOK_EVENTS))
                    if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Add webhook failed"))
                }
                    .onSuccess {
                        whName = ""
                        whUrl = ""
                        onMessage("Webhook added.")
                        onAdded()
                    }
                    .onFailure { onError(it.message ?: "Add failed") }
            }
        },
    ) { Text("Add webhook") }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CameraSettingsSection(
    gatePhone: String,
    api: DoorkingApi,
    cameraTestApi: DoorkingApi,
    cameras: CameraSettingsDto?,
    syncVersion: Int,
    gateRelays: List<Int>,
    relayLabels: Map<String, String>,
    onMessage: (String) -> Unit,
    onCamerasChanged: suspend () -> Unit,
) {
    var form by remember(gatePhone, cameras, syncVersion) { mutableStateOf(cameraFormStateFrom(cameras)) }
    var saving by remember(gatePhone) { mutableStateOf(false) }
    var testing by remember(gatePhone) { mutableStateOf(false) }
    var deleting by remember(gatePhone) { mutableStateOf(false) }
    var statusMessage by remember(gatePhone) { mutableStateOf<String?>(null) }
    var errorMessage by remember(gatePhone) { mutableStateOf<String?>(null) }
    var testResults by remember(gatePhone) { mutableStateOf<List<CameraTestChannelDto>?>(null) }
    var confirmRetention by remember(gatePhone) { mutableStateOf(false) }
    var confirmDeleteCamera by remember(gatePhone) { mutableStateOf(false) }
    var confirmDeleteSnapshots by remember(gatePhone) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hasSavedSettings = cameras != null
    val busy = saving || testing || deleting
    val isVideo = form.mode == "video"

    fun performSave() {
        errorMessage = null
        statusMessage = null
        saving = true
        val payload = buildCameraSettingsPayload(form)
        scope.launch {
            runCatching {
                val res = api.saveCameras(gatePhone, payload)
                if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Save failed"))
            }
                .onSuccess {
                    saving = false
                    form = form.copy(password = "")
                    statusMessage = "Saved. Use Test cameras to verify the login and channels."
                    onMessage("Camera settings saved.")
                    onCamerasChanged()
                }
                .onFailure {
                    saving = false
                    errorMessage = it.message ?: "Save failed"
                }
        }
    }

    if (confirmRetention) {
        val keep = form.keepDays
        AlertDialog(
            onDismissRequest = { confirmRetention = false },
            title = { Text("Keep pictures for $keep days?") },
            text = { Text("Pictures and videos older than $keep days are deleted now, and each day from then on. This cannot be undone.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        confirmRetention = false
                        performSave()
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { confirmRetention = false }) { Text("Cancel") } },
        )
    }

    if (confirmDeleteCamera) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCamera = false },
            title = { Text("Remove camera settings?") },
            text = { Text("Removes the recorder credentials and channel settings for this gate. Saved snapshots are kept.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        confirmDeleteCamera = false
                        errorMessage = null
                        statusMessage = null
                        deleting = true
                        scope.launch {
                            runCatching {
                                val res = api.deleteCameras(gatePhone)
                                if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Remove failed"))
                            }
                                .onSuccess {
                                    deleting = false
                                    testResults = null
                                    statusMessage = "Camera settings removed."
                                    onMessage("Camera settings removed.")
                                    onCamerasChanged()
                                }
                                .onFailure {
                                    deleting = false
                                    errorMessage = it.message ?: "Remove failed"
                                }
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteCamera = false }) { Text("Cancel") } },
        )
    }

    if (confirmDeleteSnapshots) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSnapshots = false },
            title = { Text("Delete all saved snapshots?") },
            text = { Text("Permanently deletes every saved picture and video for this gate. This cannot be undone. Transactions still in the backfill window get new pictures on the next snapshot run.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        confirmDeleteSnapshots = false
                        errorMessage = null
                        statusMessage = null
                        deleting = true
                        scope.launch {
                            runCatching {
                                val res = api.deleteAllSnapshots(gatePhone)
                                if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Delete failed"))
                                res.body()
                            }
                                .onSuccess { reply ->
                                    deleting = false
                                    testResults = null
                                    val files = reply?.files ?: 0
                                    val minutes = reply?.minutes ?: 0
                                    val summary = "Deleted $files file(s) across $minutes minute(s)."
                                    statusMessage = summary
                                    onMessage(summary)
                                    onCamerasChanged()
                                }
                                .onFailure {
                                    deleting = false
                                    errorMessage = it.message ?: "Delete failed"
                                }
                        }
                    },
                ) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSnapshots = false }) { Text("Cancel") } },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Save snapshots after transaction pulls",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = form.enabled,
            onCheckedChange = { form = form.copy(enabled = it) },
        )
    }

    OutlinedTextField(
        value = form.serial,
        onValueChange = { form = form.copy(serial = it.take(32)) },
        label = { Text("Recorder serial") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = form.username,
        onValueChange = { form = form.copy(username = it.take(64)) },
        label = { Text("Recorder username") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )

    PasswordOutlinedField(
        value = form.password,
        onValueChange = { form = form.copy(password = it) },
        label = if (hasSavedSettings) "Recorder password (blank keeps saved)" else "Recorder password",
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = form.clockOffset,
        onValueChange = { raw ->
            val filtered = raw.filterIndexed { idx, ch -> ch.isDigit() || (ch == '-' && idx == 0) }.take(6)
            form = form.copy(clockOffset = filtered)
        },
        label = { Text("Clock offset (seconds)") },
        supportingText = { Text("Added to the gate's transaction time to match the recorder's clock.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Capture mode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
            space = 2.dp,
        ) {
            CAMERA_MODE_OPTIONS.forEachIndexed { idx, (modeValue, label) ->
                SegmentedButton(
                    selected = form.mode == modeValue,
                    onClick = { form = form.copy(mode = modeValue) },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = CAMERA_MODE_OPTIONS.size),
                    border = NO_SEGMENT_BORDER,
                    colors = SegmentedButtonDefaults.colors(
                        inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        activeBorderColor = Color.Transparent,
                        inactiveBorderColor = Color.Transparent,
                    ),
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }

    if (!isVideo) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Frames per transaction", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CAMERA_FRAME_OPTIONS.forEach { count ->
                    FilterChip(
                        selected = form.frames == count,
                        onClick = { form = form.copy(frames = count) },
                        label = { Text(count.toString()) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Frames after the minute", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CAMERA_FRAMES_AFTER_OPTIONS.forEach { count ->
                    FilterChip(
                        selected = form.framesAfter == count,
                        onClick = { form = form.copy(framesAfter = count) },
                        label = { Text(count.toString()) },
                    )
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Video tries", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CAMERA_CLIP_TRIES_OPTIONS.forEach { tries ->
                    FilterChip(
                        selected = form.clipTries == tries,
                        onClick = { form = form.copy(clipTries = tries) },
                        label = { Text(tries.toString()) },
                    )
                }
            }
            Text(
                text = "Saves 2-minute clips at full recorder quality from the start of each matching minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Transactions to capture", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CAMERA_EVENT_OPTIONS.forEach { (eventValue, label) ->
                FilterChip(
                    selected = form.events == eventValue,
                    onClick = { form = form.copy(events = eventValue) },
                    label = { Text(label) },
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Keep snapshots", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CAMERA_KEEP_DAYS_OPTIONS.forEach { (days, label) ->
                FilterChip(
                    selected = form.keepDays == days,
                    onClick = { form = form.copy(keepDays = days) },
                    label = { Text(label) },
                )
            }
        }
    }

    OutlinedTextField(
        value = form.backfillHours,
        onValueChange = { raw ->
            form = form.copy(backfillHours = raw.filter(Char::isDigit).take(3))
        },
        label = { Text("Backfill window (hours)") },
        supportingText = { Text("0–720 hours to look back when pulling transactions.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Channels (${form.channels.size}/$MAX_CAMERA_CHANNELS)",
            style = MaterialTheme.typography.titleSmall,
        )
        form.channels.forEachIndexed { idx, row ->
            val canRemove = form.channels.size > 1
            BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = row.channel,
                        onValueChange = { raw ->
                            val updated = form.channels.toMutableList()
                            updated[idx] = row.copy(channel = raw.filter(Char::isDigit).take(2))
                            form = form.copy(channels = updated)
                        },
                        label = { Text("Ch #") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                    )
                    OutlinedTextField(
                        value = row.name,
                        onValueChange = { raw ->
                            val updated = form.channels.toMutableList()
                            updated[idx] = row.copy(name = raw.take(40))
                            form = form.copy(channels = updated)
                        },
                        label = { Text("Camera name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = canRemove,
                        onClick = {
                            val updated = form.channels.toMutableList()
                            updated.removeAt(idx)
                            form = form.copy(channels = updated)
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Remove channel row ${idx + 1}",
                            tint = if (canRemove) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
                if (gateRelays.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = row.relays.isEmpty(),
                            onClick = {
                                val updated = form.channels.toMutableList()
                                updated[idx] = row.copy(relays = emptyList())
                                form = form.copy(channels = updated)
                            },
                            label = { Text("All relays") },
                        )
                        gateRelays.forEach { relay ->
                            val selected = relay in row.relays
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val nextRelays = if (selected) {
                                        row.relays.filterNot { it == relay }
                                    } else {
                                        (row.relays + relay).distinct().sorted()
                                    }
                                    val updated = form.channels.toMutableList()
                                    updated[idx] = row.copy(relays = nextRelays)
                                    form = form.copy(channels = updated)
                                },
                                label = { Text(relayDisplayName(relayLabels, relay)) },
                            )
                        }
                    }
                }
            }
        }
        if (form.channels.size < MAX_CAMERA_CHANNELS) {
            OutlinedButton(
                onClick = {
                    form = form.copy(channels = form.channels + CameraChannelFormRow())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Add channel")
            }
        }
    }

    Button(
        onClick = {
            if (shouldConfirmCameraRetention(cameras?.keepDays ?: 0, form.keepDays)) {
                confirmRetention = true
            } else {
                performSave()
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (saving) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Saving…")
        } else {
            Text("Save camera settings")
        }
    }

    FilledTonalButton(
        onClick = {
            errorMessage = null
            testResults = null
            statusMessage = "Connecting to the recorder… (up to a minute)"
            testing = true
            scope.launch {
                runCatching {
                    val res = cameraTestApi.testCameras(gatePhone)
                    if (!res.isSuccessful) error(parseSystemApiError(res.errorBody()?.string(), res.code(), "Test failed"))
                    res.body()?.channels.orEmpty()
                }
                    .onSuccess { results ->
                        testing = false
                        testResults = results
                        val okCount = results.count { !it.file.isNullOrBlank() }
                        statusMessage = "Live pictures from $okCount of ${results.size} channel(s)."
                    }
                    .onFailure {
                        testing = false
                        statusMessage = null
                        errorMessage = "Test failed: ${it.message ?: "Unable to connect"}"
                    }
            }
        },
        enabled = hasSavedSettings && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (testing) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Testing cameras…")
        } else {
            Text("Test cameras")
        }
    }

    OutlinedButton(
        onClick = { confirmDeleteCamera = true },
        enabled = hasSavedSettings && !busy,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Remove camera settings")
    }

    OutlinedButton(
        onClick = { confirmDeleteSnapshots = true },
        enabled = hasSavedSettings && !busy,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Delete all snapshots")
    }

    statusMessage?.takeIf { it.isNotBlank() }?.let { msg ->
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
        ErrorBanner(err)
    }

    testResults?.takeIf { it.isNotEmpty() }?.let { results ->
        BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Text("Test results", style = MaterialTheme.typography.titleSmall)
            results.forEach { ch ->
                val ok = !ch.file.isNullOrBlank()
                val label = ch.name.ifBlank { "Channel ${ch.channel}" }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        text = if (ok) "Ch ${ch.channel} OK" else "Ch ${ch.channel} Error",
                        tone = if (ok) PillTone.Success else PillTone.Error,
                    )
                    Text(
                        text = if (ok) "$label (${ch.file})" else "$label: ${ch.error?.ifBlank { "Failed" } ?: "Failed"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceAssociationRow(
    index: Int,
    state: MutableState<DeviceRangeDto>,
) {
    val row = state.value
    BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill("#${index + 1}", tone = if (row.name.isNotBlank()) PillTone.Primary else PillTone.Neutral)
            OutlinedTextField(
                value = row.name,
                onValueChange = { state.value = row.copy(name = it.take(12)) },
                label = { Text("Device name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = row.from,
                onValueChange = { state.value = row.copy(from = it.filter(Char::isDigit).take(5)) },
                label = { Text("From") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.to,
                onValueChange = { state.value = row.copy(to = it.filter(Char::isDigit).take(5)) },
                label = { Text("To") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    helpText: String? = null,
    content: @Composable () -> Unit,
) {
    CollapsibleSectionCard(
        title = title,
        expanded = expanded,
        onToggle = onToggle,
        tonal = true,
        helpText = helpText,
    ) {
        content()
    }
}

package com.doorking.gate.feature.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.doorking.gate.core.ui.components.CollapsibleSectionCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.ApiKeyInfoDto
import com.doorking.gate.core.network.CameraChannelDto
import com.doorking.gate.core.network.CameraSettingsDto
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
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private suspend inline fun <T> safeSystemCall(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SystemSettingsScreen(
    gatePhone: String,
    api: DoorkingApi,
    isAdmin: Boolean,
    meUsername: String,
    onMessage: (String) -> Unit = {},
    onDeletedGate: () -> Unit,
    onReloadGate: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<GateLoginDto?>(null) }
    var users by remember { mutableStateOf<List<GateUserDto>>(emptyList()) }
    var apiKeys by remember { mutableStateOf<List<ApiKeyInfoDto>>(emptyList()) }
    var webhooks by remember { mutableStateOf<List<WebhookInfoDto>>(emptyList()) }
    var audit by remember { mutableStateOf<List<LoginAuditEntryDto>>(emptyList()) }
    var reports by remember { mutableStateOf<List<SuspiciousReportDto>>(emptyList()) }
    var requests by remember { mutableStateOf<List<ResidentRequestDto>>(emptyList()) }
    var prefs by remember { mutableStateOf(GatePrefsDto()) }
    var cameras by remember { mutableStateOf<CameraSettingsDto?>(null) }
    val cameraChannels = remember { mutableStateListOf<CameraChannelDto>() }
    var createdKey by remember { mutableStateOf<Pair<String, String>?>(null) }
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

    // Form fields
    var dksUser by remember { mutableStateOf("") }
    var dksPassword by remember { mutableStateOf("") }
    var masterCode by remember { mutableStateOf("") }
    var shareUser by remember { mutableStateOf("") }
    var shareRole by remember { mutableStateOf("readonly") }
    var keyName by remember { mutableStateOf("") }
    var keyRole by remember { mutableStateOf("readonly") }
    var keyScopePreset by remember { mutableStateOf("full") }
    var whName by remember { mutableStateOf("") }
    var whUrl by remember { mutableStateOf("") }
    val deviceStates = remember { List(10) { mutableStateOf(DeviceRangeDto()) } }
    var hasLoadedOnce by remember(gatePhone) { mutableStateOf(false) }

    val pendingRequests = remember(requests) { requests.filter { it.status == "pending" } }
    val openReports = remember(reports) { reports.filter { it.status == "open" } }
    val gateRelays = remember(prefs.tracker) { openableRelayIndices(prefs.tracker) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun parseErr(raw: String?, code: Int, fallback: String): String {
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw) }.getOrNull()
            if (parsed != null && parsed.error.isNotBlank()) return parsed.error
        }
        return "$fallback (HTTP $code)"
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

                    val loadedLogin = loginDef.await()
                    login = loadedLogin
                    dksUser = loadedLogin?.dksUser.orEmpty()
                    users = usersDef.await()
                    apiKeys = keysDef.await()
                    webhooks = webhooksDef.await()
                    audit = auditDef.await()
                    reports = reportsDef.await()
                    requests = requestsDef.await()
                    val cams = camsDef.await()
                    cameras = cams
                    cameraChannels.clear()
                    if (cams != null) cameraChannels.addAll(cams.channels)
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
                                if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Remove failed"))
                            }
                                .onSuccess { onMessage("Removed ${target.username}."); load() }
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
                                if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Revoke failed"))
                            }
                                .onSuccess { onMessage("Revoked \"${target.name}\"."); load() }
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
                                if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Delete failed"))
                            }
                                .onSuccess { onMessage("Deleted webhook \"${target.name}\"."); load() }
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
                                if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Delete failed"))
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
                            error = null
                            scope.launch {
                                runCatching {
                                    val res = api.updateGateLogin(gatePhone, GateLoginUpdateRequest(dksUser.trim(), dksPassword, masterCode))
                                    if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Save failed"))
                                }
                                    .onSuccess { dksPassword = ""; masterCode = ""; onMessage("Gate login updated."); load() }
                                    .onFailure { error = it.message ?: "Save failed" }
                            }
                        },
                    ) { Text("Save gate login") }
                }
            }

            // 2. Gate Users & Share
            item(key = "users", contentType = "section_users") {
                CollapsibleSection("Gate Users (${users.size})", openUsers, { openUsers = !openUsers }) {
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
                                    IconButton(onClick = { removeUserTarget = u }) {
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
                                        if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Share failed"))
                                    }
                                        .onSuccess { shareUser = ""; onMessage("Gate shared."); load() }
                                        .onFailure { error = it.message ?: "Share failed" }
                                }
                            },
                        ) { Text("Share") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("readonly", "editor", "admin").forEach { r ->
                            FilterChip(selected = shareRole == r, onClick = { shareRole = r }, label = { Text(r.replaceFirstChar(Char::uppercase)) })
                        }
                    }
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
                                    runCatching { api.updatePrefs(gatePhone, body) }
                                        .onSuccess { load(); onReloadGate() }
                                        .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                                }
                            },
                        )
                    }

                    val portalCfg = prefs.residentPortal
                    val portalEnabled = portalCfg?.enabled == true
                    val buzzEnabled = portalCfg?.buzzEnabled == true
                    val activeBuzzRelays = (portalCfg?.buzzRelays ?: listOf(0)).ifEmpty { listOf(0) }

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
                                            kotlinx.serialization.json.JsonArray(nextBuzzRelays.distinct().sorted().map { JsonPrimitive(it) }),
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
                            runCatching { api.updatePrefs(gatePhone, body) }
                                .onSuccess { load(); onReloadGate() }
                                .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
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
                                                .onSuccess { onMessage("Request rejected."); load() }
                                                .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                                        }
                                    },
                                ) { Text("Reject") }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch {
                                            runCatching { api.reviewResidentRequest(gatePhone, req.id, ReviewResidentRequestBody("approved", apply = true)) }
                                                .onSuccess { onMessage("Request approved."); load(); onReloadGate() }
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
                                            .onSuccess { onMessage("Report resolved."); load() }
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
                    if (createdKey != null) {
                        BubbleCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text("Key \"${createdKey!!.first}\" created — copy now:", style = MaterialTheme.typography.titleSmall)
                            Text(createdKey!!.second, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.setPrimaryClip(ClipData.newPlainText("DoorKing API Key", createdKey!!.second))
                                    onMessage("API key copied.")
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                                    Text("Copy", modifier = Modifier.padding(start = 4.dp))
                                }
                                TextButton(onClick = { createdKey = null }) { Text("Done") }
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
                                    onClick = { revokeKeyTarget = k },
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
                                            "mcpRead" -> listOf(
                                                "gate:read", "directory:read", "transactions:read", "insights:read",
                                                "history:read", "settings:read", "relays:read", "schedules:read", "cameras:read",
                                            )
                                            "mcpStaging" -> listOf(
                                                "gate:read", "directory:read", "directory:write", "transactions:read",
                                                "insights:read", "history:read", "settings:read", "settings:write",
                                                "relays:read", "schedules:read", "cameras:read", "gate:pull",
                                            )
                                            else -> null
                                        }
                                        val res = api.createApiKey(gatePhone, CreateApiKeyRequest(keyName.trim(), keyRole, 0, selectedScopes))
                                        if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Create failed"))
                                        res.body()!!
                                    }
                                        .onSuccess { created ->
                                            createdKey = created.apiKey.name to created.key
                                            keyName = ""
                                            keyScopePreset = "full"
                                            load()
                                        }
                                        .onFailure { error = it.message ?: "Create failed" }
                                }
                            },
                        ) { Text("Create") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("readonly", "editor", "admin").forEach { r ->
                            FilterChip(selected = keyRole == r, onClick = { keyRole = r }, label = { Text(r.replaceFirstChar(Char::uppercase)) })
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "full" to "Full role",
                            "mcpRead" to "MCP Read-Only",
                            "mcpStaging" to "MCP Staging",
                        ).forEach { (presetKey, label) ->
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
            }

            // 6. Webhooks
            item(key = "webhooks", contentType = "section_webhooks") {
                CollapsibleSection("Webhooks (${webhooks.size})", openWebhooks, { openWebhooks = !openWebhooks }) {
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
                                                .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                                        }
                                    }) { Text("Test") }
                                    IconButton(onClick = { deleteWebhookTarget = w }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete webhook ${w.name}", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = whName, onValueChange = { whName = it }, label = { Text("Webhook name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                                    val res = api.createWebhook(gatePhone, CreateWebhookRequest(whName.trim(), whUrl.trim(), listOf("send.completed", "relay.changed")))
                                    if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Add webhook failed"))
                                }
                                    .onSuccess { whName = ""; whUrl = ""; onMessage("Webhook added."); load() }
                                    .onFailure { error = it.message ?: "Add failed" }
                            }
                        },
                    ) { Text("Add webhook") }
                }
            }
            // 6b. Camera Channels ↔ Relay Mapping
            val camCfg = cameras
            if (camCfg != null && cameraChannels.isNotEmpty()) {
                item(key = "camera_channels", contentType = "section_cameras") {
                    CollapsibleSection(
                        title = "Camera Channels (${cameraChannels.size})",
                        expanded = openCameras,
                        onToggle = { openCameras = !openCameras },
                        helpText = "Map each camera channel to specific gate relays so snapshots and clips only capture cameras watching the relay that fired. Leave none selected to record on all relays.",
                    ) {
                        cameraChannels.forEachIndexed { idx, ch ->
                            BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StatusPill("Ch ${ch.channel}", tone = PillTone.Primary)
                                    Text(
                                        text = ch.name.ifBlank { "Channel ${ch.channel}" },
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    FilterChip(
                                        selected = ch.relays.isEmpty(),
                                        onClick = { cameraChannels[idx] = ch.copy(relays = emptyList()) },
                                        label = { Text("All relays") },
                                    )
                                    gateRelays.forEach { relay ->
                                        val selected = relay in ch.relays
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                val next = if (selected) {
                                                    ch.relays.filterNot { it == relay }
                                                } else {
                                                    (ch.relays + relay).distinct().sorted()
                                                }
                                                cameraChannels[idx] = ch.copy(relays = next)
                                            },
                                            label = { Text(relayDisplayName(prefs.relayLabels, relay)) },
                                        )
                                    }
                                }
                            }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                error = null
                                scope.launch {
                                    val channelsJson = JsonArray(
                                        cameraChannels.map { ch ->
                                            buildJsonObject {
                                                put("channel", JsonPrimitive(ch.channel))
                                                put("name", JsonPrimitive(ch.name))
                                                put("relays", JsonArray(ch.relays.distinct().sorted().map { JsonPrimitive(it) }))
                                            }
                                        },
                                    )
                                    val body = buildJsonObject {
                                        put("enabled", JsonPrimitive(camCfg.enabled))
                                        put("serial", JsonPrimitive(camCfg.serial))
                                        put("username", JsonPrimitive(camCfg.username))
                                        put("mode", JsonPrimitive(camCfg.mode))
                                        put("clockOffset", JsonPrimitive(camCfg.clockOffset))
                                        put("frames", JsonPrimitive(camCfg.frames))
                                        put("framesAfter", JsonPrimitive(camCfg.framesAfter))
                                        put("clipTries", JsonPrimitive(camCfg.clipTries))
                                        put("events", JsonPrimitive(camCfg.events))
                                        put("backfillHours", JsonPrimitive(camCfg.backfillHours))
                                        put("keepDays", JsonPrimitive(camCfg.keepDays))
                                        put("channels", channelsJson)
                                    }
                                    runCatching {
                                        val res = api.saveCameras(gatePhone, body)
                                        if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Save failed"))
                                    }
                                        .onSuccess { onMessage("Camera relay mapping saved."); load(); onReloadGate() }
                                        .onFailure { error = it.message ?: "Save failed" }
                                }
                            },
                        ) { Text("Save camera relays") }
                    }
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
                                if (!res.isSuccessful) error(parseErr(res.errorBody()?.string(), res.code(), "Save failed"))
                            }
                                .onSuccess { onMessage("Device association saved."); onReloadGate() }
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

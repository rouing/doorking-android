package com.doorking.gate.feature.admin

import android.Manifest
import android.app.StatusBarManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.auth.BiometricUnlockManager
import com.doorking.gate.core.auth.PasskeyAuthenticator
import com.doorking.gate.core.auth.PasskeyResult
import com.doorking.gate.core.auth.SessionStorage
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.network.AiClientSessionDto
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.ChangePasswordRequest
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.FcmTokenInfoDto
import com.doorking.gate.core.network.MeProfileDto
import com.doorking.gate.core.network.PasskeyInfoDto
import com.doorking.gate.core.network.PasskeyRegistrationRequest
import com.doorking.gate.core.network.RegisterFcmRequest
import com.doorking.gate.core.network.WebPushSubDto
import com.doorking.gate.core.push.FirebaseHelper
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PasswordOutlinedField
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.components.ThemeModeSelector
import com.doorking.gate.feature.auth.validateNewPassword
import com.doorking.gate.tile.GateRelayTileConfig
import com.doorking.gate.tile.GateRelayTileManager
import com.doorking.gate.widget.GateWidgetUpdater
import com.doorking.gate.widget.RelayWidgetPinHelper
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountSettingsScreen(
    api: DoorkingApi,
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    residentMode: Boolean = false,
    passkeyAuthenticator: PasskeyAuthenticator? = null,
    sessionStorage: SessionStorage? = null,
    onSwitchToStaff: (() -> Unit)? = null,
    onProfileUpdated: (() -> Unit)? = null,
    onMessage: (String) -> Unit = {},
    onSignOut: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<MeProfileDto?>(null) }
    var passkeys by remember { mutableStateOf<List<PasskeyInfoDto>>(emptyList()) }
    var fcmServerEnabled by remember { mutableStateOf(false) }
    var fcmTokens by remember { mutableStateOf<List<FcmTokenInfoDto>>(emptyList()) }
    var webPushSubs by remember { mutableStateOf<List<WebPushSubDto>>(emptyList()) }
    var aiClients by remember { mutableStateOf<List<AiClientSessionDto>>(emptyList()) }
    var aiBusyId by remember { mutableStateOf<String?>(null) }
    var fcmBusy by remember { mutableStateOf(false) }
    var notificationPermGranted by remember { mutableStateOf(false) }
    var deletePasskeyTarget by remember { mutableStateOf<PasskeyInfoDto?>(null) }
    var showAddPasskeyDialog by remember { mutableStateOf(false) }
    var addPasskeyName by remember { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android device" }) }
    var addPasskeyPassword by remember { mutableStateOf("") }
    var addingPasskey by remember { mutableStateOf(false) }

    var email by remember { mutableStateOf("") }
    var emailPassword by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var notifyAccess by remember { mutableStateOf(true) }
    var notifyAlerts by remember { mutableStateOf(true) }
    var currentPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    var showPasswords by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun parseApiError(raw: String?, code: Int, fallback: String): String {
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw) }.getOrNull()
            if (parsed != null && parsed.error.isNotBlank()) return parsed.error
        }
        return "$fallback (HTTP $code)"
    }

    fun syncTilesFromProfile(me: MeProfileDto) {
        val visibleTiles = buildList {
            me.portalRelays.forEach { group ->
                val unitCode = me.linkedUnits.firstOrNull { it.gatePhone == group.phone }?.directoryCode
                group.relays.filter { !it.hidden }.forEach { opt ->
                    add(
                        GateRelayTileConfig(
                            gatePhone = group.phone,
                            gateName = group.siteName.ifBlank { group.phone },
                            relay = opt.relay,
                            relayName = opt.name.ifBlank { "Relay ${opt.relay}" },
                            unitCode = unitCode,
                        ),
                    )
                }
            }
        }
        GateRelayTileManager.syncVisibleRelays(context, visibleTiles)
    }

    fun load(initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        notificationPermGranted = FirebaseHelper.hasNotificationPermission(context)
        scope.launch {
            runCatching {
                val me = api.getMe()
                val pks = runCatching { api.listPasskeys().passkeys }.getOrDefault(emptyList())
                val fcm = runCatching { api.getFcmTokens() }.getOrNull()
                val webPush = runCatching { api.getWebPushSubscriptions().subscriptions }.getOrDefault(emptyList())
                val ai = runCatching { api.listAiClients().clients }.getOrDefault(emptyList())
                listOf(me, pks, fcm, webPush, ai)
            }
                .onSuccess { results ->
                    val me = results[0] as MeProfileDto
                    @Suppress("UNCHECKED_CAST")
                    val pks = results[1] as List<PasskeyInfoDto>
                    val fcm = results[2] as com.doorking.gate.core.network.FcmTokensReply?
                    @Suppress("UNCHECKED_CAST")
                    val webPush = results[3] as List<WebPushSubDto>
                    @Suppress("UNCHECKED_CAST")
                    val ai = results[4] as List<AiClientSessionDto>
                    profile = me
                    syncTilesFromProfile(me)
                    passkeys = pks
                    if (fcm != null) {
                        fcmServerEnabled = fcm.enabled
                        fcmTokens = fcm.tokens
                    }
                    webPushSubs = webPush
                    aiClients = ai
                    email = me.pendingEmail.ifBlank { me.email }
                    phone = me.phone
                    notifyAccess = me.notifyAccess
                    notifyAlerts = me.notifyAlerts
                    loading = false
                    refreshing = false
                }
                .onFailure {
                    error = ApiClientFactory.parseErrorMessage(it)
                    loading = false
                    refreshing = false
                }
        }
    }

    fun registerThisDeviceFcm() {
        fcmBusy = true
        error = null
        scope.launch {
            runCatching {
                val token = FirebaseHelper.fetchFcmToken(context)
                    ?: error("Firebase push token is unavailable on this build/device.")
                val res = api.registerFcmToken(
                    RegisterFcmRequest(token = token, label = FirebaseHelper.defaultDeviceLabel()),
                )
                if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Could not enable push"))
                res.body()
            }
                .onSuccess { reply ->
                    fcmBusy = false
                    if (reply != null) {
                        fcmServerEnabled = reply.enabled
                        fcmTokens = reply.tokens
                    }
                    onMessage("Push notifications enabled on this device.")
                }
                .onFailure { err ->
                    fcmBusy = false
                    error = err.message ?: "Could not enable push"
                }
        }
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermGranted = granted || FirebaseHelper.hasNotificationPermission(context)
        if (notificationPermGranted) {
            registerThisDeviceFcm()
        } else {
            onMessage("Notification permission denied. Enable it in Android App Settings.")
        }
    }

    fun persistToggle(key: String, next: Boolean, rollback: () -> Unit) {
        scope.launch {
            val body = buildJsonObject { put(key, JsonPrimitive(next)) }
            runCatching {
                val res = api.updateMe(body)
                if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Could not save preference"))
            }
                .onSuccess { onMessage("Notification preference saved.") }
                .onFailure { err ->
                    rollback()
                    error = err.message ?: "Could not save preference"
                }
        }
    }

    LaunchedEffect(Unit) { load(initial = true) }

    if (showAddPasskeyDialog && passkeyAuthenticator != null) {
        AlertDialog(
            onDismissRequest = { if (!addingPasskey) showAddPasskeyDialog = false },
            title = { Text("Add passkey on this device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Save a biometric passkey in Android Credential Manager to sign in and confirm sensitive actions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = addPasskeyName,
                        onValueChange = { addPasskeyName = it.take(40) },
                        label = { Text("Passkey label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordOutlinedField(
                        value = addPasskeyPassword,
                        onValueChange = { addPasskeyPassword = it },
                        label = "Current password (if required)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !addingPasskey && addPasskeyName.isNotBlank(),
                    onClick = {
                        addingPasskey = true
                        error = null
                        scope.launch {
                            runCatching {
                                val optBody = buildJsonObject {
                                    if (addPasskeyPassword.isNotBlank()) put("password", JsonPrimitive(addPasskeyPassword))
                                }
                                val optRes = api.getPasskeyRegistrationOptions(optBody)
                                if (!optRes.isSuccessful) {
                                    error(parseApiError(optRes.errorBody()?.string(), optRes.code(), "Could not start passkey setup"))
                                }
                                val optionsJson = optRes.body()?.toString() ?: error("Empty passkey options")
                                when (val created = passkeyAuthenticator.register(context, optionsJson)) {
                                    is PasskeyResult.Cancelled -> return@runCatching false
                                    is PasskeyResult.Error -> error(created.message)
                                    is PasskeyResult.Success -> {
                                        val payload = ApiClientFactory.json.parseToJsonElement(created.assertionJson)
                                        val saveRes = api.addPasskey(
                                            PasskeyRegistrationRequest(name = addPasskeyName.trim(), response = payload),
                                        )
                                        if (!saveRes.isSuccessful) {
                                            error(parseApiError(saveRes.errorBody()?.string(), saveRes.code(), "Could not save passkey"))
                                        }
                                        true
                                    }
                                }
                            }
                                .onSuccess { saved ->
                                    addingPasskey = false
                                    if (saved) {
                                        addPasskeyPassword = ""
                                        showAddPasskeyDialog = false
                                        onMessage("Passkey added.")
                                        load()
                                    }
                                }
                                .onFailure { err ->
                                    addingPasskey = false
                                    error = err.message ?: "Could not add passkey"
                                }
                        }
                    },
                ) { Text(if (addingPasskey) "Creating…" else "Continue") }
            },
            dismissButton = {
                TextButton(enabled = !addingPasskey, onClick = { showAddPasskeyDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (deletePasskeyTarget != null) {
        val target = deletePasskeyTarget!!
        AlertDialog(
            onDismissRequest = { deletePasskeyTarget = null },
            title = { Text("Remove passkey \"${target.name}\"?") },
            text = {
                Text(
                    if (passkeys.size <= 1) {
                        "This is your only passkey. Removing it will prevent passkey step-up confirmations until you register another."
                    } else {
                        "You will no longer be able to sign in or confirm actions with this passkey."
                    },
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        deletePasskeyTarget = null
                        scope.launch {
                            runCatching {
                                val res = api.deletePasskey(target.id)
                                if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Could not remove passkey"))
                            }
                                .onSuccess { onMessage("Passkey removed."); load() }
                                .onFailure { error = it.message ?: "Could not remove passkey" }
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deletePasskeyTarget = null }) { Text("Cancel") } },
        )
    }

    if (loading && profile == null) {
        FullScreenLoading("Loading account…")
        return
    }

    val hasLinkedUnits = residentMode || profile?.linkedUnits?.isNotEmpty() == true
    val emailTrimmed = email.trim().lowercase()
    val emailChanged = emailTrimmed != profile?.email.orEmpty().lowercase() &&
        emailTrimmed != profile?.pendingEmail.orEmpty().lowercase()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { load(initial = false) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) item(key = "err") { ErrorBanner(error!!, onRetry = { load(initial = false) }) }

            item(key = "appearance") {
                SectionCard(title = "Appearance", tonal = true) {
                    Text(
                        text = "Signed in as ${profile?.username.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ThemeModeSelector(mode = themeMode, onSelect = onSelectTheme)
                }
            }

            val portalRelayGroups = profile?.portalRelays.orEmpty()
            if (portalRelayGroups.isNotEmpty()) {
                item(key = "portal_relays") {
                    val canPinWidget = remember(context) { RelayWidgetPinHelper.canPin(context) }
                    val visibleTileSlotByKey = remember(portalRelayGroups) {
                        val map = linkedMapOf<Pair<String, Int>, Int>()
                        var nextSlot = 0
                        portalRelayGroups.forEach { g ->
                            g.relays.filter { !it.hidden }.forEach { r ->
                                if (nextSlot < GateRelayTileManager.MAX_SLOTS) {
                                    map[g.phone to r.relay] = nextSlot++
                                }
                            }
                        }
                        map
                    }
                    SectionCard(title = "Visible gate relays", tonal = true) {
                        Text(
                            text = "Choose which gate open buttons appear in the app and Android Auto. Pin any relay as a 1×1 home-screen shortcut or add it to Quick Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        portalRelayGroups.forEach { group ->
                            if (portalRelayGroups.size > 1) {
                                Text(
                                    text = group.siteName.ifBlank { group.phone },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            group.relays.forEach { opt ->
                                val visible = !opt.hidden
                                val relayLabel = opt.name.ifBlank { "Relay ${opt.relay}" }
                                val tileSlot = if (visible) visibleTileSlotByKey[group.phone to opt.relay] else null
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = relayLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (tileSlot != null) {
                                        IconButton(
                                            onClick = {
                                                profile?.let(::syncTilesFromProfile)
                                                val started = GateRelayTileManager.requestAddTile(
                                                    context = context,
                                                    slotIndex = tileSlot,
                                                ) { resultCode ->
                                                    when (resultCode) {
                                                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                                            onMessage("Added $relayLabel to Quick Settings.")
                                                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                                            onMessage("$relayLabel is already in Quick Settings.")
                                                    }
                                                }
                                                if (!started) {
                                                    onMessage("Add the $relayLabel tile from Android Quick Settings edit mode.")
                                                }
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DashboardCustomize,
                                                contentDescription = "Add $relayLabel to Quick Settings",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    if (canPinWidget) {
                                        IconButton(
                                            onClick = {
                                                val unitCode = profile?.linkedUnits
                                                    ?.firstOrNull { it.gatePhone == group.phone }
                                                    ?.directoryCode
                                                val ok = RelayWidgetPinHelper.requestPin(
                                                    context = context,
                                                    gatePhone = group.phone,
                                                    gateName = group.siteName.ifBlank { group.phone },
                                                    relay = opt.relay,
                                                    relayName = relayLabel,
                                                    unitCode = unitCode,
                                                )
                                                if (!ok) onMessage("Add the 1×1 Relay widget from your home screen widget picker.")
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PushPin,
                                                contentDescription = "Pin $relayLabel 1×1 widget",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = visible,
                                        onCheckedChange = { wantVisible ->
                                            val currentHidden = profile?.hiddenRelays.orEmpty().toMutableMap()
                                            val gateHidden = currentHidden[group.phone].orEmpty().toMutableSet()
                                            if (wantVisible) gateHidden.remove(opt.relay) else gateHidden.add(opt.relay)
                                            if (gateHidden.isEmpty()) currentHidden.remove(group.phone)
                                            else currentHidden[group.phone] = gateHidden.sorted()
                                            val nextGroups = portalRelayGroups.map { g ->
                                                if (g.phone != group.phone) g
                                                else g.copy(relays = g.relays.map { r -> if (r.relay == opt.relay) r.copy(hidden = !wantVisible) else r })
                                            }
                                            val updatedProfile = profile?.copy(hiddenRelays = currentHidden, portalRelays = nextGroups)
                                            profile = updatedProfile
                                            updatedProfile?.let(::syncTilesFromProfile)
                                            scope.launch {
                                                val body = buildJsonObject {
                                                    put(
                                                        "hiddenRelays",
                                                        buildJsonObject {
                                                            currentHidden.forEach { (phoneKey, list) ->
                                                                put(
                                                                    phoneKey,
                                                                    kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) }),
                                                                )
                                                            }
                                                        },
                                                    )
                                                }
                                                runCatching {
                                                    val res = api.updateMe(body)
                                                    if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Could not save relay visibility"))
                                                }
                                                    .onSuccess {
                                                        onProfileUpdated?.invoke()
                                                        GateWidgetUpdater.enqueueSync(context)
                                                    }
                                                    .onFailure { err ->
                                                        error = err.message ?: "Could not save relay visibility"
                                                        load(initial = false)
                                                    }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "email") {
                SectionCard(
                    title = "Email",
                    tonal = true,
                    trailing = {
                        when {
                            !profile?.pendingEmail.isNullOrBlank() -> StatusPill("Pending", tone = PillTone.Warning)
                            !profile?.email.isNullOrBlank() -> StatusPill("Confirmed", tone = PillTone.Success)
                            else -> null
                        }
                    },
                ) {
                    Text(
                        text = "Used for password resets, security alerts, and notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!profile?.email.isNullOrBlank()) {
                        Text("Confirmed: ${profile!!.email}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!profile?.pendingEmail.isNullOrBlank()) {
                        Text(
                            "Waiting for confirmation: ${profile!!.pendingEmail}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val res = api.resendEmailVerification()
                                        if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Could not resend link"))
                                    }
                                        .onSuccess { onMessage("Confirmation link sent again.") }
                                        .onFailure { error = it.message ?: "Could not resend link" }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.MarkEmailRead, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Send confirmation link again")
                        }
                    }
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (emailChanged) {
                        PasswordOutlinedField(
                            value = emailPassword,
                            onValueChange = { emailPassword = it },
                            label = "Current password to confirm email change",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        enabled = emailChanged,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            error = null
                            scope.launch {
                                val body = buildJsonObject {
                                    put("email", JsonPrimitive(emailTrimmed))
                                    if (emailPassword.isNotBlank()) put("password", JsonPrimitive(emailPassword))
                                }
                                runCatching {
                                    val res = api.updateMe(body)
                                    if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Email save failed"))
                                    res.body()
                                }
                                    .onSuccess { updated ->
                                        emailPassword = ""
                                        val msg = updated?.warning?.takeIf { it.isNotBlank() }
                                            ?: if (emailTrimmed.isBlank()) "Email removed." else "Saved. Check $emailTrimmed to confirm."
                                        onMessage(msg)
                                        load()
                                        onProfileUpdated?.invoke()
                                    }
                                    .onFailure { error = it.message ?: "Email save failed" }
                            }
                        },
                    ) { Text("Save email") }
                }
            }

            item(key = "phone") {
                SectionCard(title = if (hasLinkedUnits) "Call-box phone number" else "Phone", tonal = true) {
                    Text(
                        text = if (hasLinkedUnits) {
                            "Linked to your gate directory listing. Updating your 10-digit US/Canada number here updates your unit's call-box number and syncs to the gate on the next send."
                        } else {
                            "Optional contact number visible to gate administrators."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Phone number") },
                            placeholder = { Text("(214) 555-0100") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = {
                            error = null
                            scope.launch {
                                val body = buildJsonObject { put("phone", JsonPrimitive(phone.trim())) }
                                runCatching {
                                    val res = api.updateMe(body)
                                    if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Save failed"))
                                    res.body()
                                }
                                    .onSuccess { updated ->
                                        val units = updated?.gatePhoneSync?.updatedUnits.orEmpty()
                                        val msg = if (units.isNotEmpty()) {
                                            "Saved & updated call-box unit ${units.joinToString(", ") { "#$it" }}."
                                        } else {
                                            "Phone saved."
                                        }
                                        onMessage(msg)
                                        load()
                                        onProfileUpdated?.invoke()
                                    }
                                    .onFailure { error = it.message ?: "Save failed" }
                            }
                        }) { Text("Save") }
                    }
                }
            }

            item(key = "notifications") {
                SectionCard(title = "Email notifications", tonal = true) {
                    Text(
                        text = "Sent to your confirmed email. Security emails (password or passkey changes) are always sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = notifyAccess,
                                role = Role.Switch,
                                onValueChange = { next ->
                                    val prev = notifyAccess
                                    notifyAccess = next
                                    persistToggle("notifyAccess", next) { notifyAccess = prev }
                                },
                            )
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Gate access & role changes",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = notifyAccess, onCheckedChange = null)
                    }
                    if (!residentMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = notifyAlerts,
                                    role = Role.Switch,
                                    onValueChange = { next ->
                                        val prev = notifyAlerts
                                        notifyAlerts = next
                                        persistToggle("notifyAlerts", next) { notifyAlerts = prev }
                                    },
                                )
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Gate alert notifications",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = notifyAlerts, onCheckedChange = null)
                        }
                    }
                }
            }

            item(key = "push_fcm") {
                val firebaseReady = FirebaseHelper.isAvailable(context)
                val totalDevices = fcmTokens.size + webPushSubs.size
                SectionCard(
                    title = "Push notifications",
                    tonal = true,
                    trailing = {
                        when {
                            totalDevices > 0 ->
                                StatusPill("$totalDevices device${if (totalDevices == 1) "" else "s"}", tone = PillTone.Success)
                            !firebaseReady && !fcmServerEnabled -> StatusPill("Off", tone = PillTone.Neutral)
                            !fcmServerEnabled -> StatusPill("Server off", tone = PillTone.Warning)
                            else -> StatusPill("Off", tone = PillTone.Neutral)
                        }
                    },
                ) {
                    Text(
                        text = when {
                            !firebaseReady && totalDevices == 0 -> "This build does not include Firebase Messaging configuration."
                            !fcmServerEnabled && totalDevices == 0 -> "Your server does not have Android push configured yet."
                            else -> "Receive instant alerts across your Android app, browsers, and desktop PWAs."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (firebaseReady && fcmServerEnabled) {
                            FilledTonalButton(
                                enabled = !fcmBusy,
                                onClick = {
                                    if (FirebaseHelper.hasNotificationPermission(context)) {
                                        registerThisDeviceFcm()
                                    } else {
                                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.NotificationsActive, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (notificationPermGranted && fcmTokens.isNotEmpty()) "Re-sync device" else "Enable on device")
                            }
                        }
                        if (totalDevices > 0) {
                            Button(
                                enabled = !fcmBusy,
                                onClick = {
                                    fcmBusy = true
                                    error = null
                                    scope.launch {
                                        runCatching {
                                            val res = api.sendTestAllPush()
                                            if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Test push failed"))
                                            res.body()
                                        }
                                            .onSuccess { reply ->
                                                fcmBusy = false
                                                val sent = reply?.sent ?: 0
                                                val failed = reply?.failed ?: 0
                                                onMessage(
                                                    if (sent > 0) "Test notification sent to $sent device${if (sent == 1) "" else "s"}."
                                                    else "Test push did not deliver ($failed failed).",
                                                )
                                            }
                                            .onFailure { err ->
                                                fcmBusy = false
                                                error = err.message ?: "Test push failed"
                                            }
                                    }
                                },
                            ) { Text("Test") }
                        }
                    }
                    fcmTokens.forEach { dev ->
                        BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(dev.label, style = MaterialTheme.typography.titleSmall)
                                        StatusPill("Android", tone = PillTone.Primary)
                                    }
                                    Text(
                                        dev.lastUsedAt?.let { "Last delivered ${it.take(16).replace('T', ' ')}" } ?: "Registered",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    enabled = !fcmBusy,
                                    onClick = {
                                        fcmBusy = true
                                        scope.launch {
                                            runCatching {
                                                val res = api.deleteFcmToken(dev.id)
                                                if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Could not remove device"))
                                                res.body()
                                            }
                                                .onSuccess { reply ->
                                                    fcmBusy = false
                                                    if (reply != null) fcmTokens = reply.tokens
                                                    onMessage("Removed ${dev.label} from push notifications.")
                                                }
                                                .onFailure { err ->
                                                    fcmBusy = false
                                                    error = err.message ?: "Could not remove device"
                                                }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Remove ${dev.label}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    webPushSubs.forEach { sub ->
                        BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(sub.label, style = MaterialTheme.typography.titleSmall)
                                        StatusPill("Browser", tone = PillTone.Neutral)
                                    }
                                    Text(
                                        sub.lastUsedAt?.let { "Last delivered ${it.take(16).replace('T', ' ')}" } ?: "Registered",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    enabled = !fcmBusy,
                                    onClick = {
                                        fcmBusy = true
                                        scope.launch {
                                            runCatching {
                                                val res = api.deleteWebPushSubscription(sub.id)
                                                if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Could not remove browser"))
                                                res.body()
                                            }
                                                .onSuccess { reply ->
                                                    fcmBusy = false
                                                    if (reply != null) webPushSubs = reply.subscriptions
                                                    onMessage("Removed ${sub.label} from push notifications.")
                                                }
                                                .onFailure { err ->
                                                    fcmBusy = false
                                                    error = err.message ?: "Could not remove browser"
                                                }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Remove ${sub.label}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (sessionStorage != null) {
                item(key = "biometric_lock") {
                    val stored by sessionStorage.state.collectAsStateWithLifecycle()
                    val biometricAvailable = remember(context) { BiometricUnlockManager.isAvailable(context) }
                    SectionCard(
                        title = "Fingerprint unlock",
                        tonal = true,
                        trailing = {
                            StatusPill(
                                text = if (stored.biometricLockEnabled) "On" else "Off",
                                tone = if (stored.biometricLockEnabled) PillTone.Success else PillTone.Neutral,
                            )
                        },
                    ) {
                        Text(
                            text = if (biometricAvailable) {
                                "Prompt immediately for your fingerprint or device screen lock when opening the app. Works with any sign-in method (Google SSO, password, or passkey)."
                            } else {
                                "Set up a fingerprint or screen lock in Android Settings to enable biometric unlock."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = stored.biometricLockEnabled,
                                    enabled = biometricAvailable,
                                    role = Role.Switch,
                                    onValueChange = { wantEnable ->
                                        if (!wantEnable) {
                                            scope.launch {
                                                sessionStorage.setBiometricLockEnabled(false)
                                                onMessage("Fingerprint unlock disabled.")
                                            }
                                        } else {
                                            val activity = BiometricUnlockManager.findFragmentActivity(context)
                                            if (activity == null) {
                                                error = "Could not start biometric prompt"
                                            } else {
                                                BiometricUnlockManager.authenticate(
                                                    activity = activity,
                                                    title = "Enable fingerprint unlock",
                                                    subtitle = "Confirm your fingerprint to lock Doorking Gate",
                                                    onSuccess = {
                                                        scope.launch {
                                                            sessionStorage.setBiometricLockEnabled(true)
                                                            onMessage("Fingerprint unlock enabled.")
                                                        }
                                                    },
                                                    onError = { msg -> error = msg },
                                                )
                                            }
                                        }
                                    },
                                )
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Require fingerprint to open app",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = stored.biometricLockEnabled,
                                enabled = biometricAvailable,
                                onCheckedChange = null,
                            )
                        }
                    }
                }
            }

            item(key = "ai_clients") {
                SectionCard(
                    title = "Connected AI Clients",
                    tonal = true,
                    trailing = if (aiClients.isNotEmpty()) {
                        { StatusPill("${aiClients.size}", tone = PillTone.Primary) }
                    } else {
                        null
                    },
                ) {
                    Text(
                        text = "AI assistants (such as Gemini or Claude) authorized via MCP OAuth. Revoking immediately ends that client's access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (aiClients.isEmpty()) {
                        Text(
                            text = "No connected AI clients.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        aiClients.forEach { client ->
                            BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(client.clientName.ifBlank { "AI Client" }, style = MaterialTheme.typography.titleSmall)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            client.relaySummary.forEach { g ->
                                                g.relays.forEach { r ->
                                                    val lbl = if (client.relaySummary.size > 1) "${g.siteName}: ${r.name}" else r.name
                                                    StatusPill(lbl, tone = PillTone.Primary)
                                                }
                                            }
                                            if (client.allowActivity) StatusPill("Recent activity", tone = PillTone.Neutral)
                                            if ("doorking:stage" in client.scopes) StatusPill("Staging & relays", tone = PillTone.Warning)
                                            if (client.relaySummary.isEmpty() && "doorking:stage" !in client.scopes) {
                                                StatusPill("Read-only", tone = PillTone.Neutral)
                                            }
                                        }
                                        Text(
                                            text = "Connected ${client.createdAt.take(16).replace('T', ' ')}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        enabled = aiBusyId == null,
                                        onClick = {
                                            aiBusyId = client.id
                                            error = null
                                            scope.launch {
                                                runCatching {
                                                    val res = api.revokeAiClient(client.id)
                                                    if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Could not revoke AI client"))
                                                    res.body()
                                                }
                                                    .onSuccess { reply ->
                                                        aiBusyId = null
                                                        if (reply != null) aiClients = reply.clients
                                                        onMessage("Revoked ${client.clientName}.")
                                                    }
                                                    .onFailure { err ->
                                                        aiBusyId = null
                                                        error = err.message ?: "Could not revoke AI client"
                                                    }
                                            }
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Revoke ${client.clientName}",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "passkeys") {
                SectionCard(
                    title = "Passkeys",
                    tonal = true,
                    trailing = { StatusPill("${passkeys.size}", tone = PillTone.Primary) },
                ) {
                    if (passkeys.isEmpty()) {
                        Text(
                            "No passkeys registered on this account yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        passkeys.forEach { pk ->
                            BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Rounded.Fingerprint,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Column {
                                            Text(pk.name, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "${pk.site}${if (pk.synced) " · Synced" else ""}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(onClick = { deletePasskeyTarget = pk }) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Remove passkey ${pk.name}",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (passkeyAuthenticator != null) {
                        FilledTonalButton(
                            onClick = { showAddPasskeyDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Add passkey on this device")
                        }
                    }
                }
            }

            item(key = "password") {
                SectionCard(title = "Change Password", tonal = true) {
                    PasswordOutlinedField(
                        value = currentPw,
                        onValueChange = { currentPw = it },
                        label = "Current password",
                        visible = showPasswords,
                        onToggleVisibility = { showPasswords = !showPasswords },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordOutlinedField(
                        value = newPw,
                        onValueChange = { newPw = it },
                        label = "New password (12+ chars)",
                        visible = showPasswords,
                        showVisibilityToggle = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordOutlinedField(
                        value = confirmPw,
                        onValueChange = { confirmPw = it },
                        label = "Confirm new password",
                        visible = showPasswords,
                        showVisibilityToggle = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = currentPw.isNotBlank() && newPw.isNotBlank() && confirmPw.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val problem = validateNewPassword(newPw, confirmPw)
                            if (problem != null) { error = problem; return@Button }
                            error = null
                            scope.launch {
                                runCatching {
                                    val res = api.changePassword(ChangePasswordRequest(currentPw, newPw))
                                    if (!res.isSuccessful) error(parseApiError(res.errorBody()?.string(), res.code(), "Password update failed"))
                                }
                                    .onSuccess { currentPw = ""; newPw = ""; confirmPw = ""; onMessage("Password updated.") }
                                    .onFailure { error = it.message ?: "Password update failed" }
                            }
                        },
                    ) { Text("Update password") }
                }
            }

            if (onSwitchToStaff != null) {
                item(key = "switch-staff") {
                    FilledTonalButton(
                        onClick = onSwitchToStaff,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Switch to Staff View")
                    }
                }
            }

            item(key = "signout") {
                Button(
                    onClick = onSignOut,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Sign out")
                }
            }
        }
    }
}

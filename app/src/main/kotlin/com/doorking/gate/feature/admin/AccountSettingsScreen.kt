package com.doorking.gate.feature.admin

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.NotificationsActive
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
import com.doorking.gate.core.auth.PasskeyAuthenticator
import com.doorking.gate.core.auth.PasskeyResult
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.ChangePasswordRequest
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.FcmTokenInfoDto
import com.doorking.gate.core.network.MeProfileDto
import com.doorking.gate.core.network.PasskeyInfoDto
import com.doorking.gate.core.network.PasskeyRegistrationRequest
import com.doorking.gate.core.network.RegisterFcmRequest
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    api: DoorkingApi,
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    residentMode: Boolean = false,
    passkeyAuthenticator: PasskeyAuthenticator? = null,
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

    fun load(initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        notificationPermGranted = FirebaseHelper.hasNotificationPermission(context)
        scope.launch {
            runCatching {
                val me = api.getMe()
                val pks = runCatching { api.listPasskeys().passkeys }.getOrDefault(emptyList())
                val fcm = runCatching { api.getFcmTokens() }.getOrNull()
                Triple(me, pks, fcm)
            }
                .onSuccess { (me, pks, fcm) ->
                    profile = me
                    passkeys = pks
                    if (fcm != null) {
                        fcmServerEnabled = fcm.enabled
                        fcmTokens = fcm.tokens
                    }
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
                SectionCard(
                    title = "Push notifications",
                    tonal = true,
                    trailing = {
                        when {
                            !firebaseReady -> StatusPill("Unavailable", tone = PillTone.Neutral)
                            !fcmServerEnabled -> StatusPill("Server off", tone = PillTone.Warning)
                            notificationPermGranted && fcmTokens.isNotEmpty() ->
                                StatusPill("${fcmTokens.size} device${if (fcmTokens.size == 1) "" else "s"}", tone = PillTone.Success)
                            else -> StatusPill("Off", tone = PillTone.Neutral)
                        }
                    },
                ) {
                    Text(
                        text = when {
                            !firebaseReady -> "This build does not include Firebase Messaging configuration."
                            !fcmServerEnabled -> "Your server does not have Firebase push configured yet."
                            else -> "Receive instant Android alerts for gate events, code changes, and account notices."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (firebaseReady && fcmServerEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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
                            if (fcmTokens.isNotEmpty()) {
                                Button(
                                    enabled = !fcmBusy,
                                    onClick = {
                                        fcmBusy = true
                                        error = null
                                        scope.launch {
                                            runCatching {
                                                val res = api.sendTestFcm()
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
                        if (fcmTokens.isNotEmpty()) {
                            fcmTokens.forEach { dev ->
                                BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(dev.label, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                dev.lastUsedAt?.let { "Last delivered $it" } ?: "Registered",
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

package com.doorking.gate

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.ReportDrawn
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doorking.gate.core.auth.BiometricKeyManager
import com.doorking.gate.core.auth.BiometricUnlockManager
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.auth.shouldRequireBiometricUnlock
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.AuthEvent
import com.doorking.gate.core.network.PendingPasskeyApprovalDto
import com.doorking.gate.core.network.RespondPasskeyApprovalRequest
import com.doorking.gate.core.push.DoorkingFirebaseMessagingService
import com.doorking.gate.core.push.FirebaseHelper
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.theme.DoorkingTheme
import com.doorking.gate.core.update.PlayInAppUpdateController
import com.doorking.gate.navigation.DoorkingNavRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private val inAppUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { /* Play InstallStateUpdatedListener tracks download & install progress */ }

    private var playUpdateController: PlayInAppUpdateController? = null
    private val biometricUnlocked = MutableStateFlow(false)
    private val resumeTick = MutableStateFlow(0)
    private var backgroundedAtMs: Long = 0L
    private var checkedUpdateInOnStart: Boolean = false
    private var lastApprovalFetchMs: Long = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as DoorkingApp
        val startupCrash = app.startupCrash
        if (startupCrash != null) {
            CrashStore.showInlineCrashView(this, startupCrash, serverUrl = null) { recreate() }
            return
        }

        handleSsoCallbackIntent(app, intent)

        try {
            enableEdgeToEdge()
            val container = app.container
            val updateController = PlayInAppUpdateController(this, inAppUpdateLauncher).also {
                playUpdateController = it
            }
            setContent {
                val authState by container.sessionStorage.state.collectAsStateWithLifecycle()
                val updateReady by updateController.updateReadyToInstall.collectAsStateWithLifecycle()
                val isUnlocked by biometricUnlocked.collectAsStateWithLifecycle()
                val currentResumeTick by resumeTick.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                var pendingApprovals by remember { mutableStateOf<List<PendingPasskeyApprovalDto>>(emptyList()) }
                var approvalToast by remember { mutableStateOf<String?>(null) }

                // Check for a saved crash trace off the main thread so startup never blocks on disk I/O.
                LaunchedEffect(Unit) {
                    val previousCrash = withContext(Dispatchers.IO) { CrashStore.read(this@MainActivity) }
                    if (previousCrash != null && !isFinishing && !isDestroyed) {
                        val loaded = container.sessionStorage.awaitLoaded()
                        CrashStore.showInlineCrashView(
                            activity = this@MainActivity,
                            trace = previousCrash,
                            serverUrl = loaded.serverUrl,
                        ) { recreate() }
                    }
                }

                val darkTheme = when (authState.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                SideEffect(darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(
                            lightScrim = Color.TRANSPARENT,
                            darkScrim = Color.TRANSPARENT,
                            detectDarkMode = { darkTheme },
                        ),
                        navigationBarStyle = SystemBarStyle.auto(
                            lightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                            darkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b),
                            detectDarkMode = { darkTheme },
                        ),
                    )
                }

                // Only mark unlocked once DataStore has loaded; prevents cold-start race where initial
                // empty StoredAuthState(loaded = false) would prematurely unlock before reading prefs.
                val hasSession = !authState.sessionToken.isNullOrBlank()
                SideEffect(authState.loaded, hasSession, authState.biometricLockEnabled) {
                    if (authState.loaded && (!hasSession || !authState.biometricLockEnabled) && !biometricUnlocked.value) {
                        biometricUnlocked.value = true
                    }
                }

                val locked = shouldRequireBiometricUnlock(
                    loaded = authState.loaded,
                    hasSession = hasSession,
                    biometricLockEnabled = authState.biometricLockEnabled,
                    alreadyUnlocked = isUnlocked,
                )

                suspend fun fetchPendingApprovals(prioritizeId: String? = null) {
                    val now = SystemClock.elapsedRealtime()
                    if (prioritizeId.isNullOrBlank() && lastApprovalFetchMs > 0L &&
                        now - lastApprovalFetchMs < APPROVAL_FETCH_MIN_INTERVAL_MS
                    ) {
                        return
                    }
                    lastApprovalFetchMs = now
                    val list = runCatching { container.api.getPendingPasskeyApprovals().requests }
                        .getOrNull() ?: return
                    pendingApprovals = if (!prioritizeId.isNullOrBlank()) {
                        val hit = list.firstOrNull { it.id == prioritizeId }
                        if (hit != null) listOf(hit) + list.filterNot { it.id == prioritizeId } else list
                    } else {
                        list
                    }
                }

                if (hasSession && !locked) {
                    LaunchedEffect(currentResumeTick) {
                        fetchPendingApprovals()
                    }

                    LaunchedEffect(Unit) {
                        container.passkeyApprovalRequests.collect { reqId ->
                            fetchPendingApprovals(prioritizeId = reqId)
                            container.passkeyApprovalRequests.resetReplayCache()
                        }
                    }
                } else if (pendingApprovals.isNotEmpty()) {
                    SideEffect(hasSession, locked) {
                        pendingApprovals = emptyList()
                    }
                }

                if (approvalToast != null) {
                    LaunchedEffect(approvalToast) {
                        delay(3_500L)
                        approvalToast = null
                    }
                }

                DoorkingTheme(darkTheme = darkTheme) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!authState.loaded) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surface,
                            ) {}
                        } else if (locked) {
                            BiometricLockOverlay(
                                activity = this@MainActivity,
                                onUnlocked = { biometricUnlocked.value = true },
                                onSignOut = {
                                    scope.launch {
                                        runCatching {
                                            FirebaseHelper.unregisterCurrentDevice(
                                                this@MainActivity,
                                                container.api,
                                                container.sessionStorage,
                                            )
                                        }
                                        runCatching { container.api.signOut() }
                                        container.sessionStorage.clearSessionToken()
                                        container.authEvents.emit(AuthEvent.SessionExpired)
                                        biometricUnlocked.value = true
                                    }
                                },
                            )
                        } else {
                            DoorkingNavRoot(container = container)
                            val activeApproval = pendingApprovals.firstOrNull()
                            if (activeApproval != null && hasSession) {
                                PasskeyApprovalDialog(
                                    activity = this@MainActivity,
                                    request = activeApproval,
                                    biometricKeyId = authState.biometricKeyId,
                                    onRespond = { body ->
                                        val res = container.api.respondPasskeyApproval(activeApproval.id, body)
                                        if (!res.isSuccessful) {
                                            val raw = res.errorBody()?.string().orEmpty()
                                            val parsed = runCatching {
                                                ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw)
                                            }.getOrNull()
                                            val msg = parsed?.error?.takeIf { it.isNotBlank() }
                                                ?: "Request failed (HTTP ${res.code()})"
                                            if (res.code() == 404 || res.code() == 410) {
                                                pendingApprovals = pendingApprovals.filterNot { it.id == activeApproval.id }
                                            }
                                            error(msg)
                                        }
                                        val tag = "passkey-approval-${activeApproval.id}"
                                        NotificationManagerCompat.from(this@MainActivity).cancel(tag, tag.hashCode())
                                        pendingApprovals = pendingApprovals.filterNot { it.id == activeApproval.id }
                                        approvalToast = if (body.decision == "approve") {
                                            "Passkey approval sent."
                                        } else {
                                            "Passkey request denied."
                                        }
                                    },
                                    onDismissLocal = {
                                        pendingApprovals = pendingApprovals.filterNot { it.id == activeApproval.id }
                                    },
                                )
                            }
                            if (approvalToast != null) {
                                Snackbar(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Text(approvalToast!!)
                                }
                            }
                            if (updateReady) {
                                Snackbar(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    action = {
                                        TextButton(
                                            onClick = { updateController.completeUpdate() },
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.inversePrimary,
                                            ),
                                        ) {
                                            Text("Restart")
                                        }
                                    },
                                    dismissAction = {
                                        IconButton(onClick = { updateController.dismissReadyBanner() }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss update banner")
                                        }
                                    },
                                ) {
                                    Text("Update downloaded — restart to install")
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            val trace = CrashStore.record(this, Thread.currentThread().name, t)
            CrashStore.showInlineCrashView(this, trace, serverUrl = null) { recreate() }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as? DoorkingApp
        if (app != null && app.startupCrash == null) {
            val st = app.container.sessionStorage.state.value
            val bgMs = if (backgroundedAtMs > 0L) SystemClock.elapsedRealtime() - backgroundedAtMs else 0L
            backgroundedAtMs = 0L
            if (
                shouldRequireBiometricUnlock(
                    loaded = st.loaded,
                    hasSession = !st.sessionToken.isNullOrBlank(),
                    biometricLockEnabled = st.biometricLockEnabled,
                    alreadyUnlocked = biometricUnlocked.value,
                    backgroundDurationMs = bgMs,
                )
            ) {
                biometricUnlocked.value = false
            }
        }
        playUpdateController?.onStart()
        checkedUpdateInOnStart = true
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value += 1
        if (checkedUpdateInOnStart) {
            checkedUpdateInOnStart = false
        } else {
            playUpdateController?.onResume()
        }
    }

    override fun onStop() {
        backgroundedAtMs = SystemClock.elapsedRealtime()
        checkedUpdateInOnStart = false
        playUpdateController?.onStop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as? DoorkingApp ?: return
        handleSsoCallbackIntent(app, intent)
    }

    private fun handleSsoCallbackIntent(app: DoorkingApp, intent: Intent?) {
        if (app.startupCrash != null || intent == null) return
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return

        val approvalReqId = runCatching {
            intent.getStringExtra(DoorkingFirebaseMessagingService.EXTRA_PASSKEY_APPROVAL_REQUEST_ID)
        }.getOrNull()
        if (!approvalReqId.isNullOrBlank()) {
            runCatching {
                intent.removeExtra(DoorkingFirebaseMessagingService.EXTRA_PASSKEY_APPROVAL_REQUEST_ID)
            }
            if (APPROVAL_REQUEST_ID_RE.matches(approvalReqId)) {
                app.container.passkeyApprovalRequests.tryEmit(approvalReqId)
            }
        }
        val uri = runCatching { intent.data }.getOrNull() ?: return
        if (uri.scheme == "doorking" && uri.host == "sso" && uri.path == "/callback") {
            runCatching { intent.data = null }
            app.container.ssoCallbacks.tryEmit(uri)
        } else if (uri.scheme == "doorking" && uri.host == "invite") {
            runCatching { intent.data = null }
            app.container.inviteLinks.tryEmit(uri)
        }
    }

    private companion object {
        const val APPROVAL_FETCH_MIN_INTERVAL_MS = 15_000L
        val APPROVAL_REQUEST_ID_RE = Regex("^[A-Za-z0-9_-]{8,128}$")
    }
}

@Composable
private fun PasskeyApprovalDialog(
    activity: FragmentActivity,
    request: PendingPasskeyApprovalDto,
    biometricKeyId: String?,
    onRespond: suspend (RespondPasskeyApprovalRequest) -> Unit,
    onDismissLocal: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(request.id) { mutableStateOf(false) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }

    fun onPickNumber(pickedNumber: Int) {
        if (busy) return
        val keyId = biometricKeyId?.takeIf { it.isNotBlank() }
        if (keyId == null) {
            error = "Hardware fingerprint signing key is unavailable on this device."
            return
        }
        busy = true
        error = null
        scope.launch {
            val cryptoSig = BiometricKeyManager.initSigningCrypto()
            if (cryptoSig == null) {
                error = "Hardware fingerprint signing key is unavailable on this device."
                busy = false
                return@launch
            }
            BiometricUnlockManager.authenticateWithCrypto(
                activity = activity,
                signature = cryptoSig,
                title = "Approve new passkey?",
                subtitle = "Confirm $pickedNumber for ${request.deviceLabel.ifBlank { "other device" }}",
                onSuccess = { unlockedSig ->
                    scope.launch {
                        runCatching {
                            val sigB64 = BiometricKeyManager.signChallenge(unlockedSig, request.challenge)
                            onRespond(
                                RespondPasskeyApprovalRequest(
                                    decision = "approve",
                                    pickedNumber = pickedNumber,
                                    biometricKeyId = keyId,
                                    signature = sigB64,
                                ),
                            )
                        }.onFailure { err ->
                            error = err.message ?: "Approval failed"
                            busy = false
                        }
                    }
                },
                onError = { msg ->
                    error = msg
                    busy = false
                },
                onCancelled = {
                    busy = false
                },
            )
        }
    }

    fun onDeny() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                onRespond(RespondPasskeyApprovalRequest(decision = "deny"))
            }.onFailure { err ->
                error = err.message ?: "Could not deny request"
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismissLocal() },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Fingerprint,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Approve new passkey?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = request.deviceLabel.ifBlank { "Another device" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (request.ip.isNotBlank()) {
                        Text(
                            text = "IP: ${request.ip}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "Tap the matching 2-digit number shown on your other device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (request.choices.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        request.choices.take(3).forEach { choice ->
                            FilledTonalButton(
                                enabled = !busy,
                                onClick = { onPickNumber(choice) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 56.dp),
                            ) {
                                Text(
                                    text = choice.toString(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                if (error != null) {
                    ErrorBanner(error!!)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                onClick = { onDeny() },
            ) {
                Text("Deny")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismissLocal) {
                Text("Later")
            }
        },
    )
}

@Composable
private fun BiometricLockOverlay(
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
    onSignOut: () -> Unit,
) {
    ReportDrawn()
    var error by remember { mutableStateOf<String?>(null) }
    var promptTrigger by remember { mutableIntStateOf(0) }
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)

    LaunchedEffect(promptTrigger) {
        error = null
        BiometricUnlockManager.authenticate(
            activity = activity,
            onSuccess = { currentOnUnlocked() },
            onError = { msg -> error = msg },
            onCancelled = { /* User can tap Unlock button */ },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.Fence,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Doorking Gate is locked",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Confirm your fingerprint or device screen lock to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                ErrorBanner(error!!)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { promptTrigger++ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Unlock with fingerprint")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSignOut) {
                Text("Sign out instead")
            }
        }
    }
}

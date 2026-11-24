package com.doorking.gate

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doorking.gate.core.auth.BiometricUnlockManager
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.auth.shouldRequireBiometricUnlock
import com.doorking.gate.core.network.AuthEvent
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.theme.DoorkingTheme
import com.doorking.gate.core.update.PlayInAppUpdateController
import com.doorking.gate.navigation.DoorkingNavRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val inAppUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { /* Play InstallStateUpdatedListener tracks download & install progress */ }

    private var playUpdateController: PlayInAppUpdateController? = null
    private val biometricUnlocked = MutableStateFlow(false)
    private var backgroundedAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as DoorkingApp
        val serverUrl = if (app.startupCrash == null) app.container.sessionStorage.currentServerUrl() else com.doorking.gate.core.auth.DEFAULT_SERVER_URL
        val previousCrash = app.startupCrash ?: CrashStore.read(this)
        if (previousCrash != null) {
            CrashStore.showInlineCrashView(this, previousCrash, serverUrl) { recreate() }
            return
        }
        // Flush any previously queued crash report if it failed to upload offline earlier.
        CrashStore.uploadPendingAsync(this, serverUrl)

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
                val scope = rememberCoroutineScope()

                val darkTheme = when (authState.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                DisposableEffect(darkTheme) {
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
                    onDispose {}
                }

                // Only mark unlocked once DataStore has loaded; prevents cold-start race where initial
                // empty StoredAuthState(loaded = false) would prematurely unlock before reading prefs.
                val hasSession = !authState.sessionToken.isNullOrBlank()
                LaunchedEffect(authState.loaded, hasSession, authState.biometricLockEnabled) {
                    if (authState.loaded && (!hasSession || !authState.biometricLockEnabled)) {
                        biometricUnlocked.value = true
                    }
                }

                val locked = shouldRequireBiometricUnlock(
                    loaded = authState.loaded,
                    hasSession = hasSession,
                    biometricLockEnabled = authState.biometricLockEnabled,
                    alreadyUnlocked = isUnlocked,
                )

                DoorkingTheme(darkTheme = darkTheme) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DoorkingNavRoot(container = container)
                        if (locked) {
                            BiometricLockOverlay(
                                activity = this@MainActivity,
                                onUnlocked = { biometricUnlocked.value = true },
                                onSignOut = {
                                    scope.launch {
                                        runCatching { container.api.signOut() }
                                        container.sessionStorage.clearSessionToken()
                                        container.authEvents.emit(AuthEvent.SessionExpired)
                                        biometricUnlocked.value = true
                                    }
                                },
                            )
                        }
                        if (updateReady && !locked) {
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
        } catch (t: Throwable) {
            val trace = CrashStore.record(this, Thread.currentThread().name, t)
            CrashStore.showInlineCrashView(this, trace, serverUrl) { recreate() }
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
    }

    override fun onResume() {
        super.onResume()
        playUpdateController?.onResume()
    }

    override fun onStop() {
        backgroundedAtMs = SystemClock.elapsedRealtime()
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
        val uri = intent?.data ?: return
        if (app.startupCrash != null) return
        if (uri.scheme == "doorking" && uri.host == "sso" && uri.path == "/callback") {
            app.container.ssoCallbacks.tryEmit(uri)
        } else if (uri.scheme == "doorking" && uri.host == "invite") {
            app.container.inviteLinks.tryEmit(uri)
        }
    }
}

@Composable
private fun BiometricLockOverlay(
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
    onSignOut: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var promptTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(promptTrigger) {
        error = null
        BiometricUnlockManager.authenticate(
            activity = activity,
            onSuccess = onUnlocked,
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

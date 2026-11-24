package com.doorking.gate

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.ui.theme.DoorkingTheme
import com.doorking.gate.core.update.PlayInAppUpdateController
import com.doorking.gate.navigation.DoorkingNavRoot

class MainActivity : ComponentActivity() {
    // MainActivity extends ComponentActivity (no Fragments); play-services-base pulls fragment:1.0.0 transitively.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val inAppUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { /* Play InstallStateUpdatedListener tracks download & install progress */ }

    private var playUpdateController: PlayInAppUpdateController? = null

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
                DoorkingTheme(darkTheme = darkTheme) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DoorkingNavRoot(container = container)
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
        } catch (t: Throwable) {
            val trace = CrashStore.record(this, Thread.currentThread().name, t)
            CrashStore.showInlineCrashView(this, trace, serverUrl) { recreate() }
        }
    }

    override fun onStart() {
        super.onStart()
        playUpdateController?.onStart()
    }

    override fun onResume() {
        super.onResume()
        playUpdateController?.onResume()
    }

    override fun onStop() {
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

package com.doorking.gate

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.ui.theme.DoorkingTheme
import com.doorking.gate.navigation.DoorkingNavRoot

class MainActivity : ComponentActivity() {
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
            setContent {
                val authState by container.sessionStorage.state.collectAsStateWithLifecycle()
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
                    DoorkingNavRoot(container = container)
                }
            }
        } catch (t: Throwable) {
            val trace = CrashStore.record(this, Thread.currentThread().name, t)
            CrashStore.showInlineCrashView(this, trace, serverUrl) { recreate() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as? DoorkingApp ?: return
        handleSsoCallbackIntent(app, intent)
    }

    private fun handleSsoCallbackIntent(app: DoorkingApp, intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "doorking" && uri.host == "sso" && uri.path == "/callback") {
            if (app.startupCrash == null) {
                app.container.ssoCallbacks.tryEmit(uri)
            }
        }
    }
}

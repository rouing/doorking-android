package com.doorking.gate.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.doorking.gate.DoorkingApp
import com.doorking.gate.core.auth.BiometricUnlockManager
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.auth.shouldRequireBiometricUnlock
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.PortalGateRelaysDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.theme.DoorkingTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Configuration activity shown when a user drags the 1×1 `Open Relay (1×1)` widget from the
 * Android widget catalog onto their home screen, letting them pick any relay they can open.
 */
class RelayWidgetConfigActivity : FragmentActivity() {

    private val biometricUnlocked = MutableStateFlow(false)
    private var backgroundedAtMs: Long = 0L

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        if (intent?.action != AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            finish()
            return
        }

        val app = application as? DoorkingApp
        if (app == null || app.startupCrash != null) {
            finish()
            return
        }

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val expectedProvider = ComponentName(this, RelaySingleWidgetReceiver::class.java)
        val actualProvider = runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        }.getOrNull()
        if (actualProvider != expectedProvider) {
            finish()
            return
        }

        enableEdgeToEdge()

        setContent {
            val authState by app.container.sessionStorage.state.collectAsStateWithLifecycle()
            val isUnlocked by biometricUnlocked.collectAsStateWithLifecycle()
            var loading by remember { mutableStateOf(true) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var groups by remember { mutableStateOf<List<PortalGateRelaysDto>>(emptyList()) }
            var linkedUnits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            val scope = rememberCoroutineScope()

            val darkTheme = when (authState.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
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

            LaunchedEffect(authState.loaded, hasSession, locked) {
                if (!authState.loaded || locked) return@LaunchedEffect
                if (!hasSession) {
                    error = "Sign in to Doorking Gate first to choose a relay."
                    groups = emptyList()
                    loading = false
                    return@LaunchedEffect
                }
                loading = true
                error = null
                runCatching { app.container.api.getMe() }
                    .onSuccess { me ->
                        groups = me.portalRelays
                        linkedUnits = me.linkedUnits.associate { it.gatePhone to it.directoryCode }
                        if (groups.isEmpty()) {
                            error = "No openable gate relays are available for your account."
                        }
                        loading = false
                    }
                    .onFailure { err ->
                        error = ApiClientFactory.parseErrorMessage(err)
                        loading = false
                    }
            }

            DoorkingTheme(darkTheme = darkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        !authState.loaded -> {
                            FullScreenLoading("Loading…")
                        }
                        locked -> {
                            BiometricLockOverlay(
                                activity = this@RelayWidgetConfigActivity,
                                onUnlocked = { biometricUnlocked.value = true },
                                onCancel = { finish() },
                            )
                        }
                        else -> {
                            Scaffold(
                                topBar = {
                                    TopAppBar(
                                        title = { Text("Choose relay for 1×1 widget") },
                                        actions = {
                                            TextButton(
                                                enabled = !saving,
                                                onClick = { finish() },
                                            ) { Text("Cancel") }
                                        },
                                    )
                                },
                            ) { padding ->
                                if (loading) {
                                    FullScreenLoading("Loading relays…", modifier = Modifier.padding(padding))
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(padding),
                                        contentPadding = PaddingValues(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        if (error != null) {
                                            item(key = "err", contentType = "error") {
                                                ErrorBanner(error!!)
                                            }
                                        }
                                        groups.forEach { group ->
                                            if (groups.size > 1) {
                                                item(key = "hdr-${group.phone}", contentType = "header") {
                                                    Text(
                                                        text = group.siteName.ifBlank { group.phone },
                                                        style = MaterialTheme.typography.titleSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            items(
                                                items = group.relays,
                                                key = { opt -> "relay-${group.phone}-${opt.relay}" },
                                                contentType = { "relay" },
                                            ) { opt ->
                                                val label = opt.name.ifBlank { "Relay ${opt.relay}" }
                                                BubbleCard(
                                                    onClick = {
                                                        if (saving) return@BubbleCard
                                                        saving = true
                                                        error = null
                                                        scope.launch {
                                                            runCatching {
                                                                RelaySingleWidget.configureWidget(
                                                                    context = applicationContext,
                                                                    appWidgetId = appWidgetId,
                                                                    gatePhone = group.phone,
                                                                    gateName = group.siteName.ifBlank { group.phone },
                                                                    relay = opt.relay,
                                                                    relayName = label,
                                                                    unitCode = linkedUnits[group.phone],
                                                                )
                                                            }.onSuccess {
                                                                val result = Intent().putExtra(
                                                                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                                                                    appWidgetId,
                                                                )
                                                                setResult(Activity.RESULT_OK, result)
                                                                finish()
                                                            }.onFailure { err ->
                                                                error = err.message?.takeIf { it.isNotBlank() }
                                                                    ?: "Could not configure widget."
                                                                saving = false
                                                            }
                                                        }
                                                    },
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.LockOpen,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(label, style = MaterialTheme.typography.titleMedium)
                                                            Text(
                                                                text = group.siteName.ifBlank { group.phone },
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
    }

    override fun onStop() {
        backgroundedAtMs = SystemClock.elapsedRealtime()
        super.onStop()
    }
}

@Composable
private fun BiometricLockOverlay(
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var promptTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(promptTrigger) {
        error = null
        BiometricUnlockManager.authenticate(
            activity = activity,
            onSuccess = onUnlocked,
            onError = { msg -> error = msg },
            onCancelled = { /* User can tap Unlock or Cancel */ },
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
                text = "Confirm your fingerprint or device screen lock to choose a relay.",
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
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

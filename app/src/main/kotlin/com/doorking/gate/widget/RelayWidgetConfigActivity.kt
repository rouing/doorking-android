package com.doorking.gate.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doorking.gate.DoorkingApp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.PortalGateRelaysDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.theme.DoorkingTheme
import kotlinx.coroutines.launch

/**
 * Configuration activity shown when a user drags the 1×1 `Open Relay (1×1)` widget from the
 * Android widget catalog onto their home screen, letting them pick any relay they can open.
 */
class RelayWidgetConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        enableEdgeToEdge()

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val app = application as DoorkingApp
        setContent {
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }
            var groups by remember { mutableStateOf<List<PortalGateRelaysDto>>(emptyList()) }
            var linkedUnits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                val stored = app.container.sessionStorage.awaitLoaded()
                if (stored.sessionToken.isNullOrBlank()) {
                    error = "Sign in to Doorking Gate first to choose a relay."
                    loading = false
                    return@LaunchedEffect
                }
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

            DoorkingTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Choose relay for 1×1 widget") },
                            actions = {
                                TextButton(onClick = { finish() }) { Text("Cancel") }
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
                                item(key = "err") { ErrorBanner(error!!) }
                            }
                            groups.forEach { group ->
                                if (groups.size > 1) {
                                    item(key = "hdr-${group.phone}") {
                                        Text(
                                            text = group.siteName.ifBlank { group.phone },
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                group.relays.forEach { opt ->
                                    val label = opt.name.ifBlank { "Relay ${opt.relay}" }
                                    item(key = "relay-${group.phone}-${opt.relay}") {
                                        BubbleCard(
                                            onClick = {
                                                scope.launch {
                                                    RelaySingleWidget.configureWidget(
                                                        context = applicationContext,
                                                        appWidgetId = appWidgetId,
                                                        gatePhone = group.phone,
                                                        gateName = group.siteName.ifBlank { group.phone },
                                                        relay = opt.relay,
                                                        relayName = label,
                                                        unitCode = linkedUnits[group.phone],
                                                    )
                                                    val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                                    setResult(Activity.RESULT_OK, result)
                                                    finish()
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

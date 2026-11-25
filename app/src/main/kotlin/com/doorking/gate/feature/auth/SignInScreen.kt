package com.doorking.gate.feature.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.doorking.gate.BuildConfig
import com.doorking.gate.core.auth.normalizeServerUrl
import com.doorking.gate.core.network.SessionResponse
import com.doorking.gate.core.network.SsoProviderInfo
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.PasswordOutlinedField
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Validates external URLs opened in Custom Tabs (SSO redirects and Privacy Policy).
 * Requires `https://`, a non-blank host, and no embedded credentials (`userInfo`); permits
 * `http://` only for loopback/emulator hosts when [allowLocalHttp] is enabled (debug builds).
 */
fun isValidExternalBrowserUrl(
    rawUrl: String,
    allowLocalHttp: Boolean = BuildConfig.DEBUG,
): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return false
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase() ?: return false
    val host = parsed.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    if (!parsed.userInfo.isNullOrBlank()) return false
    val isLocal = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
    return scheme == "https" || (allowLocalHttp && scheme == "http" && isLocal)
}

private fun launchPinnedCustomTab(context: Context, url: String) {
    val customTabsPkg = CustomTabsClient.getPackageName(context, null)
    val tabsIntent = CustomTabsIntent.Builder().build().apply {
        if (!customTabsPkg.isNullOrBlank()) {
            intent.setPackage(customTabsPkg)
        }
    }
    tabsIntent.launchUrl(context, Uri.parse(url))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    initialServerUrl: String,
    initialSessionInfo: SessionResponse?,
    externalError: String? = null,
    onClearExternalError: () -> Unit = {},
    onProbeServer: suspend (serverUrl: String) -> Result<SessionResponse>,
    onSignInPassword: suspend (serverUrl: String, user: String, password: String) -> Result<SessionResponse>,
    onSignInPasskey: suspend (serverUrl: String, activityContext: Context) -> Result<SessionResponse?>,
    onRequestPasswordReset: suspend (serverUrl: String, identifier: String) -> Result<String>,
    onStartSso: suspend (serverUrl: String, providerId: String) -> Result<String>,
) {
    var serverUrl by remember(initialServerUrl) { mutableStateOf(initialServerUrl) }
    var serverInfo by remember(initialSessionInfo) { mutableStateOf(initialSessionInfo) }
    var showServerEditor by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showForgotDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Fetch server capabilities (emailEnabled, SSO providers) on first render.
    LaunchedEffect(initialServerUrl) {
        if (serverInfo == null) {
            onProbeServer(serverUrl).onSuccess { serverInfo = it }
        }
    }

    val submitPassword: () -> Unit = {
        if (!busy && username.isNotBlank() && password.isNotBlank()) {
            busy = true
            error = null
            onClearExternalError()
            scope.launch {
                onSignInPassword(serverUrl, username, password)
                    .onFailure { err -> error = err.message ?: "Sign-in failed" }
                busy = false
            }
        }
    }

    val submitPasskey: () -> Unit = {
        if (!busy) {
            busy = true
            error = null
            onClearExternalError()
            scope.launch {
                onSignInPasskey(serverUrl, context)
                    .onFailure { err -> error = err.message ?: "Passkey sign-in failed" }
                busy = false
            }
        }
    }

    if (showForgotDialog) {
        ForgotPasswordDialog(
            initialIdentifier = username,
            onDismiss = { showForgotDialog = false },
            onSubmit = { id -> onRequestPasswordReset(serverUrl, id) },
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fence,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("Doorking Gate")
                    }
                },
                actions = {
                    TextButton(onClick = { showServerEditor = !showServerEditor }) {
                        Text(if (showServerEditor) "Hide server" else "Server")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showServerEditor) {
                SectionCard(title = "DoorKing server", tonal = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("Server URL") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = !busy && serverUrl.isNotBlank(),
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    onProbeServer(serverUrl)
                                        .onSuccess {
                                            serverInfo = it
                                            showServerEditor = false
                                        }
                                        .onFailure { err -> error = err.message ?: "Could not reach server" }
                                    busy = false
                                }
                            },
                        ) {
                            Text("Check")
                        }
                    }
                }
            }

            SectionCard(
                title = "Sign in",
                tonal = true,
                trailing = {
                    StatusPill(
                        text = serverUrl.removePrefix("https://").removePrefix("http://"),
                        tone = PillTone.Neutral,
                    )
                },
            ) {
                if (serverInfo?.setup == true) {
                    ErrorBanner("No admin account exists on this server yet. Create the first admin account on the web page using the setup code from journalctl.")
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username or email") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordOutlinedField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    enabled = !busy,
                    visible = showPassword,
                    onToggleVisibility = { showPassword = !showPassword },
                    imeAction = ImeAction.Done,
                    onDone = submitPassword,
                    modifier = Modifier.fillMaxWidth(),
                )

                val shownError = error ?: externalError
                if (shownError != null) {
                    ErrorBanner(message = shownError)
                }

                Button(
                    onClick = submitPassword,
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Rounded.Lock, contentDescription = null)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Sign in")
                    }
                }

                FilledTonalButton(
                    onClick = submitPasskey,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Sign in with passkey")
                }

                if (serverInfo?.emailEnabled == true) {
                    TextButton(
                        onClick = { showForgotDialog = true },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Forgot password?")
                    }
                }

                val providers: List<SsoProviderInfo> = serverInfo?.sso.orEmpty()
                if (providers.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "Single sign-on",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    providers.forEach { provider ->
                        FilledTonalButton(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    onStartSso(serverUrl, provider.id)
                                        .onSuccess { authUrl ->
                                            if (!isValidExternalBrowserUrl(authUrl)) {
                                                error = "Server returned an invalid sign-in URL."
                                            } else {
                                                runCatching {
                                                    launchPinnedCustomTab(context, authUrl)
                                                }.onFailure { err ->
                                                    error = err.message ?: "Could not open browser for ${provider.name}"
                                                }
                                            }
                                        }
                                        .onFailure { err ->
                                            error = err.message ?: "SSO failed"
                                        }
                                    busy = false
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Sign in with ${provider.name}")
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    val privacyUrl = "${normalizeServerUrl(serverUrl).trimEnd('/')}/privacy"
                    if (!isValidExternalBrowserUrl(privacyUrl)) {
                        error = "Privacy Policy requires an HTTPS server URL."
                    } else {
                        runCatching { launchPinnedCustomTab(context, privacyUrl) }
                            .onFailure { err -> error = err.message ?: "Could not open Privacy Policy" }
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Privacy Policy")
            }
        }
    }
}

@Composable
private fun ForgotPasswordDialog(
    initialIdentifier: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (identifier: String) -> Result<String>,
) {
    var identifier by remember(initialIdentifier) { mutableStateOf(initialIdentifier) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Reset password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter your username or confirmed email address. If an account matches, a reset link will be emailed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    label = { Text("Username or email") },
                    singleLine = true,
                    enabled = !busy && message == null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) ErrorBanner(error!!)
                if (message != null) {
                    Text(
                        text = message!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            if (message != null) {
                Button(onClick = onDismiss) { Text("Done") }
            } else {
                Button(
                    enabled = !busy && identifier.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            onSubmit(identifier.trim())
                                .onSuccess { msg -> message = msg }
                                .onFailure { err -> error = err.message ?: "Could not send reset link" }
                            busy = false
                        }
                    },
                ) {
                    Text(if (busy) "Sending…" else "Send reset link")
                }
            }
        },
        dismissButton = {
            if (message == null) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

package com.doorking.gate.feature.auth

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.doorking.gate.BuildConfig
import com.doorking.gate.core.network.InviteCheckResponse
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.PasswordOutlinedField
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import kotlinx.coroutines.launch
import java.net.URI

data class ParsedInviteLink(
    val serverUrl: String,
    val token: String,
)

private val INVITE_TOKEN_RE = Regex("^[A-Za-z0-9_-]{16,128}$")

/**
 * Validates and parses a `doorking://invite?server=<https://...>&token=<token>` deep link.
 * Rejects non-HTTPS servers (except localhost/10.0.2.2 in debug builds), embedded credentials,
 * and malformed tokens.
 */
fun parseInviteDeepLink(
    rawServer: String?,
    rawToken: String?,
    allowLocalHttp: Boolean = BuildConfig.DEBUG,
): ParsedInviteLink? {
    val token = rawToken?.trim().orEmpty()
    if (!INVITE_TOKEN_RE.matches(token)) return null
    val serverStr = rawServer?.trim().orEmpty()
    if (serverStr.isBlank()) return null
    val parsed = runCatching { URI(serverStr) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    val host = parsed.host?.lowercase() ?: return null
    if (!parsed.userInfo.isNullOrBlank()) return null
    val isLocal = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
    if (scheme != "https" && !(allowLocalHttp && scheme == "http" && isLocal)) return null
    val portPart = if (parsed.port > 0) ":${parsed.port}" else ""
    val normalizedServer = "$scheme://$host$portPart"
    return ParsedInviteLink(serverUrl = normalizedServer, token = token)
}

fun parseInviteDeepLink(uri: Uri, allowLocalHttp: Boolean = BuildConfig.DEBUG): ParsedInviteLink? {
    if (uri.scheme != "doorking" || uri.host != "invite") return null
    return parseInviteDeepLink(
        rawServer = uri.getQueryParameter("server"),
        rawToken = uri.getQueryParameter("token"),
        allowLocalHttp = allowLocalHttp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcceptInviteScreen(
    serverUrl: String,
    token: String,
    onCheckInvite: suspend (serverUrl: String, token: String) -> Result<InviteCheckResponse>,
    onAcceptInvite: suspend (serverUrl: String, token: String, password: String) -> Result<Unit>,
    onCancel: () -> Unit,
) {
    var checking by remember(serverUrl, token) { mutableStateOf(true) }
    var inviteInfo by remember(serverUrl, token) { mutableStateOf<InviteCheckResponse?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPasswords by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverUrl, token) {
        checking = true
        error = null
        onCheckInvite(serverUrl, token)
            .onSuccess { info ->
                inviteInfo = info
                checking = false
            }
            .onFailure { err ->
                error = err.message ?: "This invitation link is invalid or expired."
                checking = false
            }
    }

    val submit: () -> Unit = {
        val problem = validateNewPassword(password, confirmPassword)
        if (problem != null) {
            error = problem
        } else if (!busy) {
            busy = true
            error = null
            scope.launch {
                onAcceptInvite(serverUrl, token, password)
                    .onFailure { err ->
                        error = err.message ?: "Could not activate account"
                        busy = false
                    }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to sign in")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.Fence, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Accept invitation")
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
            SectionCard(
                title = "Finish setting up your account",
                tonal = true,
                trailing = {
                    StatusPill(
                        text = serverUrl.removePrefix("https://").removePrefix("http://"),
                        tone = PillTone.Neutral,
                    )
                },
            ) {
                if (checking) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text("Checking invitation link…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    val info = inviteInfo
                    if (info != null) {
                        val identity = if (info.isResident) info.email.ifBlank { info.username } else info.username
                        Text(
                            text = if (info.isResident) {
                                "Welcome! You will sign in with $identity. Choose a password (at least 12 characters) to activate your Resident Portal."
                            } else {
                                "Your username is $identity. Choose a password (at least 12 characters) to finish setting up your account."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PasswordOutlinedField(
                            value = password,
                            onValueChange = { password = it },
                            label = "New password (12+ chars)",
                            enabled = !busy,
                            visible = showPasswords,
                            onToggleVisibility = { showPasswords = !showPasswords },
                            imeAction = ImeAction.Next,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PasswordOutlinedField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = "Confirm new password",
                            enabled = !busy,
                            visible = showPasswords,
                            showVisibilityToggle = false,
                            imeAction = ImeAction.Done,
                            onDone = submit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (error != null) {
                        ErrorBanner(error!!)
                    }

                    if (info != null) {
                        Button(
                            onClick = submit,
                            enabled = !busy && password.isNotBlank() && confirmPassword.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            }
                            Text("Save and sign in")
                        }
                    } else {
                        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                            Text("Go to sign in")
                        }
                    }
                }
            }
        }
    }
}

package com.doorking.gate.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.PasswordOutlinedField
import com.doorking.gate.core.ui.components.SectionCard
import kotlinx.coroutines.launch

const val MIN_PASSWORD_LENGTH = 12

fun validateNewPassword(newPassword: String, confirmPassword: String): String? = when {
    newPassword.length < MIN_PASSWORD_LENGTH -> "Password must be at least $MIN_PASSWORD_LENGTH characters."
    newPassword != confirmPassword -> "Passwords do not match."
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForcedPasswordChangeScreen(
    username: String,
    onChangePassword: suspend (currentPassword: String, newPassword: String) -> Result<Unit>,
    onSignOut: () -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPasswords by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val submit: () -> Unit = {
        if (!busy && currentPassword.isNotBlank() && newPassword.isNotBlank() && confirmPassword.isNotBlank()) {
            val problem = validateNewPassword(newPassword, confirmPassword)
            if (problem != null) {
                error = problem
            } else {
                busy = true
                error = null
                scope.launch {
                    onChangePassword(currentPassword, newPassword)
                        .onFailure { err -> error = err.message ?: "Could not update password" }
                    busy = false
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Choose a new password") },
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign out")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Update password for $username", tonal = true) {
                Text(
                    text = "Your account requires a new password of at least $MIN_PASSWORD_LENGTH characters before you can open any gate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PasswordOutlinedField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = "Current password",
                    enabled = !busy,
                    visible = showPasswords,
                    onToggleVisibility = { showPasswords = !showPasswords },
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordOutlinedField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = "New password (12+ characters)",
                    enabled = !busy,
                    visible = showPasswords,
                    showVisibilityToggle = false,
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

                if (error != null) ErrorBanner(error!!)

                Button(
                    enabled = !busy && currentPassword.isNotBlank() && newPassword.isNotBlank() && confirmPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = submit,
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text("Save new password")
                    }
                }
            }
        }
    }
}

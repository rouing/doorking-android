package com.doorking.gate.feature.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.network.JobDto
import com.doorking.gate.core.network.SendPreviewDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.theme.LocalDoorkingColors
import com.doorking.gate.feature.admin.SettingsDraftActions

@Composable
fun GateControlSheetContent(
    state: StaffGateUiState,
    isAdmin: Boolean,
    readOnly: Boolean,
    draftActions: SettingsDraftActions? = null,
    onPreviewSend: () -> Unit,
    onResetWorkingCopy: () -> Unit,
    onPull: (String) -> Unit,
    onOpenLiveRelays: () -> Unit,
    onShowProgress: () -> Unit,
    onClose: () -> Unit,
) {
    val cache = state.residentsReply?.cache
    val running = state.job?.state == "running"
    val hasConflicts = cache?.conflicts?.isNotEmpty() == true
    val hasEdits = cache?.edited == true
    val semantic = LocalDoorkingColors.current
    var confirmReset by remember { mutableStateOf(false) }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset working copy to gate?") },
            text = { Text("Discards all unsent edits and restores the working copy from the last gate pull.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = { confirmReset = false; onResetWorkingCopy(); onClose() },
                ) { Text("Reset to gate") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }

    val (wcLabel, wcTone) = when {
        cache == null || !cache.exists -> "Not pulled yet" to PillTone.Neutral
        hasConflicts -> "${cache.conflicts.size} conflict(s)" to PillTone.Error
        hasEdits -> "Changes not sent" to PillTone.Warning
        else -> "Matches gate" to PillTone.Success
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Gate connection", style = MaterialTheme.typography.titleLarge)
            StatusPill(text = wcLabel, tone = wcTone)
        }

        if (running) {
            Text(
                text = "Connection running (${state.job.what.orEmpty()}). Data reloads automatically when finished.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!readOnly && draftActions != null) {
            Button(
                onClick = { draftActions.save(); onClose() },
                enabled = draftActions.unsavedCount > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = semantic.success,
                    contentColor = semantic.onSuccess,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (draftActions.unsavedCount > 0) "Save to working copy (${draftActions.unsavedCount})"
                    else "Save to working copy",
                )
            }
            Button(
                onClick = { draftActions.discard(); onClose() },
                enabled = draftActions.unsavedCount > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Discard unsaved")
            }
            HorizontalDivider()
        }

        if (!readOnly) {
            Button(
                onClick = { onPreviewSend() },
                enabled = isAdmin && !running && hasEdits && !hasConflicts && !state.sendPreviewBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.sendPreviewBusy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Send to gate")
                }
            }
            if (!isAdmin) {
                Text(
                    text = "Ask a gate admin to review and Send to gate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { confirmReset = true },
                enabled = !running && hasEdits,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Reset to gate")
            }
            HorizontalDivider()
        }

        FilledTonalButton(
            onClick = { onPull("residents"); onClose() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.People, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Pull residents")
        }

        FilledTonalButton(
            onClick = { onPull("transactions"); onClose() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Pull transactions")
        }

        FilledTonalButton(
            onClick = { onClose(); onOpenLiveRelays() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Live relays")
        }

        if (state.job != null && state.job.log.isNotEmpty()) {
            FilledTonalButton(
                onClick = { onClose(); onShowProgress() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Show progress (${state.job.state})")
            }
        }

        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun SendPreviewDialog(
    preview: SendPreviewDto,
    onConfirmSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review changes before sending") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (preview.changes.isEmpty()) {
                    Text("Changed blocks: ${preview.changedBlocks.keys.joinToString(", ")}")
                } else {
                    preview.changes.forEach { change ->
                        BubbleCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = change.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                StatusPill(
                                    text = change.change,
                                    tone = when (change.change) {
                                        "added" -> PillTone.Success
                                        "removed" -> PillTone.Error
                                        else -> PillTone.Primary
                                    },
                                )
                            }
                            change.fields.forEach { f ->
                                Text(
                                    text = "${f.field}: ${f.from.ifBlank { "—" }} → ${f.to.ifBlank { "—" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirmSend) { Text("Send to gate") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun JobProgressDialog(
    job: JobDto,
    onRefresh: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val tone = when (job.state) {
        "running" -> PillTone.Primary
        "done" -> PillTone.Success
        "failed" -> PillTone.Error
        else -> PillTone.Neutral
    }
    val logScroll = rememberScrollState()
    LaunchedEffect(job.log.size, job.state) {
        logScroll.animateScrollTo(logScroll.maxValue)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Gate job (${job.what.orEmpty()})")
                StatusPill(text = job.state.uppercase(), tone = tone)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!job.error.isNullOrBlank()) ErrorBanner(job.error)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = job.log.takeLast(25).joinToString("\n").ifBlank { "Waiting for log output…" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .verticalScroll(logScroll)
                            .padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onRefresh) { Text("Refresh") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveRelaysSheetContent(
    state: StaffGateUiState,
    isAdmin: Boolean,
    onRefresh: () -> Unit,
    onToggleRelay: (relay: Int, holdOpen: Boolean, holdMinutes: Int) -> Unit,
    onCancelTimer: (relay: Int) -> Unit,
    onClose: () -> Unit,
) {
    val holdOptions = listOf(
        15 to "Auto-release after 15 min",
        30 to "Auto-release after 30 min",
        60 to "Auto-release after 1 hour",
        120 to "Auto-release after 2 hours",
        240 to "Auto-release after 4 hours",
        0 to "Hold until manually released",
    )
    var holdMinutes by remember { mutableIntStateOf(30) }
    var holdMenuOpen by remember { mutableStateOf(false) }
    val semantic = LocalDoorkingColors.current
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live Relays", style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(onClick = onRefresh, enabled = !state.relaysBusy) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (state.relaysBusy) "Checking…" else "Refresh")
            }
        }

        if (isAdmin) {
            ExposedDropdownMenuBox(
                expanded = holdMenuOpen,
                onExpandedChange = { holdMenuOpen = !holdMenuOpen },
            ) {
                OutlinedTextField(
                    value = holdOptions.firstOrNull { it.first == holdMinutes }?.second ?: "30 min",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Hold duration") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = holdMenuOpen) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = holdMenuOpen, onDismissRequest = { holdMenuOpen = false }) {
                    holdOptions.forEach { (mins, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { holdMinutes = mins; holdMenuOpen = false },
                        )
                    }
                }
            }
        }

        val shownRelays = listOf(0, 1, 2)
        shownRelays.forEach { relayNum ->
            val relay = state.relays.firstOrNull { it.relay == relayNum }
            val isOpen = relay?.code == "1"
            val timer = state.relayTimers.firstOrNull { it.relay == relayNum }
            val label = state.prefs.relayLabels[relayNum.toString()] ?: "Relay $relayNum"

            BubbleCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = label, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(
                                text = when (relay?.code) {
                                    "1" -> "Held Open"
                                    "0" -> "Closed"
                                    else -> "Unknown"
                                },
                                tone = when (relay?.code) {
                                    "1" -> PillTone.Warning
                                    "0" -> PillTone.Success
                                    else -> PillTone.Neutral
                                },
                            )
                            if (timer != null) {
                                StatusPill(text = "Auto-release set", tone = PillTone.Primary)
                            }
                        }
                    }
                    if (isAdmin) {
                        Button(
                            enabled = !state.relaysBusy && relay != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isOpen) MaterialTheme.colorScheme.primary else semantic.warning,
                                contentColor = if (isOpen) MaterialTheme.colorScheme.onPrimary else semantic.onWarning,
                            ),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleRelay(relayNum, !isOpen, holdMinutes)
                            },
                        ) {
                            Icon(
                                imageVector = if (isOpen) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.padding(horizontal = 2.dp))
                            Text(if (isOpen) "Release" else "Hold Open")
                        }
                    }
                }
                if (isAdmin && timer != null) {
                    TextButton(
                        onClick = { onCancelTimer(relayNum) },
                        enabled = !state.relaysBusy,
                    ) {
                        Text("Cancel auto-release timer")
                    }
                }
            }
        }

        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        Spacer(Modifier.height(12.dp))
    }
}

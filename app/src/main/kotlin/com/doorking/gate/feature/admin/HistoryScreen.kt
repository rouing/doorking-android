package com.doorking.gate.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.doorking.gate.core.ui.components.CollapsibleSectionCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.HistoryEventDto
import com.doorking.gate.core.network.HistoryReply
import com.doorking.gate.core.network.PullArchiveDiffReply
import com.doorking.gate.core.network.PullArchiveEntryDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.StatusPill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val ACTION_LABELS = mapOf(
    "change" to "Change",
    "revert" to "Revert",
    "pull" to "Pull",
    "send" to "Send",
    "relay" to "Relay",
    "camera" to "Camera",
    "access" to "Users",
    "login" to "Gate login",
    "apikey" to "API key",
    "webhook" to "Webhook",
    "note" to "Note",
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    gatePhone: String,
    api: DoorkingApi,
    readOnly: Boolean,
    onMessage: (String) -> Unit = {},
    onMutated: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<HistoryReply?>(null) }
    var pulls by remember { mutableStateOf<List<PullArchiveEntryDto>>(emptyList()) }
    var shown by remember { mutableIntStateOf(20) }
    var expandedEventId by remember { mutableStateOf<String?>(null) }
    var usersExpanded by remember { mutableStateOf(false) }
    var pullsExpanded by remember { mutableStateOf(false) }
    var revertTarget by remember { mutableStateOf<HistoryEventDto?>(null) }
    var diffView by remember { mutableStateOf<PullArchiveDiffReply?>(null) }
    var restoreTarget by remember { mutableStateOf<PullArchiveEntryDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        error = null
        runCatching {
            coroutineScope {
                val hDef = async { api.getHistory(gatePhone) }
                val pDef = async {
                    try {
                        api.getPullArchive(gatePhone).pulls
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                hDef.await() to pDef.await()
            }
        }
            .onSuccess { (h, p) -> history = h; pulls = p; loading = false; refreshing = false }
            .onFailure {
                if (it is CancellationException) throw it
                error = ApiClientFactory.parseErrorMessage(it)
                loading = false
                refreshing = false
            }
    }

    LaunchedEffect(gatePhone) { load(initial = true) }

    if (revertTarget != null) {
        val ev = revertTarget!!
        AlertDialog(
            onDismissRequest = { revertTarget = null },
            title = { Text("Revert change?") },
            text = { Text("Undoes \"${ev.summary}\" in the working copy only. Nothing touches the gate until Send to gate.") },
            confirmButton = {
                Button(onClick = {
                    revertTarget = null
                    scope.launch {
                        runCatching {
                            val res = api.revertHistory(gatePhone, ev.id)
                            if (!res.isSuccessful) {
                                val raw = res.errorBody()?.string().orEmpty()
                                val msg = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw).error }.getOrNull()
                                error(msg ?: "Revert failed (HTTP ${res.code()})")
                            }
                        }
                            .onSuccess { onMessage("Reverted in working copy."); onMutated(); load() }
                            .onFailure { error = it.message ?: "Revert failed" }
                    }
                }) { Text("Revert") }
            },
            dismissButton = { TextButton(onClick = { revertTarget = null }) { Text("Cancel") } },
        )
    }

    if (restoreTarget != null) {
        val pull = restoreTarget!!
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore archived pull #${pull.id}?") },
            text = { Text("Replaces the working copy with this archived pull (${pull.pulledAt.take(19).replace('T', ' ')}). The gate is unchanged until Send to gate.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        restoreTarget = null
                        diffView = null
                        scope.launch {
                            runCatching {
                                val res = api.restorePullArchive(gatePhone, pull.id)
                                if (!res.isSuccessful) error("Restore failed (HTTP ${res.code()})")
                            }
                                .onSuccess { onMessage("Restored pull #${pull.id} into working copy."); onMutated(); load() }
                                .onFailure { error = it.message ?: "Restore failed" }
                        }
                    },
                ) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } },
        )
    }

    if (diffView != null) {
        val diff = diffView!!
        AlertDialog(
            onDismissRequest = { diffView = null },
            title = { Text("Pull #${diff.pull.id} diff") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (diff.changes.isEmpty()) {
                        Text("Working copy already matches this pull.")
                    } else {
                        diff.changes.forEach { item ->
                            BubbleCard {
                                Text(item.label, style = MaterialTheme.typography.titleSmall)
                                item.fields.forEach { f ->
                                    Text(
                                        "${f.field}: ${f.from.ifBlank { "—" }} → ${f.to.ifBlank { "—" }}",
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
                if (!readOnly && diff.changes.isNotEmpty()) {
                    Button(onClick = { restoreTarget = diff.pull }) { Text("Restore") }
                }
            },
            dismissButton = { TextButton(onClick = { diffView = null }) { Text("Close") } },
        )
    }

    if (loading && history == null) {
        FullScreenLoading("Loading history…")
        return
    }

    val users = history?.users.orEmpty()
    val events = history?.events.orEmpty()
    val visibleEvents = remember(events, shown) { events.take(shown) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { load(initial = false) } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item(key = "err", contentType = "error") {
                    ErrorBanner(error!!, onRetry = { scope.launch { load(initial = false) } })
                }
            }

            // Accessible collapsible Gate users (borderless tonal)
            item(key = "users", contentType = "users_section") {
                CollapsibleSectionCard(
                    title = "Gate users (${users.size})",
                    expanded = usersExpanded,
                    tonal = true,
                    onToggle = { usersExpanded = !usersExpanded },
                ) {
                    users.forEach { u ->
                        val roleTone = when (u.role) {
                            "admin" -> PillTone.Primary
                            "editor" -> PillTone.Warning
                            "resident" -> PillTone.Success
                            else -> PillTone.Neutral
                        }
                        BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(u.username, style = MaterialTheme.typography.titleSmall)
                                    StatusPill(u.role.replaceFirstChar(Char::uppercase), tone = roleTone)
                                }
                                Text(
                                    text = u.lastSignIn?.take(10) ?: "Never",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (pulls.isNotEmpty()) {
                item(key = "pulls", contentType = "pulls_section") {
                    CollapsibleSectionCard(
                        title = "Past pulls (${pulls.size})",
                        expanded = pullsExpanded,
                        tonal = true,
                        onToggle = { pullsExpanded = !pullsExpanded },
                    ) {
                        pulls.forEach { pull ->
                            BubbleCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    StatusPill(pull.pulledAt.take(16).replace('T', ' '), tone = PillTone.Neutral)
                                    StatusPill(pull.actor, tone = PillTone.Neutral)
                                    StatusPill("${pull.residentsCount} entries", tone = PillTone.Primary)
                                    StatusPill("${pull.cardsCount} cards", tone = PillTone.Neutral)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            scope.launch {
                                                runCatching { api.diffPullArchive(gatePhone, pull.id) }
                                                    .onSuccess { diffView = it }
                                                    .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                                            }
                                        },
                                    ) { Text("Compare") }
                                    if (!readOnly) {
                                        Button(
                                            modifier = Modifier.weight(1f),
                                            onClick = { restoreTarget = pull },
                                        ) { Text("Restore") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (visibleEvents.isEmpty()) {
                item(key = "events-empty", contentType = "empty") {
                    BubbleCard {
                        Text("No history events yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pulls, sends, relay toggles, and working-copy changes appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Events bubble cards with right-aligned Revert button
            items(visibleEvents, key = { it.id }, contentType = { "history_event" }) { entry ->
                val failed = entry.result == "failed"
                val hasDetails = entry.details.isNotEmpty()
                val expanded by remember(entry.id, hasDetails) {
                    computedStateOf { hasDetails && expandedEventId == entry.id }
                }
                BubbleCard(
                    containerColor = if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = if (hasDetails) {
                        { expandedEventId = if (expanded) null else entry.id }
                    } else {
                        null
                    },
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = entry.summary.ifBlank { ACTION_LABELS[entry.action] ?: entry.action },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusPill(ACTION_LABELS[entry.action] ?: entry.action, tone = PillTone.Primary)
                            if (entry.user.isNotBlank()) StatusPill(entry.user, tone = PillTone.Neutral)
                            StatusPill(entry.time.take(16).replace('T', ' '), tone = PillTone.Neutral)
                            if (entry.result == "done") StatusPill("OK", tone = PillTone.Success)
                            if (failed) StatusPill("Failed", tone = PillTone.Error)
                            if (entry.revertedBy != null) {
                                StatusPill("Reverted by ${entry.revertedBy.user}", tone = PillTone.Warning)
                            }
                            if (entry.details.isNotEmpty()) {
                                StatusPill(if (expanded) "Hide details" else "${entry.details.size} detail(s)", tone = PillTone.Neutral)
                            }
                        }
                        if (failed && !entry.error.isNullOrBlank()) {
                            Text(entry.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (entry.revertedBy == null && entry.revertible && !readOnly) {
                        OutlinedButton(onClick = { revertTarget = entry }) {
                            Text("Revert")
                        }
                    }
                }
                if (expanded) {
                    HorizontalDivider()
                    entry.details.forEach { d ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(d.label, style = MaterialTheme.typography.labelLarge)
                            d.fields.forEach { f ->
                                Text(
                                    "${f.field}: ${f.from.ifBlank { "—" }} → ${f.to.ifBlank { "—" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (events.size > shown) {
            item(key = "more", contentType = "show_more") {
                OutlinedButton(
                    onClick = { shown += 20 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show more (${shown} of ${events.size})") }
            }
        }
        }
    }
}

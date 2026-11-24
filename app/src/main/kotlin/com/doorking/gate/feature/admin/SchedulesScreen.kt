package com.doorking.gate.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.ScheduleDto
import com.doorking.gate.core.network.ScheduleInputDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.components.TimePickerField
import com.doorking.gate.core.ui.components.rememberExpandedModalSheetState
import kotlinx.coroutines.launch

private val DAY_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val ACTION_OPTIONS = listOf(
    "send" to "Send changes",
    "residents" to "Pull residents",
    "transactions" to "Pull transactions",
    "snapshots" to "Fetch snapshots",
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(
    gatePhone: String,
    api: DoorkingApi,
    isAdmin: Boolean,
    onMessage: (String) -> Unit = {},
) {
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var schedules by remember { mutableStateOf<List<ScheduleDto>>(emptyList()) }
    var editing by remember { mutableStateOf<ScheduleDto?>(null) }
    var deleteTarget by remember { mutableStateOf<ScheduleDto?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        error = null
        scope.launch {
            runCatching { api.getSchedules(gatePhone).schedules }
                .onSuccess { schedules = it; loading = false; refreshing = false }
                .onFailure { error = ApiClientFactory.parseErrorMessage(it); loading = false; refreshing = false }
        }
    }

    LaunchedEffect(gatePhone) { load(initial = true) }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.time} schedule?") },
            text = { Text("Stops future automatic runs for this schedule.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            runCatching { api.deleteSchedule(gatePhone, target.id) }
                                .onSuccess { onMessage("Schedule deleted."); load() }
                                .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    if (showCreate || editing != null) {
        val sheetState = rememberExpandedModalSheetState()
        ModalBottomSheet(
            onDismissRequest = { showCreate = false; editing = null },
            sheetState = sheetState,
        ) {
            ScheduleEditorSheet(
                existing = editing,
                onSave = { input ->
                    val target = editing
                    showCreate = false
                    editing = null
                    scope.launch {
                        runCatching {
                            val res = if (target == null) {
                                api.createSchedule(gatePhone, input)
                            } else {
                                api.updateSchedule(gatePhone, target.id, input)
                            }
                            if (!res.isSuccessful) error("Save failed (HTTP ${res.code()})")
                        }
                            .onSuccess { onMessage("Schedule saved."); load() }
                            .onFailure { error = it.message ?: "Save failed" }
                    }
                },
                onCancel = { showCreate = false; editing = null },
            )
        }
    }

    if (loading && schedules.isEmpty()) {
        FullScreenLoading("Loading schedules…")
        return
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { load(initial = false) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) item(key = "err") { ErrorBanner(error!!, onRetry = { load(initial = false) }) }

            if (isAdmin) {
                item(key = "add") {
                    Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("Add scheduled transfer", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            if (schedules.isEmpty()) {
                item(key = "empty") {
                    BubbleCard {
                        Text("No scheduled transfers", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Create a schedule to automatically pull transactions, fetch camera clips, or send working-copy changes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(schedules, key = { it.id }) { sch ->
                    BubbleCard(onClick = if (isAdmin) { { editing = sch } } else null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(sch.time, style = MaterialTheme.typography.titleMedium)
                                StatusPill(
                                    text = if (sch.queued) "Queued" else if (sch.enabled) "Enabled" else "Paused",
                                    tone = if (sch.queued) PillTone.Warning else if (sch.enabled) PillTone.Success else PillTone.Neutral,
                                )
                            }
                            if (isAdmin) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            runCatching { api.runScheduleNow(gatePhone, sch.id) }
                                                .onSuccess { onMessage("Schedule queued to run now."); load() }
                                                .onFailure { error = ApiClientFactory.parseErrorMessage(it) }
                                        }
                                    }) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Run ${sch.time} schedule now")
                                    }
                                    IconButton(onClick = { deleteTarget = sch }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete ${sch.time} schedule", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sch.days.sorted().forEach { d ->
                            StatusPill(DAY_LABELS.getOrElse(d) { "$d" }, tone = PillTone.Primary)
                        }
                        sch.actions.forEach { a ->
                            StatusPill(ACTION_OPTIONS.firstOrNull { it.first == a }?.second ?: a, tone = PillTone.Neutral)
                        }
                    }
                    if (!sch.nextRun.isNullOrBlank()) {
                        Text(
                            "Next run: ${sch.nextRun.take(16).replace('T', ' ')}",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditorSheet(
    existing: ScheduleDto?,
    onSave: (ScheduleInputDto) -> Unit,
    onCancel: () -> Unit,
) {
    var time by remember(existing) { mutableStateOf(existing?.time ?: "02:00") }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    val days = remember(existing) { mutableStateListOf<Int>().apply { addAll(existing?.days ?: listOf(0, 1, 2, 3, 4, 5, 6)) } }
    val actions = remember(existing) { mutableStateListOf<String>().apply { addAll(existing?.actions ?: listOf("transactions")) } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (existing == null) "Add schedule" else "Edit schedule", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Enabled", style = MaterialTheme.typography.labelLarge)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }

        TimePickerField(
            label = "Time (24h HH:MM)",
            value = time,
            onValueChange = { if (it.isNotBlank()) time = it },
            allowClear = false,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Days", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_LABELS.forEachIndexed { idx, label ->
                val active = idx in days
                FilterChip(
                    selected = active,
                    onClick = { if (active) days.remove(idx) else days.add(idx) },
                    label = { Text(label) },
                )
            }
        }

        Text("Transfers", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ACTION_OPTIONS.forEach { (id, label) ->
                val active = id in actions
                FilterChip(
                    selected = active,
                    onClick = { if (active) actions.remove(id) else actions.add(id) },
                    label = { Text(label) },
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                enabled = Regex("^\\d{2}:\\d{2}$").matches(time) && days.isNotEmpty() && actions.isNotEmpty(),
                modifier = Modifier.weight(1.4f),
                onClick = { onSave(ScheduleInputDto(days = days.sorted(), time = time, actions = actions.toList(), enabled = enabled)) },
            ) { Text("Save schedule") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

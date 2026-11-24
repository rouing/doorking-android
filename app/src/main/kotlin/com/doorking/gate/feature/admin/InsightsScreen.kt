package com.doorking.gate.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.network.AccessAuditReply
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    gatePhone: String,
    api: DoorkingApi,
) {
    var days by remember { mutableIntStateOf(90) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<AccessAuditReply?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(targetDays: Int, initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        error = null
        scope.launch {
            runCatching { api.getAudit(gatePhone, targetDays) }
                .onSuccess { audit = it; loading = false; refreshing = false }
                .onFailure { error = ApiClientFactory.parseErrorMessage(it); loading = false; refreshing = false }
        }
    }

    LaunchedEffect(gatePhone, days) { load(days, initial = audit == null) }

    if (loading && audit == null) {
        FullScreenLoading("Analyzing gate activity…")
        return
    }

    val data = audit
    val tabs = listOf(
        "Dormant (${data?.dormant?.size ?: 0})",
        "Unknown (${data?.unknownCards?.size ?: 0})",
        "Codes (${data?.entryCodes?.size ?: 0})",
    )

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { load(days, initial = false) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "period") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(30, 90, 180, 365).forEach { opt ->
                        FilterChip(
                            selected = days == opt,
                            onClick = { days = opt },
                            label = { Text("${opt}d") },
                        )
                    }
                }
            }

            if (error != null) {
                item(key = "err") { ErrorBanner(error!!, onRetry = { load(days, initial = false) }) }
            }

            if (data != null) {
                item(key = "kpis") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KpiTile("Total Events", "${data.traffic.totalEvents}", Modifier.weight(1f))
                            KpiTile("Admits", "${data.traffic.admits}", Modifier.weight(1f))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KpiTile("Denies", "${data.traffic.denies}", Modifier.weight(1f))
                            KpiTile("Open Cycles", "${data.traffic.openCycles}", Modifier.weight(1f))
                        }
                    }
                }

                if (data.traffic.byHour.size == 24) {
                    item(key = "hourly") {
                        val peakCount = data.traffic.byHour.maxOrNull() ?: 0
                        val peakHour = data.traffic.byHour.indexOf(peakCount).coerceAtLeast(0)
                        SectionCard(title = "Activity by Hour (0–23)") {
                            val peak = peakCount.coerceAtLeast(1)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(88.dp)
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "Hourly gate activity chart. Peak activity is $peakCount events around hour $peakHour."
                                    },
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                data.traffic.byHour.forEach { count ->
                                    val fraction = (count.toFloat() / peak.toFloat()).coerceIn(0.06f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height((80f * fraction).dp)
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            .background(
                                                if (count == 0) MaterialTheme.colorScheme.surfaceContainerHighest
                                                else MaterialTheme.colorScheme.primary,
                                            ),
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("12 AM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("6 AM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("12 PM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("6 PM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("11 PM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                item(key = "seg") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        tabs.forEachIndexed { idx, label ->
                            SegmentedButton(
                                selected = tabIndex == idx,
                                onClick = { tabIndex = idx },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }

            when (tabIndex) {
                0 -> {
                    if (data.dormant.isEmpty()) {
                        item(key = "dormant-empty") {
                            BubbleCard { Text("No dormant credentials in the last $days days.") }
                        }
                    } else {
                        itemsIndexed(data.dormant, key = { idx, it -> "d-$idx-${it.directoryCode}" }) { _, item ->
                            BubbleCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(item.name.ifBlank { "Unit ${item.directoryCode}" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    StatusPill("Unit ${item.directoryCode}", tone = PillTone.Primary)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusPill(item.type, tone = PillTone.Neutral)
                                    if (item.entryCode.isNotBlank()) StatusPill("#${item.entryCode}", tone = PillTone.Success)
                                    StatusPill(item.lastSeen?.take(10)?.let { "Last seen $it" } ?: "Never seen", tone = PillTone.Warning)
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (data.unknownCards.isEmpty()) {
                        item(key = "unk-empty") {
                            BubbleCard { Text("No unknown card activity in the last $days days.") }
                        }
                    } else {
                        itemsIndexed(data.unknownCards, key = { idx, it -> "u-$idx-${it.cardNumber}" }) { _, item ->
                            BubbleCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Card #${item.cardNumber}", style = MaterialTheme.typography.titleSmall)
                                    StatusPill("${item.uses} uses", tone = PillTone.Warning)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (item.admits > 0) StatusPill("${item.admits} admit", tone = PillTone.Success)
                                    if (item.denies > 0) StatusPill("${item.denies} deny", tone = PillTone.Error)
                                    if (item.lastSeen.isNotBlank()) StatusPill("Last ${item.lastSeen.take(10)}", tone = PillTone.Neutral)
                                }
                            }
                        }
                    }
                }

                else -> {
                    if (data.entryCodes.isEmpty()) {
                        item(key = "codes-empty") {
                            BubbleCard { Text("No entry code usage recorded in the last $days days.") }
                        }
                    } else {
                        itemsIndexed(data.entryCodes, key = { idx, it -> "c-$idx-${it.directoryCode}-${it.entryCode}" }) { _, item ->
                            BubbleCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(item.name.ifBlank { "Unit ${item.directoryCode}" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    StatusPill("#${item.entryCode}", tone = if (item.flagged) PillTone.Error else PillTone.Primary)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusPill("${item.uses} uses", tone = PillTone.Neutral)
                                    StatusPill("${item.distinctDays} days", tone = PillTone.Neutral)
                                    if (item.offHoursUses > 0) StatusPill("${item.offHoursUses} off-hours", tone = PillTone.Warning)
                                    if (item.flagged) StatusPill("Flagged", tone = PillTone.Error)
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

@Composable
private fun KpiTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    BubbleCard(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.headlineSmall)
    }
}

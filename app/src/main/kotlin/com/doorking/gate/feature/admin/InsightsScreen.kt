package com.doorking.gate.feature.admin

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val LOOKBACK_DAY_OPTIONS = listOf(30, 90, 180, 365)
private val INSIGHTS_TAB_LABELS = listOf("Dormant", "Unknown", "Codes")
private val HOURLY_AXIS_LABELS = listOf("12 AM", "6 AM", "12 PM", "6 PM", "11 PM")
private val NO_INSIGHTS_SEGMENT_BORDER = BorderStroke(0.dp, Color.Transparent)

private fun <T> List<T>.withUniqueKeys(baseKey: (T) -> String): List<Pair<String, T>> {
    val seen = HashMap<String, Int>(size)
    return map { item ->
        val base = baseKey(item)
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        val key = if (count == 1) base else "$base#$count"
        key to item
    }
}

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

    suspend fun load(targetDays: Int, initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        error = null
        runCatching { api.getAudit(gatePhone, targetDays) }
            .onSuccess { audit = it; loading = false; refreshing = false }
            .onFailure {
                if (it is CancellationException) throw it
                error = ApiClientFactory.parseErrorMessage(it)
                loading = false
                refreshing = false
            }
    }

    LaunchedEffect(gatePhone, days) { load(days, initial = audit == null) }

    if (loading && audit == null) {
        FullScreenLoading("Analyzing gate activity…")
        return
    }

    val data = audit
    val dormantItems = remember(data?.dormant) {
        data?.dormant.orEmpty().withUniqueKeys { "d-${it.directoryCode}" }
    }
    val unknownCardItems = remember(data?.unknownCards) {
        data?.unknownCards.orEmpty().withUniqueKeys { "u-${it.cardNumber}" }
    }
    val entryCodeItems = remember(data?.entryCodes) {
        data?.entryCodes.orEmpty().withUniqueKeys { "c-${it.directoryCode}-${it.entryCode}" }
    }
    val dormantCount = data?.dormant?.size ?: 0
    val unknownCount = data?.unknownCards?.size ?: 0
    val entryCodeCount = data?.entryCodes?.size ?: 0
    val tabs = remember(dormantCount, unknownCount, entryCodeCount) {
        listOf(
            "${INSIGHTS_TAB_LABELS[0]} ($dormantCount)",
            "${INSIGHTS_TAB_LABELS[1]} ($unknownCount)",
            "${INSIGHTS_TAB_LABELS[2]} ($entryCodeCount)",
        )
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { load(days, initial = false) } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Borderless tonal lookback selector
            item(key = "period", contentType = "period_selector") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LOOKBACK_DAY_OPTIONS.forEach { opt ->
                        val selected = days == opt
                        Surface(
                            onClick = { days = opt },
                            shape = CircleShape,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "${opt}d",
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            if (error != null) {
                item(key = "err", contentType = "error") {
                    ErrorBanner(error!!, onRetry = { scope.launch { load(days, initial = false) } })
                }
            }

            if (data != null) {
                item(key = "kpis", contentType = "kpis") {
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
                    item(key = "hourly", contentType = "hourly_chart") {
                        val byHour = data.traffic.byHour
                        val peakCount = byHour.maxOrNull() ?: 0
                        val peakHour = byHour.indexOf(peakCount).coerceAtLeast(0)
                        val peak = peakCount.coerceAtLeast(1)
                        val emptyBarColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        val activeBarColor = MaterialTheme.colorScheme.primary
                        SectionCard(title = "Activity by Hour (0–23)", tonal = true) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(88.dp)
                                    .semantics {
                                        contentDescription = "Hourly gate activity chart. Peak activity is $peakCount events around hour $peakHour."
                                    }
                                    .drawWithCache {
                                        val gapPx = 3.dp.toPx()
                                        val maxBarHeight = 80.dp.toPx().coerceAtMost(size.height)
                                        val barWidth = ((size.width - gapPx * (byHour.size - 1)) / byHour.size).coerceAtLeast(1f)
                                        val radiusPx = minOf(4.dp.toPx(), barWidth / 2f)
                                        val emptyPath = Path()
                                        val activePath = Path()
                                        byHour.forEachIndexed { index, count ->
                                            val fraction = (count.toFloat() / peak.toFloat()).coerceIn(0.06f, 1f)
                                            val barHeight = maxBarHeight * fraction
                                            val left = index * (barWidth + gapPx)
                                            val bottom = size.height
                                            val top = bottom - barHeight
                                            val corner = CornerRadius(radiusPx, minOf(radiusPx, barHeight))
                                            val target = if (count == 0) emptyPath else activePath
                                            target.addRoundRect(
                                                RoundRect(
                                                    left = left,
                                                    top = top,
                                                    right = left + barWidth,
                                                    bottom = bottom,
                                                    topLeftCornerRadius = corner,
                                                    topRightCornerRadius = corner,
                                                    bottomRightCornerRadius = CornerRadius.Zero,
                                                    bottomLeftCornerRadius = CornerRadius.Zero,
                                                ),
                                            )
                                        }
                                        onDrawBehind {
                                            if (!emptyPath.isEmpty) drawPath(emptyPath, color = emptyBarColor)
                                            if (!activePath.isEmpty) drawPath(activePath, color = activeBarColor)
                                        }
                                    },
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                HOURLY_AXIS_LABELS.forEach { axisLabel ->
                                    Text(
                                        text = axisLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // Borderless Material 3 segmented switcher
                item(key = "seg", contentType = "segment_tabs") {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                        space = 2.dp,
                    ) {
                        tabs.forEachIndexed { idx, label ->
                            SegmentedButton(
                                selected = tabIndex == idx,
                                onClick = { tabIndex = idx },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                                border = NO_INSIGHTS_SEGMENT_BORDER,
                                colors = SegmentedButtonDefaults.colors(
                                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    activeBorderColor = Color.Transparent,
                                    inactiveBorderColor = Color.Transparent,
                                ),
                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }

                when (tabIndex) {
                    0 -> {
                        if (dormantItems.isEmpty()) {
                            item(key = "dormant-empty", contentType = "empty") {
                                BubbleCard { Text("No dormant credentials in the last $days days.") }
                            }
                        } else {
                            items(dormantItems, key = { it.first }, contentType = { "dormant_entry" }) { (_, item) ->
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
                        if (unknownCardItems.isEmpty()) {
                            item(key = "unk-empty", contentType = "empty") {
                                BubbleCard { Text("No unknown card activity in the last $days days.") }
                            }
                        } else {
                            items(unknownCardItems, key = { it.first }, contentType = { "unknown_card" }) { (_, item) ->
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
                        if (entryCodeItems.isEmpty()) {
                            item(key = "codes-empty", contentType = "empty") {
                                BubbleCard { Text("No entry code usage recorded in the last $days days.") }
                            }
                        } else {
                            items(entryCodeItems, key = { it.first }, contentType = { "entry_code" }) { (_, item) ->
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

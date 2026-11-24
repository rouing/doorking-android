package com.doorking.gate.feature.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.doorking.gate.core.media.MediaItemEntry
import com.doorking.gate.core.media.MediaViewerDialog
import com.doorking.gate.core.network.TransactionRowDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SearchPillBar
import com.doorking.gate.core.ui.components.StatusPill
import okhttp3.OkHttpClient

private val MP4_EXT_REGEX = Regex("\\.mp4$", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionsTab(
    state: StaffGateUiState,
    serverUrl: String,
    gatePhone: String,
    okHttpClient: OkHttpClient,
    onLoadOlder: () -> Unit,
    onRequestSnapshot: (minute: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Pair<TransactionRowDto, List<MediaItemEntry>>?>(null) }

    val filtered = remember(state.transactions, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            state.transactions
        } else {
            state.transactions.filter { r ->
                r.name.lowercase().contains(q) ||
                    r.eventType.lowercase().contains(q) ||
                    r.access.lowercase().contains(q) ||
                    r.directoryCode.lowercase().contains(q) ||
                    r.number.orEmpty().contains(q) ||
                    r.date.contains(q)
            }
        }
    }

    if (viewing != null) {
        val (tx, items) = viewing!!
        MediaViewerDialog(
            title = tx.name.ifBlank { tx.eventType.ifBlank { "Gate Event" } },
            subtitle = "${tx.date} ${tx.time} · ${tx.eventType} ${tx.access}".trim(),
            items = items,
            okHttpClient = okHttpClient,
            onDismiss = { viewing = null },
        )
    }

    val hasCamera = state.cameras != null
    val isVideoMode = state.cameras?.mode == "video"
    val base = serverUrl.trimEnd('/')

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.error != null) {
            item(key = "error") { ErrorBanner(state.error) }
        }

        item(key = "search") {
            SearchPillBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search transactions",
            )
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                BubbleCard {
                    Text("No transactions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Open the Gate tab and tap Pull transactions to download recent gate events.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = filtered,
                key = { index, it -> "$index-${it.source ?: "gate"}-${it.ts}-${it.raw}-${it.directoryCode}" },
            ) { _, row ->
                val activeSnap = state.snapshots[row.minute]?.takeIf { it.status != "removed" }
                val allFiles = activeSnap?.files.orEmpty()
                val hasVideoFile = allFiles.any { it.endsWith(".mp4", ignoreCase = true) }
                // In video mode, prefer saved .mp4 clips; poster JPEG is saved alongside as ch<N>.jpg (posterFile).
                val displayFiles = remember(allFiles, isVideoMode, hasVideoFile) {
                    if (isVideoMode && hasVideoFile) {
                        allFiles.filter { it.endsWith(".mp4", ignoreCase = true) }
                    } else {
                        allFiles
                    }
                }
                val hasReadyMedia = displayFiles.isNotEmpty() && (!isVideoMode || hasVideoFile)
                val mediaEntries = remember(row.minute, displayFiles, base, gatePhone) {
                    displayFiles.map { file ->
                        MediaItemEntry(
                            label = file.substringBefore('.').uppercase(),
                            url = "$base/api/gates/$gatePhone/snapshots/${row.minute}/$file",
                            isVideo = file.endsWith(".mp4", ignoreCase = true),
                        )
                    }
                }
                val thumbUrl = remember(row.minute, displayFiles, hasVideoFile, base, gatePhone) {
                    when {
                        hasVideoFile -> {
                            val firstMp4 = displayFiles.firstOrNull { it.endsWith(".mp4", ignoreCase = true) }
                            firstMp4?.let { mp4 ->
                                val poster = mp4.replace(MP4_EXT_REGEX, ".jpg")
                                "$base/api/gates/$gatePhone/snapshots/${row.minute}/$poster"
                            }
                        }
                        else -> displayFiles.firstOrNull { it.endsWith(".jpg", ignoreCase = true) }?.let { jpg ->
                            "$base/api/gates/$gatePhone/snapshots/${row.minute}/$jpg"
                        }
                    }
                }

                val accessTone = when {
                    row.access.contains("Deny", ignoreCase = true) -> PillTone.Error
                    row.access.contains("Admit", ignoreCase = true) -> PillTone.Success
                    row.eventType.contains("Hold", ignoreCase = true) -> PillTone.Warning
                    else -> PillTone.Neutral
                }

                BubbleCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val headline = buildString {
                                append(row.name.ifBlank { row.eventType.ifBlank { "Gate Event" } })
                                if (row.directoryCode.isNotBlank()) append(" · #${row.directoryCode}")
                            }
                            Text(text = headline, style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val combined = listOf(row.eventType, row.access)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                if (combined.isNotBlank()) {
                                    StatusPill(text = combined, tone = accessTone)
                                }
                                StatusPill(text = "${row.date} ${row.time}".trim(), tone = PillTone.Neutral)
                                if (row.relay.isNotBlank()) {
                                    val resolved = state.prefs.relayLabels[row.relay]
                                        ?: row.relay.trim().removePrefix("Relay ").removePrefix("relay ").trim()
                                    val formatted = resolved.replaceFirstChar {
                                        if (it.isLowerCase()) it.titlecase() else it.toString()
                                    }
                                    StatusPill(text = "Relay · $formatted", tone = PillTone.Primary)
                                }
                            }
                        }

                        // Right-aligned camera thumbnail (uses ch<N>.jpg poster for .mp4 clips) or Get video button.
                        if (hasReadyMedia) {
                            Box(
                                modifier = Modifier
                                    .size(width = 80.dp, height = 54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = if (hasVideoFile) "Play gate video" else "View gate snapshot",
                                    ) { viewing = row to mediaEntries },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (thumbUrl != null) {
                                    AsyncImage(
                                        model = thumbUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                                if (hasVideoFile) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        } else if (hasCamera && row.camera && row.minute.isNotBlank()) {
                            FilledTonalButton(
                                onClick = { onRequestSnapshot(row.minute) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (isVideoMode) "Get video" else "Get snap",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            if (state.txNextBefore != null) {
                item(key = "more") {
                    FilledTonalButton(
                        onClick = onLoadOlder,
                        enabled = !state.loadingOlderTx,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.loadingOlderTx) "Loading older…" else "Load older transactions")
                    }
                }
            }
        }
    }
}

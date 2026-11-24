package com.doorking.gate.feature.staff

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.doorking.gate.core.media.MediaItemEntry
import com.doorking.gate.core.media.MediaViewerDialog
import com.doorking.gate.core.network.DeviceRangeDto
import com.doorking.gate.core.network.ResidentRowDto
import com.doorking.gate.core.network.TransactionRowDto
import com.doorking.gate.core.ui.components.AccessCredentialsSection
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SearchPillBar
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.feature.gates.formatUsPhone
import okhttp3.OkHttpClient

private val DIR_MP4_EXT_REGEX = Regex("\\.mp4$", RegexOption.IGNORE_CASE)

private fun normalizeDirCode(code: String): String =
    code.trim().trimStart('0').ifEmpty { code.trim() }

private enum class DirectorySort(val label: String) {
    Name("Name"),
    Unit("Unit"),
    EntryCode("Code"),
}

private fun deviceLabelForCard(devices: List<DeviceRangeDto>, card: String): String {
    val num = card.filter(Char::isDigit).toIntOrNull() ?: return "Access Card"
    val match = devices.firstOrNull { range ->
        val from = range.from.filter(Char::isDigit).toIntOrNull()
        val to = range.to.filter(Char::isDigit).toIntOrNull()
        from != null && to != null && num in from..to && range.name.isNotBlank()
    }
    return match?.name ?: "Access Card"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DirectoryTab(
    state: StaffGateUiState,
    isVendor: Boolean,
    canEdit: Boolean,
    editingTarget: ResidentRowDto?,
    showAddSheet: Boolean,
    serverUrl: String = "",
    gatePhone: String = "",
    okHttpClient: OkHttpClient? = null,
    onRequestSnapshot: ((minute: String) -> Unit)? = null,
    onRequestEdit: (ResidentRowDto?) -> Unit,
    onDismissAdd: () -> Unit,
    onSave: (
        existing: ResidentRowDto?,
        directoryCode: String,
        name: String,
        areaCode: String,
        phone: String,
        entryCode: String,
        note: String,
        onDone: () -> Unit,
    ) -> Unit,
    onDelete: (String) -> Unit,
    onShareWelcomePdf: (ResidentRowDto) -> Unit,
    onDecideConflict: (kind: String, key: String, take: String) -> Unit,
) {
    var query by remember(isVendor) { mutableStateOf("") }
    var sort by remember(isVendor) { mutableStateOf(DirectorySort.Name) }
    var expandedCode by remember(isVendor) { mutableStateOf<String?>(null) }
    var deleteConfirm by remember { mutableStateOf<ResidentRowDto?>(null) }
    var viewingMedia by remember { mutableStateOf<Pair<TransactionRowDto, List<MediaItemEntry>>?>(null) }
    val context = LocalContext.current

    if (viewingMedia != null && okHttpClient != null) {
        val (tx, items) = viewingMedia!!
        MediaViewerDialog(
            title = tx.name.ifBlank { tx.eventType.ifBlank { "Gate Event" } },
            subtitle = "${tx.date} ${tx.time} · ${tx.eventType} ${tx.access}".trim(),
            items = items,
            okHttpClient = okHttpClient,
            onDismiss = { viewingMedia = null },
        )
    }

    val allRows = state.residentsReply?.rows.orEmpty()
    val kindRows = remember(allRows, isVendor) {
        allRows.filter { (it.type.equals("Vendor", ignoreCase = true)) == isVendor }
    }
    val filtered = remember(kindRows, query, sort) {
        val q = query.trim().lowercase()
        val matched = if (q.isEmpty()) {
            kindRows
        } else {
            kindRows.filter { r ->
                r.name.lowercase().contains(q) ||
                    r.directoryCode.lowercase().contains(q) ||
                    r.entryCode.contains(q) ||
                    r.phone.contains(q) ||
                    r.cards.any { it.contains(q) } ||
                    r.note.orEmpty().lowercase().contains(q)
            }
        }
        when (sort) {
            DirectorySort.Name -> matched.sortedBy { it.name.lowercase() }
            DirectorySort.Unit -> matched.sortedBy { it.directoryCode }
            DirectorySort.EntryCode -> matched.sortedBy { it.entryCode.ifBlank { "zzzz" } }
        }
    }

    if (deleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete ${deleteConfirm!!.name.ifBlank { deleteConfirm!!.directoryCode }}?") },
            text = { Text("Removes this entry in the working copy. Nothing touches the physical gate until you Send to gate.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        val code = deleteConfirm!!.directoryCode
                        deleteConfirm = null
                        onDelete(code)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") }
            },
        )
    }

    if (showAddSheet || editingTarget != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                onRequestEdit(null)
                onDismissAdd()
            },
            sheetState = sheetState,
        ) {
            ResidentEditorSheet(
                existing = editingTarget,
                isVendor = isVendor,
                existingEntryCodes = allRows.map { it.entryCode },
                busy = state.editBusy,
                error = state.error,
                onSave = { code, name, area, phone, entryCode, note ->
                    onSave(editingTarget, code, name, area, phone, entryCode, note) {
                        onRequestEdit(null)
                        onDismissAdd()
                    }
                },
                onCancel = {
                    onRequestEdit(null)
                    onDismissAdd()
                },
            )
        }
    }

    val conflicts = state.residentsReply?.cache?.conflicts.orEmpty()
    val devices = state.prefs.deviceAssociation

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.error != null) {
            item(key = "error") { ErrorBanner(state.error) }
        }

        if (conflicts.isNotEmpty() && !isVendor) {
            item(key = "conflicts") {
                SectionCard(
                    title = "${conflicts.size} Sync Conflict${if (conflicts.size == 1) "" else "s"}",
                    trailing = { StatusPill("Action needed", tone = PillTone.Error) },
                ) {
                    conflicts.forEach { c ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = c.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Gate: ${c.gate} · Yours: ${c.mine}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (canEdit) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = { onDecideConflict(c.kind, c.key, "gate") },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Take gate's") }
                                    Button(
                                        onClick = { onDecideConflict(c.kind, c.key, "mine") },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Keep mine") }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchPillBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = if (isVendor) "Search vendors" else "Search residents",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DirectorySort.entries.forEach { option ->
                            FilterChip(
                                selected = sort == option,
                                onClick = { sort = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    StatusPill(text = "${filtered.size} shown", tone = PillTone.Neutral)
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                BubbleCard {
                    Text(
                        text = if (query.isBlank()) "No ${if (isVendor) "vendors" else "residents"} yet" else "No matching entries",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (state.residentsReply == null) {
                            "Open the Gate tab and tap Pull residents to load the directory."
                        } else {
                            "Try clearing the filter or tap + to add a new entry."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(items = filtered, key = { index, it -> "$index-${it.directoryCode}" }) { _, row ->
                val expanded = expandedCode == row.directoryCode
                BubbleCard(
                    modifier = Modifier.semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                    onClick = { expandedCode = if (expanded) null else row.directoryCode },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.name.ifBlank { "(Unnamed)" },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(text = "Unit ${row.directoryCode}", tone = PillTone.Primary)
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "Collapse details" else "Expand details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (row.entryCode.isNotBlank()) {
                            StatusPill(text = "#${row.entryCode}", tone = PillTone.Success)
                        }
                        if (!isVendor) {
                            val fullPhone = (row.areaCode + row.phone).filter(Char::isDigit)
                            if (fullPhone.isNotBlank()) {
                                StatusPill(text = formatUsPhone(fullPhone), tone = PillTone.Neutral)
                            }
                        }
                        if (row.cards.isNotEmpty()) {
                            StatusPill(text = "${row.cards.size} card${if (row.cards.size == 1) "" else "s"}", tone = PillTone.Neutral)
                        }
                        if (row.portalEnabled == true) {
                            StatusPill(text = if (row.portalInvited == true) "Portal invited" else "Portal", tone = PillTone.Success)
                        }
                        if (row.hidden) {
                            StatusPill(text = "Hidden", tone = PillTone.Warning)
                        }
                    }

                    if (expanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (row.cards.isNotEmpty()) {
                            val credentials = remember(row.cards, devices) {
                                row.cards.map { num -> num to deviceLabelForCard(devices, num) }
                            }
                            AccessCredentialsSection(
                                credentials = credentials,
                                onCopyNumber = { cardNum ->
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Access Card", cardNum))
                                    Toast.makeText(context, "Copied #$cardNum", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                        if (!row.note.isNullOrBlank()) {
                            Text(
                                text = "Note: ${row.note}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val recentTransactions = remember(state.transactions, row.directoryCode) {
                            val targetKey = normalizeDirCode(row.directoryCode)
                            state.transactions
                                .filter { it.directoryCode.isNotBlank() && normalizeDirCode(it.directoryCode) == targetKey }
                                .take(5)
                        }
                        ResidentRecentTransactionsSection(
                            hasAnyTransactions = state.transactions.isNotEmpty(),
                            recent = recentTransactions,
                            state = state,
                            serverUrl = serverUrl,
                            gatePhone = gatePhone,
                            canOpenMedia = okHttpClient != null,
                            onOpenMedia = { tx, items -> viewingMedia = tx to items },
                            onRequestSnapshot = onRequestSnapshot,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val expressiveShape = RoundedCornerShape(14.dp)
                            if (canEdit) {
                                Button(
                                    onClick = { onRequestEdit(row) },
                                    shape = expressiveShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp),
                                ) {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text("Edit")
                                }
                            }
                            if (!isVendor) {
                                FilledTonalButton(
                                    onClick = { onShareWelcomePdf(row) },
                                    shape = expressiveShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp),
                                ) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text("PDF")
                                }
                            }
                            if (canEdit) {
                                FilledTonalIconButton(
                                    onClick = { deleteConfirm = row },
                                    shape = expressiveShape,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "Delete ${row.name.ifBlank { row.directoryCode }}",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResidentRecentTransactionsSection(
    hasAnyTransactions: Boolean,
    recent: List<TransactionRowDto>,
    state: StaffGateUiState,
    serverUrl: String,
    gatePhone: String,
    canOpenMedia: Boolean,
    onOpenMedia: (TransactionRowDto, List<MediaItemEntry>) -> Unit,
    onRequestSnapshot: ((minute: String) -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasCamera = state.cameras != null && serverUrl.isNotBlank() && gatePhone.isNotBlank()
    val isVideoMode = state.cameras?.mode == "video"
    val base = serverUrl.trimEnd('/')

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse recent transactions" else "Expand recent transactions",
                        onClick = { expanded = !expanded },
                    )
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent transactions",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(
                        text = "${recent.size}",
                        tone = if (recent.isNotEmpty()) PillTone.Primary else PillTone.Neutral,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!hasAnyTransactions) {
                        Text(
                            text = "No transactions yet. Pull transactions from the Gate tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (recent.isEmpty()) {
                        Text(
                            text = "No recent transactions for this entry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        recent.forEachIndexed { index, tx ->
                            val topCorner = if (index == 0) 12.dp else 4.dp
                            val bottomCorner = if (index == recent.lastIndex) 12.dp else 4.dp
                            val itemShape = RoundedCornerShape(
                                topStart = topCorner,
                                topEnd = topCorner,
                                bottomStart = bottomCorner,
                                bottomEnd = bottomCorner,
                            )
                            val accessTone = when {
                                tx.access.contains("Deny", ignoreCase = true) -> PillTone.Error
                                tx.access.contains("Admit", ignoreCase = true) -> PillTone.Success
                                tx.eventType.contains("Hold", ignoreCase = true) -> PillTone.Warning
                                else -> PillTone.Neutral
                            }
                            val activeSnap = state.snapshots[tx.minute]?.takeIf { it.status != "removed" }
                            val allFiles = activeSnap?.files.orEmpty()
                            val hasVideoFile = allFiles.any { it.endsWith(".mp4", ignoreCase = true) }
                            val displayFiles = if (isVideoMode && hasVideoFile) {
                                allFiles.filter { it.endsWith(".mp4", ignoreCase = true) }
                            } else {
                                allFiles
                            }
                            val hasReadyMedia = canOpenMedia && hasCamera && displayFiles.isNotEmpty() && (!isVideoMode || hasVideoFile)
                            val mediaEntries = displayFiles.map { file ->
                                MediaItemEntry(
                                    label = file.substringBefore('.').uppercase(),
                                    url = "$base/api/gates/$gatePhone/snapshots/${tx.minute}/$file",
                                    isVideo = file.endsWith(".mp4", ignoreCase = true),
                                )
                            }
                            val thumbUrl = if (hasReadyMedia) {
                                if (hasVideoFile) {
                                    displayFiles.firstOrNull { it.endsWith(".mp4", ignoreCase = true) }?.let { mp4 ->
                                        "$base/api/gates/$gatePhone/snapshots/${tx.minute}/${mp4.replace(DIR_MP4_EXT_REGEX, ".jpg")}"
                                    }
                                } else {
                                    displayFiles.firstOrNull { it.endsWith(".jpg", ignoreCase = true) }?.let { jpg ->
                                        "$base/api/gates/$gatePhone/snapshots/${tx.minute}/$jpg"
                                    }
                                }
                            } else {
                                null
                            }

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = itemShape,
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FlowRow(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        val combined = listOf(tx.eventType, tx.access)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · ")
                                        if (combined.isNotBlank()) {
                                            StatusPill(text = combined, tone = accessTone)
                                        }
                                        if (!tx.number.isNullOrBlank() && tx.lookup != "directory") {
                                            StatusPill(text = "#${tx.number}", tone = PillTone.Neutral)
                                        }
                                        StatusPill(text = "${tx.date} ${tx.time}".trim(), tone = PillTone.Neutral)
                                        if (tx.relay.isNotBlank()) {
                                            val resolved = state.prefs.relayLabels[tx.relay]
                                                ?: tx.relay.trim().removePrefix("Relay ").removePrefix("relay ").trim()
                                            val formatted = resolved.replaceFirstChar {
                                                if (it.isLowerCase()) it.titlecase() else it.toString()
                                            }
                                            StatusPill(text = "Relay · $formatted", tone = PillTone.Primary)
                                        }
                                    }

                                    if (hasReadyMedia) {
                                        Box(
                                            modifier = Modifier
                                                .size(width = 64.dp, height = 44.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                                .clickable(
                                                    role = Role.Button,
                                                    onClickLabel = if (hasVideoFile) "Play gate video" else "View gate snapshot",
                                                ) { onOpenMedia(tx, mediaEntries) },
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
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.PlayArrow,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                        }
                                    } else if (hasCamera && tx.camera && tx.minute.isNotBlank() && onRequestSnapshot != null) {
                                        FilledTonalIconButton(
                                            onClick = { onRequestSnapshot(tx.minute) },
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Videocam,
                                                contentDescription = if (isVideoMode) "Get video" else "Get snapshot",
                                                modifier = Modifier.size(18.dp),
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

@Composable
private fun ResidentEditorSheet(
    existing: ResidentRowDto?,
    isVendor: Boolean,
    existingEntryCodes: List<String>,
    busy: Boolean,
    error: String? = null,
    onSave: (directoryCode: String, name: String, areaCode: String, phone: String, entryCode: String, note: String) -> Unit,
    onCancel: () -> Unit,
) {
    var directoryCode by remember(existing) { mutableStateOf(existing?.directoryCode.orEmpty()) }
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var areaCode by remember(existing) { mutableStateOf(existing?.areaCode.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var entryCode by remember(existing) { mutableStateOf(existing?.entryCode.orEmpty()) }
    var note by remember(existing) { mutableStateOf(existing?.note.orEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = when {
                existing == null -> "Add ${if (isVendor) "vendor" else "resident"}"
                isVendor -> "Edit vendor ${existing.directoryCode}"
                else -> "Edit unit ${existing.directoryCode}"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        if (error != null) ErrorBanner(error)
        if (existing == null) {
            OutlinedTextField(
                value = directoryCode,
                onValueChange = { directoryCode = it.filter(Char::isDigit).take(4) },
                label = { Text("Directory code (leave blank for next open)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.filter { ch -> ch in ' '..'~' }.take(15) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!isVendor) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = areaCode,
                    onValueChange = { areaCode = it.filter(Char::isDigit).take(4) },
                    label = { Text("Area") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.9f),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { ch -> ch.isDigit() || ch == '-' }.take(8) },
                    label = { Text("Phone (7 digits)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1.6f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = entryCode,
                onValueChange = { entryCode = it.filter(Char::isDigit).take(4) },
                label = { Text("Entry code (4 digits)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { entryCode = StaffGateViewModel.generateSafeEntryCode(existingEntryCodes) },
            ) {
                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Generate")
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Local note (never sent to gate)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Button(
                onClick = { onSave(directoryCode, name, areaCode, phone, entryCode, note) },
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.weight(1.4f),
            ) {
                Text(if (busy) "Saving…" else "Save to working copy")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

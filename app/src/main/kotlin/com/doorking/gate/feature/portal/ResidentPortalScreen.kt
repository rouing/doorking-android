package com.doorking.gate.feature.portal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.outlined.Fence
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import com.doorking.gate.core.media.MediaItemEntry
import com.doorking.gate.core.media.MediaViewerDialog
import com.doorking.gate.core.ui.components.AccessCredentialsSection
import com.doorking.gate.feature.admin.AccountSettingsScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doorking.gate.AppContainer
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.media.PdfShareHelper
import com.doorking.gate.core.network.SessionResponse
import com.doorking.gate.core.network.TransactionRowDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.theme.LocalDoorkingColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PortalTab(val label: String) {
    Gate("Gate"),
    Activity("Activity"),
    Requests("Requests"),
    Account("Account"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResidentPortalScreen(
    container: AppContainer,
    gatePhone: String,
    session: SessionResponse?,
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    onSwitchToStaff: (() -> Unit)?,
    onSignOut: () -> Unit,
) {
    val vm: ResidentPortalViewModel = viewModel(
        key = "portal-$gatePhone",
        factory = ResidentPortalViewModel.factory(container, gatePhone),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(PortalTab.Gate) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(state.bannerMessage) {
        val msg = state.bannerMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearBanner()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Column {
                        Text(state.me?.gate?.siteName?.ifBlank { "Resident Portal" } ?: "Resident Portal")
                        state.me?.unit?.let { unit ->
                            Text(
                                text = "Unit ${unit.directoryCode} · ${unit.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (onSwitchToStaff != null) {
                        IconButton(onClick = onSwitchToStaff) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = "Switch to staff view")
                        }
                    }
                    IconButton(onClick = { vm.loadAll(initial = false) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == PortalTab.Gate,
                    onClick = { tab = PortalTab.Gate },
                    icon = { Icon(if (tab == PortalTab.Gate) Icons.Rounded.Fence else Icons.Outlined.Fence, contentDescription = null) },
                    label = { Text(PortalTab.Gate.label) },
                )
                NavigationBarItem(
                    selected = tab == PortalTab.Activity,
                    onClick = { tab = PortalTab.Activity },
                    icon = { Icon(if (tab == PortalTab.Activity) Icons.Rounded.History else Icons.Outlined.History, contentDescription = null) },
                    label = { Text(PortalTab.Activity.label) },
                )
                NavigationBarItem(
                    selected = tab == PortalTab.Requests,
                    onClick = { tab = PortalTab.Requests },
                    icon = { Icon(if (tab == PortalTab.Requests) Icons.AutoMirrored.Rounded.Assignment else Icons.AutoMirrored.Outlined.Assignment, contentDescription = null) },
                    label = { Text(PortalTab.Requests.label) },
                )
                NavigationBarItem(
                    selected = tab == PortalTab.Account,
                    onClick = { tab = PortalTab.Account },
                    icon = { Icon(if (tab == PortalTab.Account) Icons.Rounded.Person else Icons.Outlined.Person, contentDescription = null) },
                    label = { Text(PortalTab.Account.label) },
                )
            }
        },
    ) { padding ->
        if (state.loading && state.me == null) {
            FullScreenLoading(
                label = "Loading your gate…",
                modifier = Modifier
                    .consumeWindowInsets(padding)
                    .padding(padding),
            )
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.loadAll(initial = false) },
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .padding(padding),
        ) {
            when (tab) {
                PortalTab.Gate -> PortalGateTab(
                    state = state,
                    onSelectUnit = vm::selectUnit,
                    onBuzz = vm::buzz,
                    onReroll = vm::rerollCode,
                    onSharePdf = {
                        scope.launch {
                            vm.downloadInstructionsPdfBytes()
                                .onSuccess { (name, bytes) ->
                                    PdfShareHelper.sharePdfBytes(context, name, bytes)
                                        .onFailure { snackbar.showSnackbar(it.message ?: "Share failed") }
                                }
                                .onFailure { snackbar.showSnackbar(it.message ?: "Download failed") }
                        }
                    },
                    onCopyCode = { code ->
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("Gate Entry Code", code))
                        scope.launch { snackbar.showSnackbar("Entry code copied") }
                    },
                )

                PortalTab.Activity -> PortalActivityTab(
                    state = state,
                    serverUrl = container.sessionStorage.currentServerUrl(),
                    gatePhone = gatePhone,
                    okHttpClient = container.okHttpClient,
                    onReport = vm::reportSuspicious,
                )

                PortalTab.Requests -> PortalRequestsTab(
                    state = state,
                    onSubmit = vm::submitRequest,
                )

                PortalTab.Account -> AccountSettingsScreen(
                    api = container.api,
                    themeMode = themeMode,
                    onSelectTheme = onSelectTheme,
                    residentMode = true,
                    passkeyAuthenticator = container.passkeyAuthenticator,
                    onSwitchToStaff = onSwitchToStaff,
                    onProfileUpdated = { vm.loadAll(initial = false) },
                    onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    onSignOut = onSignOut,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortalGateTab(
    state: ResidentPortalUiState,
    onSelectUnit: (String) -> Unit,
    onBuzz: (Int, String) -> Unit,
    onReroll: () -> Unit,
    onSharePdf: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val me = state.me
    val haptic = LocalHapticFeedback.current
    val semantic = LocalDoorkingColors.current
    var revealCode by remember(me?.unit?.directoryCode) { mutableStateOf(false) }
    var revealSeconds by remember { mutableIntStateOf(0) }
    var confirmReroll by remember { mutableStateOf(false) }

    // 30-second auto-remask matching the web portal's privacy behavior.
    LaunchedEffect(revealCode) {
        if (!revealCode) {
            revealSeconds = 0
            return@LaunchedEffect
        }
        revealSeconds = 30
        while (revealSeconds > 0) {
            delay(1_000L)
            revealSeconds -= 1
        }
        revealCode = false
    }

    if (confirmReroll) {
        AlertDialog(
            onDismissRequest = { confirmReroll = false },
            title = { Text("Generate a new gate code?") },
            text = {
                Text("This replaces your 4-digit entry code with a new random code. Changes sync to the physical gate on the next scheduled send (or immediately if auto-send is enabled).")
            },
            confirmButton = {
                Button(onClick = { confirmReroll = false; onReroll() }) { Text("Generate new code") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReroll = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.error != null) {
            item(key = "error") { ErrorBanner(state.error) }
        }
        if (me == null) return@LazyColumn

        if (me.units.size > 1) {
            item(key = "units") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    me.units.forEach { u ->
                        FilterChip(
                            selected = u.directoryCode == me.unit.directoryCode,
                            onClick = { onSelectUnit(u.directoryCode) },
                            label = { Text("Unit ${u.directoryCode} · ${u.name}") },
                        )
                    }
                }
            }
        }

        if (me.portal.buzzEnabled && me.portal.buzzRelayLabels.isNotEmpty()) {
            items(
                items = me.portal.buzzRelayLabels,
                key = { "buzz-${it.relay}" },
            ) { entry ->
                val isBuzzing = state.buzzingRelay == entry.relay
                val isBusy = !state.status.available
                val isCooldown = !isBusy && state.status.cooldownSeconds > 0
                val enabled = !isBusy && !isCooldown && state.buzzingRelay == null

                val containerColor = when {
                    isBusy -> semantic.warning
                    isCooldown -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> semantic.success
                }
                val contentColor = when {
                    isBusy -> semantic.onWarning
                    isCooldown -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> semantic.onSuccess
                }
                val buttonLabel = when {
                    isBuzzing -> "Opening ${entry.name}…"
                    isBusy -> state.status.percent?.let { "${entry.name} · Busy $it%" }
                        ?: "${entry.name} · Busy"
                    isCooldown -> "${entry.name} · Wait ${state.status.cooldownSeconds}s"
                    else -> "Open ${entry.name}"
                }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBuzz(entry.relay, entry.name)
                    },
                    enabled = enabled,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = containerColor,
                        contentColor = contentColor,
                        disabledContainerColor = containerColor,
                        disabledContentColor = contentColor,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    if (isBuzzing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = contentColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(buttonLabel, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Icon(
                            imageVector = when {
                                isBusy -> Icons.Rounded.HourglassTop
                                isCooldown -> Icons.Rounded.Schedule
                                else -> Icons.Rounded.LockOpen
                            },
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(buttonLabel, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        item(key = "access") {
            SectionCard(
                title = "My Gate Access",
                tonal = true,
                trailing = {
                    if (me.reroll.pendingSend) {
                        StatusPill(text = "Sync pending", tone = PillTone.Warning)
                    }
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Entry code",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val display = when {
                            me.unit.entryCode.isBlank() -> "None assigned"
                            revealCode -> "#${me.unit.entryCode}"
                            else -> "#••••"
                        }
                        Text(
                            text = display,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        if (revealCode && revealSeconds > 0) {
                            Text(
                                text = "Hides automatically in ${revealSeconds}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (me.unit.entryCode.isNotBlank()) {
                        Row {
                            IconButton(onClick = { revealCode = !revealCode }) {
                                Icon(
                                    imageVector = if (revealCode) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (revealCode) "Hide entry code" else "Reveal entry code",
                                )
                            }
                            IconButton(onClick = { onCopyCode(me.unit.entryCode) }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy entry code")
                            }
                        }
                    }
                }

                if (me.portal.maxRerollsPerDay > 0) {
                    FilledTonalButton(
                        onClick = { confirmReroll = true },
                        enabled = !state.rerollBusy && me.reroll.remainingToday > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.rerollBusy) {
                                "Generating…"
                            } else {
                                "Get a new gate code (${me.reroll.remainingToday}/${me.reroll.maxPerDay} left today)"
                            },
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onSharePdf,
                    enabled = !state.pdfBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(if (state.pdfBusy) "Preparing PDF…" else "Share Instructions PDF")
                }
            }
        }

        if (me.unit.cards.isNotEmpty()) {
            item(key = "cards") {
                AccessCredentialsSection(
                    title = "Assigned remotes & cards",
                    credentials = me.unit.cards.map { it.number to it.device },
                    onCopyNumber = onCopyCode,
                    topLevel = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortalActivityTab(
    state: ResidentPortalUiState,
    serverUrl: String,
    gatePhone: String,
    okHttpClient: okhttp3.OkHttpClient,
    onReport: (TransactionRowDto, String) -> Unit,
) {
    var reporting by remember { mutableStateOf<TransactionRowDto?>(null) }
    var reportNote by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Pair<TransactionRowDto, List<MediaItemEntry>>?>(null) }
    val base = serverUrl.trimEnd('/')
    val unitCode = state.me?.unit?.directoryCode.orEmpty()

    if (viewing != null) {
        val (tx, items) = viewing!!
        MediaViewerDialog(
            title = tx.eventType.ifBlank { "Gate Event" },
            subtitle = "${tx.date} ${tx.time} · ${tx.access}".trim(),
            items = items,
            okHttpClient = okHttpClient,
            onDismiss = { viewing = null },
        )
    }

    if (reporting != null) {
        AlertDialog(
            onDismissRequest = { reporting = null; reportNote = "" },
            title = { Text("Report suspicious activity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${reporting!!.eventType} · ${reporting!!.date} ${reporting!!.time}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = reportNote,
                        onValueChange = { reportNote = it },
                        label = { Text("Optional details for management") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onReport(reporting!!, reportNote)
                    reporting = null
                    reportNote = ""
                }) { Text("Submit report") }
            },
            dismissButton = {
                TextButton(onClick = { reporting = null; reportNote = "" }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.activity.isEmpty()) {
            item(key = "empty") {
                BubbleCard {
                    Text("No recent activity", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Gate entries using your directory code, entry code, remotes, or Buzz Me In appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = state.activity,
                key = { index, it -> "$index-${it.source ?: "gate"}-${it.txKey ?: "${it.ts}-${it.raw}"}" },
            ) { _, row ->
                val accessTone = when {
                    row.access.contains("Deny", ignoreCase = true) -> PillTone.Error
                    row.access.contains("Admit", ignoreCase = true) -> PillTone.Success
                    else -> PillTone.Neutral
                }
                BubbleCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = row.eventType.ifBlank { "Gate Event" },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (row.access.isNotBlank()) StatusPill(row.access, tone = accessTone)
                                StatusPill("${row.date} ${row.time}".trim(), tone = PillTone.Neutral)
                                if (!row.number.isNullOrBlank()) StatusPill("#${row.number}", tone = PillTone.Primary)
                                if (row.reported == true) StatusPill("Reported", tone = PillTone.Warning)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.showSnapshots && row.camera && row.minute.isNotBlank() && unitCode.isNotBlank()) {
                                IconButton(onClick = {
                                    val q = "?code=$unitCode"
                                    val items = listOf(
                                        MediaItemEntry("Video", "$base/api/portal/gates/$gatePhone/snapshots/${row.minute}/ch1.mp4$q", isVideo = true),
                                        MediaItemEntry("Photo", "$base/api/portal/gates/$gatePhone/snapshots/${row.minute}/ch1.jpg$q", isVideo = false),
                                    )
                                    viewing = row to items
                                }) {
                                    Icon(Icons.Rounded.PhotoCamera, contentDescription = "View gate photo or video")
                                }
                            }
                            if (row.reported != true && !row.txKey.isNullOrBlank()) {
                                IconButton(onClick = { reporting = row }) {
                                    Icon(Icons.Rounded.Flag, contentDescription = "Report suspicious")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortalRequestsTab(
    state: ResidentPortalUiState,
    onSubmit: (kind: String, phone: String, note: String, onDone: () -> Unit) -> Unit,
) {
    val kinds = listOf(
        "phone" to "Update call-box phone number",
        "hidden" to "Change directory visibility",
        "cardIssue" to "Lost / damaged remote or card",
        "other" to "Other request",
    )
    var kindExpanded by remember { mutableStateOf(false) }
    var selectedKind by remember { mutableStateOf(kinds.first().first) }
    var phone by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.me?.portal?.allowRequests == true) {
            item(key = "form") {
                SectionCard(title = "Request a Change", tonal = true) {
                    ExposedDropdownMenuBox(
                        expanded = kindExpanded,
                        onExpandedChange = { kindExpanded = !kindExpanded },
                    ) {
                        OutlinedTextField(
                            value = kinds.firstOrNull { it.first == selectedKind }?.second ?: selectedKind,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Request type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kindExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = kindExpanded, onDismissRequest = { kindExpanded = false }) {
                            kinds.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { selectedKind = id; kindExpanded = false },
                                )
                            }
                        }
                    }
                    if (selectedKind == "phone") {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("New phone number") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Details") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !state.requestBusy && (selectedKind != "phone" || phone.isNotBlank()) && (selectedKind == "phone" || note.isNotBlank()),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onSubmit(selectedKind, phone, note) {
                                phone = ""
                                note = ""
                            }
                        },
                    ) {
                        Text(if (state.requestBusy) "Sending…" else "Send request to management")
                    }
                }
            }
        }

        if (state.requests.isEmpty()) {
            item(key = "reqs-empty") {
                BubbleCard {
                    Text("No requests yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (state.me?.portal?.allowRequests == true) {
                            "Submitted change requests and management responses appear here."
                        } else {
                            "Self-service requests are currently turned off for this gate."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(items = state.requests, key = { it.id }) { req ->
            val tone = when (req.status) {
                "approved" -> PillTone.Success
                "rejected" -> PillTone.Error
                else -> PillTone.Warning
            }
            BubbleCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = req.kind.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleSmall)
                    StatusPill(text = req.status.replaceFirstChar(Char::uppercase), tone = tone)
                }
                if (req.note.isNotBlank()) {
                    Text(text = req.note, style = MaterialTheme.typography.bodyMedium)
                }
                if (!req.reviewNote.isNullOrBlank()) {
                    Text(
                        text = "Management: ${req.reviewNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

package com.doorking.gate.feature.staff

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Fence
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doorking.gate.AppContainer
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.media.LiveCameraDialog
import com.doorking.gate.core.media.PdfShareHelper
import com.doorking.gate.core.network.ResidentRowDto
import com.doorking.gate.core.network.SessionResponse
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.components.rememberExpandedModalSheetState
import com.doorking.gate.feature.admin.AccountSettingsScreen
import com.doorking.gate.feature.admin.BoardSection
import com.doorking.gate.feature.admin.BoardSettingsScreen
import com.doorking.gate.feature.admin.HistoryScreen
import com.doorking.gate.feature.admin.InsightsScreen
import com.doorking.gate.feature.admin.SchedulesScreen
import com.doorking.gate.feature.admin.SettingsDraftActions
import com.doorking.gate.feature.admin.SystemSettingsScreen
import kotlinx.coroutines.launch

private enum class StaffTab(val label: String) {
    Residents("Residents"),
    Vendors("Vendors"),
    Transactions("Activity"),
    More("More"),
}

private sealed interface MoreSubScreen {
    data object Insights : MoreSubScreen
    data object History : MoreSubScreen
    data object Schedules : MoreSubScreen
    data class Board(val section: BoardSection) : MoreSubScreen
    data object SystemSettings : MoreSubScreen
    data object Account : MoreSubScreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffGateShell(
    container: AppContainer,
    gatePhone: String,
    session: SessionResponse?,
    themeMode: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    onSwitchGate: () -> Unit,
    onOpenResidentPortal: (() -> Unit)?,
    onSignOut: () -> Unit,
) {
    val vm: StaffGateViewModel = viewModel(
        key = "staff-$gatePhone",
        factory = StaffGateViewModel.factory(container, gatePhone),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(StaffTab.Residents) }
    var subScreen by remember { mutableStateOf<MoreSubScreen?>(null) }
    var draftActions by remember { mutableStateOf<SettingsDraftActions?>(null) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showGateSheet by remember { mutableStateOf(false) }
    var showRelaysSheet by remember { mutableStateOf(false) }
    var showJobDialog by remember { mutableStateOf(false) }
    var showActivityExportDialog by remember { mutableStateOf(false) }
    var showLiveCameraDialog by remember { mutableStateOf(false) }
    var editingResident by remember { mutableStateOf<ResidentRowDto?>(null) }
    var showAddResident by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val role = session?.gate?.role.orEmpty().lowercase()
    val isAdmin = role == "admin"
    val readOnly = role == "readonly"
    val canEdit = !readOnly
    val unsavedDraft = draftActions?.unsavedCount ?: 0

    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    // Prevent silent loss of unsaved Board Settings edits when leaving the screen.
    fun navigateGuarded(action: () -> Unit) {
        if (unsavedDraft > 0) {
            pendingNavigation = action
        } else {
            action()
        }
    }

    BackHandler(enabled = subScreen != null) {
        navigateGuarded { subScreen = null }
    }

    if (pendingNavigation != null) {
        AlertDialog(
            onDismissRequest = { pendingNavigation = null },
            title = { Text("Discard $unsavedDraft unsaved change(s)?") },
            text = { Text("Leaving Board Settings will discard your unsaved edits. Open the Gate tab first if you want to save them to the working copy.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        draftActions?.discard()
                        val go = pendingNavigation
                        pendingNavigation = null
                        go?.invoke()
                    },
                ) { Text("Discard & Leave") }
            },
            dismissButton = {
                TextButton(onClick = { pendingNavigation = null }) { Text("Keep editing") }
            },
        )
    }

    LaunchedEffect(state.bannerMessage) {
        val msg = state.bannerMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.clearBanner()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshJob()
    }

    LaunchedEffect(showGateSheet, showJobDialog) {
        if (showGateSheet || showJobDialog) {
            vm.refreshJob()
        }
    }

    if (state.sendPreview != null) {
        SendPreviewDialog(
            preview = state.sendPreview!!,
            onConfirmSend = {
                vm.startSend()
                showJobDialog = true
            },
            onDismiss = vm::dismissSendPreview,
        )
    }

    if (showJobDialog && state.job != null) {
        JobProgressDialog(
            job = state.job!!,
            onRefresh = vm::refreshJob,
            onDismiss = { showJobDialog = false },
        )
    }

    if (showLiveCameraDialog && state.cameras != null && state.cameras!!.channels.isNotEmpty()) {
        LiveCameraDialog(
            serverUrl = vm.serverUrl(),
            gatePhone = gatePhone,
            channels = state.cameras!!.channels,
            okHttpClient = container.okHttpClient,
            onDismiss = { showLiveCameraDialog = false },
        )
    }

    if (showActivityExportDialog) {
        val hasCamera = state.cameras != null
        val isVideoMode = state.cameras?.mode == "video"
        val cameraRunning = state.snapRun?.state == "running"
        AlertDialog(
            onDismissRequest = { showActivityExportDialog = false },
            title = { Text("Export or download") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = state.transactions.isNotEmpty(),
                        onClick = {
                            showActivityExportDialog = false
                            val csv = PdfShareHelper.buildTransactionsCsv(state.transactions)
                            scope.launch {
                                PdfShareHelper.shareCsvText(
                                    context = context,
                                    filename = "transactions-$gatePhone.csv",
                                    csvContent = csv,
                                    subject = "Gate Activity Export",
                                ).onFailure { err ->
                                    snackbar.showSnackbar(err.message ?: "CSV export failed")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Export Activity CSV") }
                    if (hasCamera) {
                        Button(
                            enabled = !cameraRunning,
                            onClick = {
                                showActivityExportDialog = false
                                vm.requestAllSnapshots()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    cameraRunning -> "Camera download already running"
                                    isVideoMode -> "Download Camera Videos"
                                    else -> "Download Camera Snapshots"
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActivityExportDialog = false }) { Text("Close") }
            },
        )
    }

    if (showGateSheet) {
        val sheetState = rememberExpandedModalSheetState()
        ModalBottomSheet(onDismissRequest = { showGateSheet = false }, sheetState = sheetState) {
            GateControlSheetContent(
                state = state,
                isAdmin = isAdmin,
                readOnly = readOnly,
                draftActions = draftActions,
                onPreviewSend = {
                    showGateSheet = false
                    vm.loadSendPreview()
                },
                onResetWorkingCopy = vm::resetWorkingCopy,
                onPull = { what ->
                    vm.startPull(what)
                    showJobDialog = true
                },
                onOpenLiveRelays = { showRelaysSheet = true },
                onShowProgress = { showJobDialog = true },
                onClose = { showGateSheet = false },
            )
        }
    }

    if (showRelaysSheet) {
        val sheetState = rememberExpandedModalSheetState()
        ModalBottomSheet(onDismissRequest = { showRelaysSheet = false }, sheetState = sheetState) {
            LiveRelaysSheetContent(
                state = state,
                isAdmin = isAdmin,
                onRefresh = vm::refreshRelays,
                onToggleRelay = vm::toggleRelay,
                onCancelTimer = vm::cancelRelayTimer,
                onClose = { showRelaysSheet = false },
            )
        }
    }

    val gateTitle = session?.gate?.siteName?.ifBlank { gatePhone } ?: gatePhone
    val running = state.job?.state == "running"
    val hasEdits = state.residentsReply?.cache?.edited == true
    val hasOwnPortalUnit = session?.hasPortalUnit == true ||
        (state.prefs.residentPortal?.enabled == true &&
            state.residentsReply?.rows?.any { row ->
                row.portalEnabled == true && row.portalUsername.equals(session?.user, ignoreCase = true)
            } == true)

    val topTitle = when (subScreen) {
        null -> gateTitle
        MoreSubScreen.Insights -> "Access Insights"
        MoreSubScreen.History -> "History"
        MoreSubScreen.Schedules -> "Scheduled Transfers"
        is MoreSubScreen.Board -> "Board Settings"
        MoreSubScreen.SystemSettings -> "System Settings"
        MoreSubScreen.Account -> "User Settings"
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Column {
                        Text(topTitle)
                        val sub = when {
                            subScreen != null -> gateTitle
                            running -> "Connection running…"
                            hasEdits -> "Unsent working-copy changes"
                            else -> "Matches gate"
                        }
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (subScreen != null) {
                        IconButton(onClick = { navigateGuarded { subScreen = null } }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (subScreen == null) {
                        when (tab) {
                            StaffTab.Residents, StaffTab.Vendors -> {
                                val isVendor = tab == StaffTab.Vendors
                                val rowsForTab = state.residentsReply?.rows.orEmpty().filter {
                                    isVisibleDirectoryRow(it) && it.type.equals("Vendor", ignoreCase = true) == isVendor
                                }
                                IconButton(
                                    enabled = rowsForTab.isNotEmpty(),
                                    onClick = {
                                        val csv = PdfShareHelper.buildDirectoryCsv(rowsForTab, isVendor = isVendor)
                                        val kindName = if (isVendor) "vendors" else "residents"
                                        scope.launch {
                                            PdfShareHelper.shareCsvText(
                                                context = context,
                                                filename = "$kindName-$gatePhone.csv",
                                                csvContent = csv,
                                                subject = if (isVendor) "Vendors Export" else "Residents Export",
                                            ).onFailure { err ->
                                                snackbar.showSnackbar(err.message ?: "CSV export failed")
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.Download,
                                        contentDescription = if (isVendor) "Export vendors CSV" else "Export residents CSV",
                                    )
                                }
                            }
                            StaffTab.Transactions -> {
                                IconButton(onClick = { showActivityExportDialog = true }) {
                                    Icon(Icons.Rounded.Download, contentDescription = "Export CSV or download cameras")
                                }
                            }
                            StaffTab.More -> {
                                if (state.cameras != null && state.cameras!!.channels.isNotEmpty()) {
                                    IconButton(onClick = { showLiveCameraDialog = true }) {
                                        Icon(Icons.Rounded.Videocam, contentDescription = "Live camera view")
                                    }
                                }
                            }
                        }
                        if (hasOwnPortalUnit && onOpenResidentPortal != null) {
                            IconButton(onClick = { navigateGuarded { onOpenResidentPortal() } }) {
                                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Switch to resident portal")
                            }
                        }
                        IconButton(onClick = { vm.loadAll(initial = false) }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                        }
                    } else if (hasOwnPortalUnit && onOpenResidentPortal != null) {
                        IconButton(onClick = { navigateGuarded { onOpenResidentPortal() } }) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = "Switch to resident portal")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (subScreen == null && canEdit && (tab == StaffTab.Residents || tab == StaffTab.Vendors)) {
                val label = if (tab == StaffTab.Vendors) "Add vendor" else "Add resident"
                ExtendedFloatingActionButton(
                    onClick = { showAddResident = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(label) },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                val resSel = subScreen == null && tab == StaffTab.Residents
                NavigationBarItem(
                    selected = resSel,
                    onClick = { navigateGuarded { subScreen = null; tab = StaffTab.Residents } },
                    icon = { Icon(if (resSel) Icons.Rounded.People else Icons.Outlined.People, contentDescription = null) },
                    label = { Text("Residents") },
                )
                val venSel = subScreen == null && tab == StaffTab.Vendors
                NavigationBarItem(
                    selected = venSel,
                    onClick = { navigateGuarded { subScreen = null; tab = StaffTab.Vendors } },
                    icon = { Icon(if (venSel) Icons.Rounded.Work else Icons.Outlined.WorkOutline, contentDescription = null) },
                    label = { Text("Vendors") },
                )
                val txSel = subScreen == null && tab == StaffTab.Transactions
                NavigationBarItem(
                    selected = txSel,
                    onClick = { navigateGuarded { subScreen = null; tab = StaffTab.Transactions } },
                    icon = { Icon(if (txSel) Icons.AutoMirrored.Rounded.ListAlt else Icons.AutoMirrored.Outlined.ListAlt, contentDescription = null) },
                    label = { Text("Activity") },
                )
                NavigationBarItem(
                    selected = showGateSheet,
                    onClick = { showGateSheet = true },
                    icon = {
                        BadgedBox(
                            badge = {
                                when {
                                    unsavedDraft > 0 -> Badge { Text("$unsavedDraft") }
                                    running || hasEdits -> Badge()
                                }
                            },
                        ) {
                            Icon(if (showGateSheet) Icons.Rounded.Fence else Icons.Outlined.Fence, contentDescription = null)
                        }
                    },
                    label = { Text("Gate") },
                )
                val moreSel = subScreen != null || tab == StaffTab.More
                NavigationBarItem(
                    selected = moreSel,
                    onClick = { navigateGuarded { subScreen = null; tab = StaffTab.More } },
                    icon = { Icon(if (moreSel) Icons.Rounded.MoreHoriz else Icons.Outlined.MoreHoriz, contentDescription = null) },
                    label = { Text("More") },
                )
            }
        },
    ) { padding ->
        if (subScreen != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .padding(padding)
                    .imePadding(),
            ) {
                when (val sub = subScreen!!) {
                    MoreSubScreen.Insights -> InsightsScreen(gatePhone = gatePhone, api = container.api)
                    MoreSubScreen.History -> HistoryScreen(
                        gatePhone = gatePhone,
                        api = container.api,
                        readOnly = readOnly,
                        onMessage = ::showMessage,
                        onMutated = { vm.loadAll(initial = false) },
                    )
                    MoreSubScreen.Schedules -> SchedulesScreen(
                        gatePhone = gatePhone,
                        api = container.api,
                        isAdmin = isAdmin,
                        onMessage = ::showMessage,
                    )
                    is MoreSubScreen.Board -> BoardSettingsScreen(
                        gatePhone = gatePhone,
                        api = container.api,
                        initialSection = sub.section,
                        readOnly = readOnly,
                        relayLabels = state.prefs.relayLabels,
                        tracker = state.prefs.tracker,
                        onDraftChange = { draftActions = it },
                        onMessage = ::showMessage,
                        onSavedToWorkingCopy = { vm.loadAll(initial = false) },
                    )
                    MoreSubScreen.SystemSettings -> SystemSettingsScreen(
                        gatePhone = gatePhone,
                        api = container.api,
                        isAdmin = isAdmin,
                        meUsername = session?.user.orEmpty(),
                        onMessage = ::showMessage,
                        onDeletedGate = onSwitchGate,
                        onReloadGate = { vm.loadAll(initial = false) },
                    )
                    MoreSubScreen.Account -> AccountSettingsScreen(
                        api = container.api,
                        themeMode = themeMode,
                        onSelectTheme = onSelectTheme,
                        passkeyAuthenticator = container.passkeyAuthenticator,
                        sessionStorage = container.sessionStorage,
                        onMessage = ::showMessage,
                        onSignOut = onSignOut,
                    )
                }
            }
            return@Scaffold
        }

        if (state.loading && state.residentsReply == null && state.transactions.isEmpty()) {
            FullScreenLoading(
                label = "Loading gate data…",
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
                StaffTab.Residents, StaffTab.Vendors -> DirectoryTab(
                    state = state,
                    isVendor = tab == StaffTab.Vendors,
                    canEdit = canEdit,
                    editingTarget = editingResident,
                    showAddSheet = showAddResident,
                    serverUrl = vm.serverUrl(),
                    gatePhone = gatePhone,
                    okHttpClient = container.okHttpClient,
                    onRequestSnapshot = vm::requestSnapshot,
                    onRequestEdit = { editingResident = it },
                    onDismissAdd = { showAddResident = false },
                    onSave = { existing, code, name, area, phone, entryCode, note, onDone ->
                        vm.saveResident(existing, tab == StaffTab.Vendors, code, name, area, phone, entryCode, note, onDone)
                    },
                    onDelete = vm::deleteResident,
                    onShareWelcomePdf = { row ->
                        scope.launch {
                            vm.downloadWelcomePdf(row)
                                .onSuccess { (filename, bytes) ->
                                    PdfShareHelper.sharePdfBytes(context, filename, bytes)
                                        .onFailure { snackbar.showSnackbar(it.message ?: "Share failed") }
                                }
                                .onFailure { snackbar.showSnackbar(it.message ?: "PDF failed") }
                        }
                    },
                    onDecideConflict = vm::decideConflict,
                )

                StaffTab.Transactions -> TransactionsTab(
                    state = state,
                    serverUrl = vm.serverUrl(),
                    gatePhone = gatePhone,
                    okHttpClient = container.okHttpClient,
                    onLoadOlder = vm::loadOlderTransactions,
                    onRequestSnapshot = vm::requestSnapshot,
                    onStopSnapshots = vm::stopSnapshots,
                )

                StaffTab.More -> {
                    StaffMoreTab(
                        session = session,
                        onNavigate = { subScreen = it },
                        onOpenLiveRelays = { showRelaysSheet = true },
                        onSwitchGate = onSwitchGate,
                        onOpenResidentPortal = if (hasOwnPortalUnit) onOpenResidentPortal else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun StaffMoreTab(
    session: SessionResponse?,
    onNavigate: (MoreSubScreen) -> Unit,
    onOpenLiveRelays: () -> Unit,
    onSwitchGate: () -> Unit,
    onOpenResidentPortal: (() -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "admin") {
            SectionCard(
                title = "Management & Monitoring",
                tonal = true,
                trailing = {
                    val role = session?.gate?.role.orEmpty().replaceFirstChar(Char::uppercase)
                    if (role.isNotBlank()) StatusPill(text = role, tone = PillTone.Primary)
                },
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    Column {
                        MoreListItem("Access Insights", "Dormant credentials, unknown cards & traffic", Icons.Rounded.Analytics) {
                            onNavigate(MoreSubScreen.Insights)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreListItem("History & Pull Archive", "Events, Revert, point-in-time restore", Icons.Rounded.History) {
                            onNavigate(MoreSubScreen.History)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreListItem("Scheduled Transfers", "Automatic pulls, clips & sends", Icons.Rounded.Schedule) {
                            onNavigate(MoreSubScreen.Schedules)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreListItem("Board Settings", "Facility codes, Holidays, Time zones, Hold open, Display", Icons.Rounded.Tune) {
                            onNavigate(MoreSubScreen.Board(BoardSection.Facility))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreListItem("System Settings", "DKS login, Users, Portal, Audit, API keys, Webhooks", Icons.Rounded.Settings) {
                            onNavigate(MoreSubScreen.SystemSettings)
                        }
                    }
                }
            }
        }

        item(key = "quick") {
            SectionCard(title = "Gate & Account", tonal = true) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    Column {
                        MoreListItem("Live Relays", "Hold open, timed release & status", Icons.Rounded.LockOpen, onOpenLiveRelays)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreListItem("Switch Gate", "Return to all gates", Icons.Rounded.SwapHoriz, onSwitchGate)
                        if (onOpenResidentPortal != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            MoreListItem("Resident Portal", "Switch to your unit's resident view", Icons.Rounded.Fence, onOpenResidentPortal)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreListItem("User Settings", session?.user.orEmpty(), Icons.Rounded.Person) {
                            onNavigate(MoreSubScreen.Account)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreListItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

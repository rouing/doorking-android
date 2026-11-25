package com.doorking.gate.feature.gates

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doorking.gate.AppContainer
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.auth.normalizeServerUrl
import com.doorking.gate.core.network.GateConfigDto
import com.doorking.gate.core.network.GateInfoDto
import com.doorking.gate.core.network.JobDto
import com.doorking.gate.core.network.SessionResponse
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.PasswordOutlinedField
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.SectionCard
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.components.rememberExpandedModalSheetState
import com.doorking.gate.feature.auth.isValidExternalBrowserUrl
import com.doorking.gate.feature.auth.launchPinnedCustomTab

private val MEMORY_SIZE_OPTIONS = listOf("1000", "2000", "3000")
private val AREA_CODE_OPTIONS = listOf("10 Area Codes", "255 Area Codes")
private val MODEL_NUMBER_OPTIONS = listOf("1833/1835/1837/1838", "1834")
private val YES_NO_OPTIONS = listOf("N", "Y")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatePickerScreen(
    container: AppContainer,
    session: SessionResponse?,
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    onGateOpened: (GateInfoDto) -> Unit,
    onSignOut: () -> Unit,
) {
    val factory = remember(container) { GatePickerViewModel.factory(container) }
    val vm: GatePickerViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }
    var privacyError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    ReportDrawnWhen { !state.loading }

    LifecycleStartEffect(vm) {
        vm.setActive(true)
        onStopOrDispose {
            vm.setActive(false)
        }
    }

    val canAddGate = session?.isResidentOnly != true

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    if (showAddSheet) {
        val sheetState = rememberExpandedModalSheetState()
        ModalBottomSheet(
            onDismissRequest = { if (!state.addBusy) showAddSheet = false },
            sheetState = sheetState,
        ) {
            AddGateSheetContent(
                defaults = session?.defaults ?: GateConfigDto(
                    siteName = "Site",
                    memorySize = "3000",
                    areaCodes = "255 Area Codes",
                    modelNumber = "1833/1835/1837/1838",
                    antiPassBack = "N",
                    interface1816 = "N",
                ),
                busy = state.addBusy,
                error = state.addError,
                onSubmit = { phone, master, user, pass, cfg ->
                    vm.startAddGate(phone, master, user, pass, cfg) {
                        showAddSheet = false
                    }
                },
                onCancel = { showAddSheet = false },
            )
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Column {
                        Text("Your Gates")
                        val username = session?.user.orEmpty()
                        if (username.isNotBlank()) {
                            Text(
                                text = username,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onCycleTheme) {
                        Icon(
                            imageVector = Icons.Rounded.Brightness6,
                            contentDescription = "Theme: ${themeMode.name.lowercase()}",
                        )
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
        floatingActionButton = {
            if (canAddGate) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add gate") },
                )
            }
        },
    ) { padding ->
        if (state.loading) {
            FullScreenLoading(
                label = "Loading gates…",
                modifier = Modifier
                    .consumeWindowInsets(padding)
                    .padding(padding),
            )
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.loadGates(initial = false) },
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.error != null) {
                    item(key = "error", contentType = "error") { ErrorBanner(state.error!!, onRetry = { vm.loadGates(initial = false) }) }
                }
                if (privacyError != null) {
                    item(key = "privacyError", contentType = "error") { ErrorBanner(privacyError!!) }
                }
                if (state.addError != null) {
                    item(key = "addError", contentType = "error") { ErrorBanner(state.addError!!) }
                }
                if (state.addJob != null) {
                    item(key = "addJob", contentType = "add_job") {
                        AddGateProgressCard(
                            job = state.addJob!!,
                            onDismiss = vm::dismissAddJob,
                        )
                    }
                }
                if (state.gates.isEmpty() && state.error == null) {
                    item(key = "empty", contentType = "empty") {
                        BubbleCard {
                            Text("No gates yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (canAddGate) {
                                    "Tap + to add a DoorKing gate using its phone number, master code, and DKS Cloud login."
                                } else {
                                    "Ask your property manager to enable Resident Portal access for your unit."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(items = state.gates, key = { it.phoneNumber }, contentType = { "gate_row" }) { gate ->
                        GateRowCard(
                            gate = gate,
                            opening = state.openingPhone == gate.phoneNumber,
                            enabled = state.openingPhone == null,
                            onClick = { vm.openGate(gate, onGateOpened) },
                        )
                    }
                }
                item(key = "privacy_footer", contentType = "footer") {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(
                            onClick = {
                                privacyError = null
                                val serverUrl = normalizeServerUrl(container.sessionStorage.currentServerUrl())
                                val privacyUrl = "${serverUrl.trimEnd('/')}/privacy"
                                if (!isValidExternalBrowserUrl(privacyUrl)) {
                                    privacyError = "Privacy Policy requires an HTTPS server URL."
                                } else {
                                    runCatching { launchPinnedCustomTab(context, privacyUrl) }
                                        .onFailure { err -> privacyError = err.message ?: "Could not open Privacy Policy" }
                                }
                            },
                        ) {
                            Text("Privacy Policy")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GateRowCard(
    gate: GateInfoDto,
    opening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val rolePill = when (gate.role.lowercase()) {
        "admin" -> "Admin" to PillTone.Primary
        "editor" -> "Editor" to PillTone.Warning
        "resident" -> "Resident" to PillTone.Success
        else -> "Read only" to PillTone.Neutral
    }
    BubbleCard(onClick = if (enabled) onClick else null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Fence,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = gate.siteName.ifBlank { gate.phoneNumber },
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(text = formatUsPhone(gate.phoneNumber), tone = PillTone.Neutral)
                    StatusPill(text = rolePill.first, tone = rolePill.second)
                }
            }
            if (opening) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Open gate",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddGateProgressCard(
    job: JobDto,
    onDismiss: () -> Unit,
) {
    val tone = when (job.state) {
        "running" -> PillTone.Primary
        "done" -> PillTone.Success
        "failed" -> PillTone.Error
        else -> PillTone.Neutral
    }
    SectionCard(
        title = "Adding gate ${job.phoneNumber.orEmpty()}",
        trailing = { StatusPill(text = job.state.uppercase(), tone = tone) },
    ) {
        if (!job.error.isNullOrBlank()) {
            ErrorBanner(job.error)
        }
        if (job.log.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = job.log.takeLast(8).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (job.state != "running") {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Dismiss")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGateSheetContent(
    defaults: GateConfigDto,
    busy: Boolean,
    error: String?,
    onSubmit: (phone: String, masterCode: String, dksUser: String, dksPassword: String, config: GateConfigDto) -> Unit,
    onCancel: () -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var masterCode by remember { mutableStateOf("") }
    var dksUser by remember { mutableStateOf("") }
    var dksPassword by remember { mutableStateOf("") }
    var siteName by remember { mutableStateOf(defaults.siteName) }
    var memorySize by remember { mutableStateOf(defaults.memorySize.ifBlank { "3000" }) }
    var areaCodes by remember { mutableStateOf(defaults.areaCodes.ifBlank { "255 Area Codes" }) }
    var modelNumber by remember { mutableStateOf(defaults.modelNumber.ifBlank { "1833/1835/1837/1838" }) }
    var antiPassBack by remember { mutableStateOf(defaults.antiPassBack.ifBlank { "N" }) }
    var interface1816 by remember { mutableStateOf(defaults.interface1816.ifBlank { "N" }) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add a gate", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Connects via DKS Cloud and runs an initial residents pull before saving the gate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) ErrorBanner(error)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Gate phone") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1.4f),
            )
            PasswordOutlinedField(
                value = masterCode,
                onValueChange = { masterCode = it },
                label = "Master code",
                digitsOnly = true,
                maxLength = 4,
                showVisibilityToggle = false,
                modifier = Modifier.weight(1f),
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = dksUser,
                onValueChange = { dksUser = it },
                label = { Text("DKS user") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            PasswordOutlinedField(
                value = dksPassword,
                onValueChange = { dksPassword = it },
                label = "DKS password",
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = siteName,
            onValueChange = { siteName = it },
            label = { Text("Site name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactDropdown(
                label = "Memory",
                value = memorySize,
                options = MEMORY_SIZE_OPTIONS,
                onSelect = { memorySize = it },
                modifier = Modifier.weight(1f),
            )
            CompactDropdown(
                label = "Area codes",
                value = areaCodes,
                options = AREA_CODE_OPTIONS,
                onSelect = { areaCodes = it },
                modifier = Modifier.weight(1.2f),
            )
        }

        CompactDropdown(
            label = "Model",
            value = modelNumber,
            options = MODEL_NUMBER_OPTIONS,
            onSelect = { modelNumber = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactDropdown(
                label = "Anti-passback",
                value = antiPassBack,
                options = YES_NO_OPTIONS,
                onSelect = { antiPassBack = it },
                modifier = Modifier.weight(1f),
            )
            CompactDropdown(
                label = "Interface 1816/1820",
                value = interface1816,
                options = YES_NO_OPTIONS,
                onSelect = { interface1816 = it },
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel")
            }
            Button(
                enabled = !busy && phone.isNotBlank() && masterCode.length == 4 && dksUser.isNotBlank() && dksPassword.isNotBlank(),
                modifier = Modifier.weight(1.4f),
                onClick = {
                    onSubmit(
                        phone,
                        masterCode,
                        dksUser,
                        dksPassword,
                        GateConfigDto(
                            siteName = siteName.trim(),
                            memorySize = memorySize,
                            areaCodes = areaCodes,
                            modelNumber = modelNumber,
                            antiPassBack = antiPassBack,
                            interface1816 = interface1816,
                        ),
                    )
                },
            ) {
                Text(if (busy) "Starting…" else "Add & Pull")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

fun formatUsPhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return if (digits.length == 10) {
        "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
    } else {
        raw
    }
}

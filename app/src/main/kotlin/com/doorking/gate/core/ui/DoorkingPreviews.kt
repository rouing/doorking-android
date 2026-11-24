package com.doorking.gate.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Fence
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.components.TimePickerField
import com.doorking.gate.core.network.BuzzRelayLabelDto
import com.doorking.gate.core.network.CameraChannelDto
import com.doorking.gate.core.network.CameraSettingsDto
import com.doorking.gate.core.network.DeviceRangeDto
import com.doorking.gate.core.network.GatePrefsDto
import com.doorking.gate.core.network.PortalCapabilitiesDto
import com.doorking.gate.core.network.PortalCardDto
import com.doorking.gate.core.network.PortalGateDto
import com.doorking.gate.core.network.PortalMeReply
import com.doorking.gate.core.network.PortalRerollStatusDto
import com.doorking.gate.core.network.PortalStatusReply
import com.doorking.gate.core.network.PortalUnitDto
import com.doorking.gate.core.network.PortalUnitSummaryDto
import com.doorking.gate.core.network.RelayDto
import com.doorking.gate.core.network.RelayTimerDto
import com.doorking.gate.core.network.ResidentRowDto
import com.doorking.gate.core.network.ResidentsReply
import com.doorking.gate.core.network.SessionResponse
import com.doorking.gate.core.network.SnapshotMinuteDto
import com.doorking.gate.core.network.SsoProviderInfo
import com.doorking.gate.core.network.TransactionRowDto
import com.doorking.gate.core.network.WorkingCopyCacheDto
import com.doorking.gate.core.ui.theme.DoorkingTheme
import com.doorking.gate.feature.auth.SignInScreen
import com.doorking.gate.feature.portal.PortalGateTab
import com.doorking.gate.feature.portal.ResidentPortalUiState
import com.doorking.gate.feature.staff.DirectoryTab
import com.doorking.gate.feature.staff.GateControlSheetContent
import com.doorking.gate.feature.staff.LiveRelaysSheetContent
import com.doorking.gate.feature.staff.StaffGateUiState
import com.doorking.gate.feature.staff.TransactionsTab
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

private val sampleStaffState = StaffGateUiState(
    loading = false,
    residentsReply = ResidentsReply(
        rows = listOf(
            ResidentRowDto(
                directoryCode = "101",
                name = "Jordan Rivera",
                type = "Resident",
                areaCode = "214",
                phone = "5550142",
                entryCode = "4829",
                cards = listOf("10421", "10422"),
                portalEnabled = true,
                note = "Main building north unit",
            ),
            ResidentRowDto(
                directoryCode = "102",
                name = "Samira Patel",
                type = "Resident",
                areaCode = "214",
                phone = "5550188",
                entryCode = "7391",
                cards = listOf("10455"),
                portalEnabled = true,
                portalInvited = true,
            ),
            ResidentRowDto(
                directoryCode = "104",
                name = "Alex Chen",
                type = "Resident",
                areaCode = "469",
                phone = "5550193",
                entryCode = "6150",
                hidden = true,
            ),
            ResidentRowDto(
                directoryCode = "901",
                name = "Metro Waste",
                type = "Vendor",
                hidden = true,
                entryCode = "8142",
                securityLevel = JsonPrimitive(2),
                note = "Tue/Fri morning pickup",
            ),
            ResidentRowDto(
                directoryCode = "902",
                name = "ClearView Pool",
                type = "Vendor",
                hidden = true,
                entryCode = "5294",
                cards = listOf("10810"),
                securityLevel = JsonPrimitive(1),
            ),
            ResidentRowDto(
                directoryCode = "905",
                name = "Parcel Courier",
                type = "Vendor",
                hidden = true,
                entryCode = "3719",
                securityLevel = JsonPrimitive(1),
            ),
        ),
        cache = WorkingCopyCacheDto(exists = true, edited = true),
    ),
    transactions = listOf(
        TransactionRowDto(
            ts = 1700000300000L,
            minute = "20261122-1432",
            date = "11/22",
            time = "2:32 PM",
            eventType = "Entry Code",
            access = "Admit",
            name = "Jordan Rivera",
            directoryCode = "101",
            relay = "0",
            camera = true,
        ),
        TransactionRowDto(
            ts = 1700000200000L,
            minute = "20261122-1415",
            date = "11/22",
            time = "2:15 PM",
            eventType = "Portal Open",
            access = "Admit",
            name = "Samira Patel",
            directoryCode = "102",
            relay = "0",
            camera = true,
        ),
        TransactionRowDto(
            ts = 1700000100000L,
            minute = "20261122-1350",
            date = "11/22",
            time = "1:50 PM",
            eventType = "Card",
            access = "Deny (Unknown)",
            name = "Card #55912",
            relay = "0",
            camera = true,
        ),
    ),
    prefs = GatePrefsDto(
        relayLabels = mapOf("0" to "Main Gate", "1" to "Exit Gate", "2" to "Walk Gate"),
        deviceAssociation = listOf(
            DeviceRangeDto("Clickers", "10000", "19999"),
            DeviceRangeDto("Cards", "00001", "09999"),
        ),
    ),
    cameras = CameraSettingsDto(enabled = true, mode = "video", channels = listOf(CameraChannelDto(1, "Entry"))),
    snapshots = mapOf(
        "20261122-1432" to SnapshotMinuteDto(status = "done", files = listOf("ch1.mp4")),
        "20261122-1415" to SnapshotMinuteDto(status = "done", files = listOf("ch1.mp4")),
    ),
    relays = listOf(
        RelayDto(relay = 0, code = "1", state = "Active"),
        RelayDto(relay = 1, code = "0", state = "Inactive"),
        RelayDto(relay = 2, code = "0", state = "Inactive"),
    ),
    relayTimers = listOf(
        RelayTimerDto(phone = "2145550100", relay = 0, releaseAt = "2026-11-22T15:00:00Z"),
    ),
)

private val samplePortalState = ResidentPortalUiState(
    loading = false,
    selectedUnitCode = "101",
    me = PortalMeReply(
        gate = PortalGateDto(phoneNumber = "2145550100", siteName = "Oakridge Condos", adminPhone = "2145550199"),
        units = listOf(PortalUnitSummaryDto("101", "Jordan Rivera")),
        unit = PortalUnitDto(
            directoryCode = "101",
            name = "Jordan Rivera",
            phone = "2145550142",
            entryCode = "4829",
            cards = listOf(PortalCardDto("10421", "Remote"), PortalCardDto("10422", "Clickers")),
            email = "jordan@example.com",
        ),
        portal = PortalCapabilitiesDto(
            enabled = true,
            buzzEnabled = true,
            buzzRelays = listOf(0, 2),
            pulseSeconds = 2,
            maxRerollsPerDay = 2,
            buzzRelayLabels = listOf(BuzzRelayLabelDto(0, "Main Gate"), BuzzRelayLabelDto(2, "Walk Gate")),
        ),
        reroll = PortalRerollStatusDto(usedToday = 0, maxPerDay = 2, remainingToday = 2),
    ),
    status = PortalStatusReply(available = true, cooldownSeconds = 0),
)

@Preview(name = "01 Sign In (Light)", showSystemUi = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
fun SignInLightPreview() {
    DoorkingTheme(darkTheme = false, dynamicColor = false) {
        SignInScreen(
            initialServerUrl = "https://gate.example.com",
            initialSessionInfo = SessionResponse(
                loggedIn = false,
                emailEnabled = true,
                sso = listOf(SsoProviderInfo("work", "Work SSO")),
            ),
            onProbeServer = { Result.success(SessionResponse()) },
            onSignInPassword = { _, _, _ -> Result.success(SessionResponse()) },
            onSignInPasskey = { _, _ -> Result.success(null) },
            onRequestPasswordReset = { _, _ -> Result.success("Sent") },
            onStartSso = { _, _ -> Result.success("https://example.com") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "02 Resident Portal (Light)", showSystemUi = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
fun ResidentPortalPreview() {
    DoorkingTheme(darkTheme = false, dynamicColor = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Oakridge Condos")
                            Text(
                                "Unit 101 · Jordan Rivera",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Refresh, null) } },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Rounded.Fence, null) }, label = { Text("Gate") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.History, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.AutoMirrored.Rounded.Assignment, null) }, label = { Text("Requests") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Person, null) }, label = { Text("Account") })
                }
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).padding(padding)) {
                PortalGateTab(
                    state = samplePortalState,
                    onSelectUnit = {},
                    onBuzz = { _, _ -> },
                    onReroll = {},
                    onSharePdf = {},
                    onCopyCode = {},
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "03 Staff Residents (Dark)", showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
fun StaffResidentsDarkPreview() {
    DoorkingTheme(darkTheme = true, dynamicColor = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Oakridge Condos")
                            Text(
                                "Unsent working-copy changes",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Refresh, null) } },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) { Icon(Icons.Rounded.Add, null) }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Rounded.People, null) }, label = { Text("Residents") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Work, null) }, label = { Text("Vendors") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { BadgedBox(badge = { Badge() }) { Icon(Icons.Rounded.Fence, null) } }, label = { Text("Gate") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.MoreHoriz, null) }, label = { Text("More") })
                }
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).padding(padding)) {
                DirectoryTab(
                    state = sampleStaffState,
                    isVendor = false,
                    canEdit = true,
                    editingTarget = null,
                    showAddSheet = false,
                    onRequestEdit = {},
                    onDismissAdd = {},
                    onSave = { _, _, _, _, _, _, _, _ -> },
                    onDelete = {},
                    onShareWelcomePdf = {},
                    onDecideConflict = { _, _, _ -> },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "04 Staff Activity (Dark)", showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
fun StaffActivityDarkPreview() {
    DoorkingTheme(darkTheme = true, dynamicColor = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Oakridge Condos")
                            Text(
                                "Unsent working-copy changes",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Refresh, null) } },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.People, null) }, label = { Text("Residents") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Work, null) }, label = { Text("Vendors") })
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { BadgedBox(badge = { Badge() }) { Icon(Icons.Rounded.Fence, null) } }, label = { Text("Gate") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.MoreHoriz, null) }, label = { Text("More") })
                }
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).padding(padding)) {
                TransactionsTab(
                    state = sampleStaffState,
                    serverUrl = "https://gate.example.com",
                    gatePhone = "2145550100",
                    okHttpClient = OkHttpClient(),
                    onLoadOlder = {},
                    onRequestSnapshot = {},
                )
            }
        }
    }
}

@Preview(name = "05 Live Relays Sheet (Dark)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 412)
@Composable
fun LiveRelaysSheetPreview() {
    DoorkingTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            LiveRelaysSheetContent(
                state = sampleStaffState,
                isAdmin = true,
                onRefresh = {},
                onToggleRelay = { _, _, _ -> },
                onCancelTimer = {},
                onClose = {},
            )
        }
    }
}

@Preview(name = "06 Gate Control Sheet (Light)", showBackground = true, widthDp = 412)
@Composable
fun GateControlSheetPreview() {
    DoorkingTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            GateControlSheetContent(
                state = sampleStaffState,
                isAdmin = true,
                readOnly = false,
                onPreviewSend = {},
                onResetWorkingCopy = {},
                onPull = {},
                onOpenLiveRelays = {},
                onShowProgress = {},
                onClose = {},
            )
        }
    }
}

// --- Google Play Store 9:16 Phone Screenshots (100% synthetic sample data) ---

@Preview(name = "Play01 Login", showSystemUi = true, device = "spec:width=412dp,height=732dp,dpi=420")
@Composable
fun PlayStoreLoginPreview() {
    SignInLightPreview()
}

@Preview(name = "Play02 Resident", showSystemUi = true, device = "spec:width=412dp,height=732dp,dpi=420")
@Composable
fun PlayStoreResidentPreview() {
    ResidentPortalPreview()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Preview(name = "Play03 Gate Settings", showSystemUi = true, device = "spec:width=412dp,height=732dp,dpi=420")
@Composable
fun PlayStoreGateSettingsPreview() {
    DoorkingTheme(darkTheme = false, dynamicColor = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    },
                    title = {
                        Column {
                            Text("Board Settings")
                            Text(
                                "Oakridge Condos",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.People, null) }, label = { Text("Residents") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Work, null) }, label = { Text("Vendors") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Fence, null) }, label = { Text("Gate") })
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Rounded.MoreHoriz, null) }, label = { Text("More") })
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .padding(padding),
            ) {
                SecondaryScrollableTabRow(selectedTabIndex = 3, edgePadding = 12.dp) {
                    listOf("Facility", "Holidays", "Security", "Hold Open", "Display", "APB", "System").forEachIndexed { idx, label ->
                        Tab(selected = idx == 3, onClick = {}, text = { Text(label) })
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        BubbleCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusPill("Row #1 · Main Gate", tone = PillTone.Primary)
                                StatusPill("Active", tone = PillTone.Success)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimePickerField(label = "Start", value = "07:00", onValueChange = {}, modifier = Modifier.weight(1f))
                                TimePickerField(label = "End", value = "19:00", onValueChange = {}, modifier = Modifier.weight(1f))
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("S" to false, "M" to true, "T" to true, "W" to true, "T" to true, "F" to true, "S" to false).forEach { (d, on) ->
                                    FilterChip(selected = on, onClick = {}, label = { Text(d) })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = true, onClick = {}, label = { Text("R0 · Main Gate") })
                                FilterChip(selected = false, onClick = {}, label = { Text("R1 · Exit Gate") })
                                FilterChip(selected = true, onClick = {}, label = { Text("R2 · Walk Gate") })
                            }
                        }
                    }
                    item {
                        BubbleCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Anti-Pass Back Mode", style = MaterialTheme.typography.titleSmall)
                                StatusPill("Synced", tone = PillTone.Neutral)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Off", "Passive", "Active", "Trap").forEach { mode ->
                                    FilterChip(selected = mode == "Passive", onClick = {}, label = { Text(mode) })
                                }
                            }
                        }
                    }
                    item {
                        BubbleCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Scheduled Re-Sync All", style = MaterialTheme.typography.titleSmall)
                                Switch(checked = true, onCheckedChange = {})
                            }
                            OutlinedTextField(
                                value = "1:30",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Talk time (m:ss)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Play04 Transactions", showSystemUi = true, device = "spec:width=412dp,height=732dp,dpi=420")
@Composable
fun PlayStoreTransactionsPreview() {
    DoorkingTheme(darkTheme = false, dynamicColor = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Oakridge Condos")
                            Text(
                                "Gate activity · 24h",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Refresh, null) } },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.People, null) }, label = { Text("Residents") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Work, null) }, label = { Text("Vendors") })
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Fence, null) }, label = { Text("Gate") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.MoreHoriz, null) }, label = { Text("More") })
                }
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).padding(padding)) {
                TransactionsTab(
                    state = sampleStaffState,
                    serverUrl = "https://gate.example.com",
                    gatePhone = "2145550100",
                    okHttpClient = OkHttpClient(),
                    onLoadOlder = {},
                    onRequestSnapshot = {},
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Play05 Residents", showSystemUi = true, device = "spec:width=412dp,height=732dp,dpi=420")
@Composable
fun PlayStoreResidentsPreview() {
    DoorkingTheme(darkTheme = false, dynamicColor = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Oakridge Condos")
                            Text(
                                "Unsent working-copy changes",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Refresh, null) } },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) { Icon(Icons.Rounded.Add, null) }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Rounded.People, null) }, label = { Text("Residents") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Work, null) }, label = { Text("Vendors") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { BadgedBox(badge = { Badge() }) { Icon(Icons.Rounded.Fence, null) } }, label = { Text("Gate") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.MoreHoriz, null) }, label = { Text("More") })
                }
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).padding(padding)) {
                DirectoryTab(
                    state = sampleStaffState,
                    isVendor = false,
                    canEdit = true,
                    editingTarget = null,
                    showAddSheet = false,
                    onRequestEdit = {},
                    onDismissAdd = {},
                    onSave = { _, _, _, _, _, _, _, _ -> },
                    onDelete = {},
                    onShareWelcomePdf = {},
                    onDecideConflict = { _, _, _ -> },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Play06 Vendors", showSystemUi = true, device = "spec:width=412dp,height=732dp,dpi=420")
@Composable
fun PlayStoreVendorsPreview() {
    DoorkingTheme(darkTheme = false, dynamicColor = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Oakridge Condos")
                            Text(
                                "Vendor access codes",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Refresh, null) } },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) { Icon(Icons.Rounded.Add, null) }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.People, null) }, label = { Text("Residents") })
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Rounded.Work, null) }, label = { Text("Vendors") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Fence, null) }, label = { Text("Gate") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.MoreHoriz, null) }, label = { Text("More") })
                }
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).padding(padding)) {
                DirectoryTab(
                    state = sampleStaffState,
                    isVendor = true,
                    canEdit = true,
                    editingTarget = null,
                    showAddSheet = false,
                    onRequestEdit = {},
                    onDismissAdd = {},
                    onSave = { _, _, _, _, _, _, _, _ -> },
                    onDelete = {},
                    onShareWelcomePdf = {},
                    onDecideConflict = { _, _, _ -> },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Play07 Live Relays", showSystemUi = true, device = "spec:width=412dp,height=732dp,dpi=420")
@Composable
fun PlayStoreRelaysPreview() {
    DoorkingTheme(darkTheme = false, dynamicColor = false) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    },
                    title = {
                        Column {
                            Text("Live Relays")
                            Text(
                                "Oakridge Condos",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Refresh, null) } },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.People, null) }, label = { Text("Residents") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Work, null) }, label = { Text("Vendors") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.AutoMirrored.Rounded.ListAlt, null) }, label = { Text("Activity") })
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Rounded.Fence, null) }, label = { Text("Gate") })
                    NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.MoreHoriz, null) }, label = { Text("More") })
                }
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).padding(padding)) {
                LiveRelaysSheetContent(
                    state = sampleStaffState,
                    isAdmin = true,
                    onRefresh = {},
                    onToggleRelay = { _, _, _ -> },
                    onCancelTimer = {},
                    onClose = {},
                )
            }
        }
    }
}

// --- 7-inch Tablet Screenshots (9:16, 900x1600 px) ---
@Preview(showSystemUi = true, device = "spec:width=600dp,height=1067dp,dpi=240")
@Composable
fun PlayStore7in1() = PlayStoreLoginPreview()

@Preview(showSystemUi = true, device = "spec:width=600dp,height=1067dp,dpi=240")
@Composable
fun PlayStore7in2() = PlayStoreResidentPreview()

@Preview(showSystemUi = true, device = "spec:width=600dp,height=1067dp,dpi=240")
@Composable
fun PlayStore7in3() = PlayStoreGateSettingsPreview()

@Preview(showSystemUi = true, device = "spec:width=600dp,height=1067dp,dpi=240")
@Composable
fun PlayStore7in4() = PlayStoreTransactionsPreview()

@Preview(showSystemUi = true, device = "spec:width=600dp,height=1067dp,dpi=240")
@Composable
fun PlayStore7in5() = PlayStoreResidentsPreview()

@Preview(showSystemUi = true, device = "spec:width=600dp,height=1067dp,dpi=240")
@Composable
fun PlayStore7in6() = PlayStoreVendorsPreview()

@Preview(showSystemUi = true, device = "spec:width=600dp,height=1067dp,dpi=240")
@Composable
fun PlayStore7in7() = PlayStoreRelaysPreview()

// --- 10-inch Tablet Screenshots (9:16, 1200x2133 px — each side >= 1080 px) ---
@Preview(showSystemUi = true, device = "spec:width=800dp,height=1422dp,dpi=240")
@Composable
fun PlayStore10in1() = PlayStoreLoginPreview()

@Preview(showSystemUi = true, device = "spec:width=800dp,height=1422dp,dpi=240")
@Composable
fun PlayStore10in2() = PlayStoreResidentPreview()

@Preview(showSystemUi = true, device = "spec:width=800dp,height=1422dp,dpi=240")
@Composable
fun PlayStore10in3() = PlayStoreGateSettingsPreview()

@Preview(showSystemUi = true, device = "spec:width=800dp,height=1422dp,dpi=240")
@Composable
fun PlayStore10in4() = PlayStoreTransactionsPreview()

@Preview(showSystemUi = true, device = "spec:width=800dp,height=1422dp,dpi=240")
@Composable
fun PlayStore10in5() = PlayStoreResidentsPreview()

@Preview(showSystemUi = true, device = "spec:width=800dp,height=1422dp,dpi=240")
@Composable
fun PlayStore10in6() = PlayStoreVendorsPreview()

@Preview(showSystemUi = true, device = "spec:width=800dp,height=1422dp,dpi=240")
@Composable
fun PlayStore10in7() = PlayStoreRelaysPreview()

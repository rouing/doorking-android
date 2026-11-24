package com.doorking.gate.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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

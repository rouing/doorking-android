package com.doorking.gate.feature.admin

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.GateSettingRowDto
import com.doorking.gate.core.network.GateTrackerDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.HelpIconButton
import com.doorking.gate.core.ui.components.MonthDayPickerField
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.components.TimePickerField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/** Draft callbacks surfaced into the bottom-bar Gate sheet (Green Save / Red Discard). */
data class SettingsDraftActions(
    val unsavedCount: Int,
    val save: () -> Unit,
    val discard: () -> Unit,
)

enum class BoardSection(val title: String, val group: String, val helpText: String) {
    Facility(
        "Facility Codes",
        "Facility Codes",
        "A facility code is a number shared by a batch of cards (000–255). When enabled, the gate accepts only cards whose facility code is in this list.",
    ),
    Holidays(
        "Holidays",
        "Holidays",
        "Dates the gate treats as holidays (month and day only). On these dates the HOL row of Hold Open applies, and TZ4 of each security level applies when Holidays for Security Levels is enabled.",
    ),
    Security(
        "Security Levels",
        "Security",
        "A security level controls when a code or card works. Each of its 4 time zones sets a time window (start, end, days) and which relays it may open.",
    ),
    HoldOpen(
        "Hold Open",
        "Hold Open",
        "Times when the gate stays open automatically, such as during business hours. Each row defines a start and end time, weekdays, and the relays held open. The HOL row applies on holidays.",
    ),
    Display(
        "Display",
        "Display",
        "LCD greeting and instruction messages shown on the entry panel screen. Choose 2-Line (Single Line) or 8-Line to match your entry panel's display.",
    ),
    Apb(
        "Anti-Pass Back",
        "Anti-Pass Back",
        "Anti-pass back prevents one card from letting multiple people in: a card used on an In relay must be used on an Out relay before it works on In again (applies to security levels 2–27).",
    ),
    System(
        "Board System",
        "System",
        "Hardware and dialing settings stored on the gate board, including call talk time, outside line digit, and read-only memory/firmware diagnostics.",
    ),
}

private val BOARD_FIELD_HELP = mapOf(
    "holidaysForSecurity" to "Makes Time Zone 4 of every security level act as the holiday schedule: on holiday dates it applies instead of the level's other time zones.",
    "apbMode" to "Off disables anti-pass back. Passive, Active, and Trap are the gate's three modes for handling cards used out of turn.",
    "apbRelaysIn" to "Relays that count as entering the property (up to 10).",
    "apbRelaysOut" to "Relays that count as exiting the property (up to 10).",
    "apbCounterEvery" to "Records how many cards are currently inside to the transaction log at the chosen interval.",
    "apbCounterReset" to "Resets the gate's inside-facility card counter to a specific number (0–65535).",
    "apbResyncAll" to "Clears the in/out state of cards so they can be used on any relay again.",
    "talkTime" to "Maximum duration of a call from the entry panel to a resident, as minutes:seconds (up to 4:14).",
    "outsideLineDigit" to "Optional digit dialed before every phone number to reach an outside line (often 9). Leave blank if not needed.",
)

enum class DisplayLayoutMode(val label: String, val groupName: String) {
    TwoLine("2-Line", "Display (single line)"),
    EightLine("8-Line", "Display (8 line)"),
}

private val FACTORY_DISPLAY_DEFAULTS = mapOf(
    "displayLine1" to "this space is",
    "displayLine2" to "for your user",
    "displayLine3" to "programmable",
    "displayLine4" to "message",
    "displayLine5" to "use a-z to find name",
    "displayLine6" to "or number-enter code",
    "displayLine7" to "or push call-if busy",
    "displayLine8" to "press # - try again.",
    "systemInstruction" to "push a-z to find name or no. enter code or push call",
)

private val WHITESPACE_REGEX = Regex("\\s+")
private val SECURITY_TIME_ZONES = listOf(1, 2, 3, 4)
private val HOLD_OPEN_LABELS = listOf("1", "2", "3", "4", "5", "6", "7", "Hol")

private fun isCustomizedDisplayField(field: GateSettingRowDto): Boolean {
    val norm = field.value.trim().lowercase().replace(WHITESPACE_REGEX, " ")
    if (norm.isEmpty()) return false
    val factoryDefault = FACTORY_DISPLAY_DEFAULTS[field.key]
    return factoryDefault == null || norm != factoryDefault
}

/**
 * Infers which LCD layout (2-line vs 8-line) the gate actually uses.
 * DoorKing boards store both blocks in names.bin and pre-fill the 8-line slots with factory
 * placeholder text ("This Space is / For Your User / Programmable / Message"), so we prioritize
 * slots that have been customized away from factory defaults before falling back to non-blank counts.
 */
fun detectInitialDisplayLayout(fields: List<GateSettingRowDto>): DisplayLayoutMode {
    val twoLineFields = fields.filter { it.group == DisplayLayoutMode.TwoLine.groupName }
    val eightLineFields = fields.filter { it.group == DisplayLayoutMode.EightLine.groupName }

    val twoLineCustom = twoLineFields.count(::isCustomizedDisplayField)
    val eightLineCustom = eightLineFields.count(::isCustomizedDisplayField)

    return when {
        twoLineCustom > 0 && eightLineCustom == 0 -> DisplayLayoutMode.TwoLine
        eightLineCustom > 0 && twoLineCustom == 0 -> DisplayLayoutMode.EightLine
        eightLineCustom > twoLineCustom -> DisplayLayoutMode.EightLine
        twoLineCustom > 0 -> DisplayLayoutMode.TwoLine
        else -> {
            // Neither block customized yet: default to 2-Line unless 2-Line is completely blank and 8-Line is populated.
            val twoLineNonBlank = twoLineFields.count { it.value.isNotBlank() }
            val eightLineNonBlank = eightLineFields.count { it.value.isNotBlank() }
            if (twoLineNonBlank == 0 && eightLineNonBlank > 0) DisplayLayoutMode.EightLine else DisplayLayoutMode.TwoLine
        }
    }
}

private val DAY_LETTERS = listOf("S", "M", "T", "W", "T", "F", "S")
private val DAY_FULL_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

fun toggleBitString(current: String, index: Int, length: Int): String {
    val chars = current.padEnd(length, '0').toCharArray()
    if (index in chars.indices) {
        chars[index] = if (chars[index] == '1') '0' else '1'
    }
    return String(chars)
}

fun usHolidaysForYear(year: Int): List<Pair<String, String>> {
    fun nthWeekday(month: Month, dow: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
    fun lastWeekday(month: Month, dow: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dow))
    fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}"
    return listOf(
        "New Year's Day" to "1/1",
        "MLK Jr. Day" to md(nthWeekday(Month.JANUARY, DayOfWeek.MONDAY, 3)),
        "Presidents' Day" to md(nthWeekday(Month.FEBRUARY, DayOfWeek.MONDAY, 3)),
        "Memorial Day" to md(lastWeekday(Month.MAY, DayOfWeek.MONDAY)),
        "Juneteenth" to "6/19",
        "Independence Day" to "7/4",
        "Labor Day" to md(nthWeekday(Month.SEPTEMBER, DayOfWeek.MONDAY, 1)),
        "Columbus Day" to md(nthWeekday(Month.OCTOBER, DayOfWeek.MONDAY, 2)),
        "Veterans Day" to "11/11",
        "Thanksgiving" to md(nthWeekday(Month.NOVEMBER, DayOfWeek.THURSDAY, 4)),
        "Christmas Day" to "12/25",
    )
}

/**
 * Compact 7-day toggle row fitting Sunday–Saturday on a single line without wrapping.
 */
@Composable
private fun WeekdayToggleRow(
    bits: String,
    enabled: Boolean,
    labelPrefix: String,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DAY_LETTERS.forEachIndexed { d, letter ->
            val selected = bits.getOrNull(d) == '1'
            Surface(
                onClick = { if (enabled) onToggle(d) },
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .semantics { contentDescription = "$labelPrefix ${DAY_FULL_NAMES[d]}" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

fun openableRelayIndices(tracker: GateTrackerDto?): List<Int> {
    val active = tracker?.takeIf { it.enabled }
    val base = listOf(0, 1, 2).filter { r ->
        !(r == 2 && active?.tracker2 == true) && !(r == 1 && active?.tracker1 == true)
    }.toMutableList()
    if (active?.tracker2 == true) base.addAll(3..10)
    if (active?.tracker1 == true) base.addAll(11..18)
    return base
}

fun relayDisplayName(relayLabels: Map<String, String>, relay: Int): String =
    relayLabels[relay.toString()]?.trim()?.takeIf { it.isNotEmpty() } ?: "Relay $relay"

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BoardSettingsScreen(
    gatePhone: String,
    api: DoorkingApi,
    initialSection: BoardSection,
    readOnly: Boolean,
    relayLabels: Map<String, String> = emptyMap(),
    tracker: GateTrackerDto? = null,
    onDraftChange: (SettingsDraftActions?) -> Unit,
    onMessage: (String) -> Unit = {},
    onSavedToWorkingCopy: () -> Unit,
) {
    val availableRelays = remember(tracker) { openableRelayIndices(tracker) }
    var section by remember(initialSection) { mutableStateOf(initialSection) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var fields by remember { mutableStateOf<List<GateSettingRowDto>>(emptyList()) }
    val edits = remember { mutableStateMapOf<String, String>() }
    var securityLevel by remember { mutableIntStateOf(2) }
    var securityMenuOpen by remember { mutableStateOf(false) }
    var displayMode by remember { mutableStateOf(DisplayLayoutMode.TwoLine) }
    var displayModeInitialized by remember(gatePhone) { mutableStateOf(false) }
    var holidayYear by remember { mutableIntStateOf(LocalDate.now().year) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        error = null
        runCatching { api.getSettings(gatePhone).fields }
            .onSuccess { loaded ->
                fields = loaded
                if (!displayModeInitialized) {
                    displayMode = detectInitialDisplayLayout(loaded)
                    displayModeInitialized = true
                }
                edits.clear()
                loading = false
                refreshing = false
            }
            .onFailure {
                if (it is CancellationException) throw it
                error = ApiClientFactory.parseErrorMessage(it)
                loading = false
                refreshing = false
            }
    }

    LaunchedEffect(gatePhone) { load(initial = true) }

    fun current(f: GateSettingRowDto): String = edits[f.key] ?: f.value
    fun edit(f: GateSettingRowDto, next: String) {
        if (readOnly || !f.editable) return
        if (next == f.value) edits.remove(f.key) else edits[f.key] = next
    }

    val saveDraft: () -> Unit = {
        if (edits.isNotEmpty()) {
            val payload = edits.toMap()
            error = null
            scope.launch {
                runCatching {
                    val res = api.saveSettings(gatePhone, payload)
                    if (!res.isSuccessful) {
                        val raw = res.errorBody()?.string().orEmpty()
                        val msg = runCatching { ApiClientFactory.json.decodeFromString<ApiErrorResponse>(raw).error }.getOrNull()
                        error(msg ?: "Save failed (HTTP ${res.code()})")
                    }
                    res.body()
                }
                    .onSuccess { reply ->
                        onMessage("Saved to working copy (blocks: ${reply?.changedBlocks?.joinToString(", ").orEmpty().ifBlank { "none" }}).")
                        onSavedToWorkingCopy()
                        load()
                    }
                    .onFailure { error = it.message ?: "Save failed" }
            }
        }
    }

    val discardDraft: () -> Unit = {
        edits.clear()
        error = null
    }

    // Surface Save (green) / Discard (red) to the Gate bottom sheet whenever this screen is open.
    val unsavedCount = edits.size
    val latestSaveDraft by rememberUpdatedState(saveDraft)
    val latestDiscardDraft by rememberUpdatedState(discardDraft)
    val lastReportedDraftKey = remember(gatePhone) { arrayOfNulls<Pair<Int, Boolean>>(1) }
    SideEffect {
        val nextKey = unsavedCount to readOnly
        if (lastReportedDraftKey[0] != nextKey) {
            lastReportedDraftKey[0] = nextKey
            if (readOnly) {
                onDraftChange(null)
            } else {
                onDraftChange(
                    SettingsDraftActions(
                        unsavedCount = unsavedCount,
                        save = { latestSaveDraft() },
                        discard = { latestDiscardDraft() },
                    ),
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { onDraftChange(null) }
    }

    val sectionFields = remember(fields, section, displayMode) {
        when (section) {
            BoardSection.Display -> fields.filter { it.group == displayMode.groupName }
            else -> fields.filter { it.group == section.group }
        }
    }
    val byKey = remember(sectionFields) { sectionFields.associateBy { it.key } }
    val codeFields = remember(sectionFields) { sectionFields.filter { it.key != "facilityEnabled" } }
    val holFields = remember(sectionFields) { sectionFields.filter { it.key != "holidaysForSecurity" } }
    val holSwitch = remember(sectionFields) { sectionFields.firstOrNull { it.key == "holidaysForSecurity" } }

    val facilityToggleField = remember(fields) { fields.firstOrNull { it.key == "facilityEnabled" } }
    val facilityEnabled = facilityToggleField?.let { current(it) == "Yes" } ?: false
    val holidaysForSecurityField = remember(fields) { fields.firstOrNull { it.key == "holidaysForSecurity" } }

    fun clearTarget(f: GateSettingRowDto): String? = when (section) {
        BoardSection.Facility -> if (f.key == "facilityEnabled") "No" else ""
        BoardSection.Holidays -> if (f.key == "holidaysForSecurity") null else ""
        BoardSection.HoldOpen -> when {
            f.key.endsWith("Begin") || f.key.endsWith("End") || f.key.endsWith("Days") -> ""
            f.key.startsWith("holdOpenRelay") -> "00000000"
            else -> null
        }
        else -> null
    }

    val supportsClear = section in setOf(BoardSection.Facility, BoardSection.Holidays, BoardSection.HoldOpen)
    val canClear by remember(sectionFields, section, readOnly) {
        derivedStateOf {
            !readOnly && supportsClear && sectionFields.any { f ->
                val cleared = clearTarget(f)
                cleared != null && (edits[f.key] ?: f.value) != cleared
            }
        }
    }

    if (loading && fields.isEmpty()) {
        FullScreenLoading("Loading gate settings…")
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = BoardSection.entries.indexOf(section),
            edgePadding = 16.dp,
        ) {
            BoardSection.entries.forEach { item ->
                Tab(
                    selected = section == item,
                    onClick = { section = item },
                    text = { Text(item.title, maxLines = 1) },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { load(initial = false) } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Header row: title + optional inline Facility Codes toggle + right-aligned Red Clear button
                item(key = "header", contentType = "header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(section.title, style = MaterialTheme.typography.titleLarge)
                                HelpIconButton(title = section.title, text = section.helpText)
                            }
                            if (unsavedCount > 0) {
                                Text(
                                    "$unsavedCount unsaved change(s) — tap Gate tab to Save or Discard.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (section == BoardSection.Facility && facilityToggleField != null) {
                            Switch(
                                checked = facilityEnabled,
                                enabled = !readOnly,
                                onCheckedChange = { edit(facilityToggleField, if (it) "Yes" else "No") },
                                modifier = Modifier.semantics { contentDescription = "Enable Facility Codes" },
                            )
                        }
                        if (supportsClear) {
                            Button(
                                enabled = canClear,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                onClick = {
                                    sectionFields.forEach { f ->
                                        clearTarget(f)?.let { edit(f, it) }
                                    }
                                },
                            ) { Text("Clear") }
                        }
                    }
                }

                if (error != null) {
                    item(key = "err", contentType = "error") {
                        ErrorBanner(error!!, onRetry = { scope.launch { load(initial = false) } })
                    }
                }

                when (section) {
                    BoardSection.Facility -> {
                        if (facilityEnabled) {
                            items(codeFields, key = { it.key }, contentType = { "facility_code" }) { setting ->
                                val cur = current(setting)
                                val dirty = setting.key in edits
                                val pending = setting.value != setting.gate
                                val (pillText, pillTone) = when {
                                    dirty -> "Unsaved" to PillTone.Primary
                                    pending -> "Not sent" to PillTone.Success
                                    cur.isNotBlank() -> "Active" to PillTone.Success
                                    else -> "Unused" to PillTone.Neutral
                                }
                                BubbleCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(setting.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        OutlinedTextField(
                                            value = cur,
                                            onValueChange = { edit(setting, it.filter(Char::isDigit).take(3)) },
                                            singleLine = true,
                                            enabled = !readOnly,
                                            placeholder = { Text("—") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.width(84.dp),
                                        )
                                        StatusPill(pillText, tone = pillTone)
                                    }
                                }
                            }
                        }
                    }

                    BoardSection.Holidays -> {
                        item(key = "hol-tools", contentType = "holiday_tools") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    enabled = !readOnly && holFields.isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val list = usHolidaysForYear(holidayYear)
                                        holFields.forEachIndexed { idx, setting ->
                                            edit(setting, list.getOrNull(idx)?.second ?: "")
                                        }
                                    },
                                ) { Text("Fill $holidayYear US Holidays") }
                                OutlinedButton(onClick = { holidayYear = if (holidayYear >= LocalDate.now().year + 2) LocalDate.now().year else holidayYear + 1 }) {
                                    Text("$holidayYear")
                                }
                            }
                        }
                        if (holSwitch != null) {
                            item(key = holSwitch.key, contentType = "holiday_switch") {
                                val cur = current(holSwitch) == "Yes"
                                BubbleCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                "Holidays for Security Levels (TZ4 → HOL)",
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            BOARD_FIELD_HELP["holidaysForSecurity"]?.let { help ->
                                                HelpIconButton(title = "Holidays for Security Levels", text = help)
                                            }
                                        }
                                        Switch(checked = cur, enabled = !readOnly, onCheckedChange = { edit(holSwitch, if (it) "Yes" else "No") })
                                    }
                                }
                            }
                        }
                        itemsIndexed(holFields, key = { _, setting -> setting.key }, contentType = { _, _ -> "holiday_row" }) { idx, setting ->
                            val cur = current(setting)
                            val dirty = setting.key in edits
                            val pending = setting.value != setting.gate
                            val (pillText, pillTone) = when {
                                dirty -> "Unsaved" to PillTone.Primary
                                pending -> "Not sent" to PillTone.Success
                                cur.isNotBlank() -> "Active" to PillTone.Success
                                else -> "Unused" to PillTone.Neutral
                            }
                            BubbleCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StatusPill("#${idx + 1}", tone = PillTone.Neutral)
                                    MonthDayPickerField(
                                        label = "Holiday #${idx + 1}",
                                        value = cur,
                                        onValueChange = { edit(setting, it) },
                                        enabled = !readOnly,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatusPill(pillText, tone = pillTone)
                                }
                            }
                        }
                    }

                    BoardSection.Security -> {
                        val holOn = holidaysForSecurityField?.let { current(it) == "Yes" } ?: false
                        fun activeCountForLevel(lvl: Int): Int = (1..4).count { tz ->
                            val b = byKey["sec${lvl}tz${tz}Begin"]?.let { current(it) }.orEmpty()
                            val e = byKey["sec${lvl}tz${tz}End"]?.let { current(it) }.orEmpty()
                            b.isNotBlank() || e.isNotBlank()
                        }
                        fun levelDisplay(lvl: Int): String {
                            val active = activeCountForLevel(lvl)
                            val num = lvl.toString().padStart(2, '0')
                            return if (active > 0) "Level $num · $active active" else "Level $num"
                        }

                        item(key = "sec-picker", contentType = "security_picker") {
                            ExposedDropdownMenuBox(
                                expanded = securityMenuOpen,
                                onExpandedChange = { securityMenuOpen = it },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedTextField(
                                    value = levelDisplay(securityLevel),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Security level") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = securityMenuOpen) },
                                    modifier = Modifier
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth(),
                                )
                                ExposedDropdownMenu(
                                    expanded = securityMenuOpen,
                                    onDismissRequest = { securityMenuOpen = false },
                                ) {
                                    (2..30).forEach { lvl ->
                                        DropdownMenuItem(
                                            text = { Text(levelDisplay(lvl)) },
                                            onClick = {
                                                securityLevel = lvl
                                                securityMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        items(SECURITY_TIME_ZONES, key = { "tz-$securityLevel-$it" }, contentType = { "security_tz" }) { tz ->
                            val begin = byKey["sec${securityLevel}tz${tz}Begin"]
                            val end = byKey["sec${securityLevel}tz${tz}End"]
                            val days = byKey["sec${securityLevel}tz${tz}Days"]
                            val relays = byKey["sec${securityLevel}tz${tz}Relays"]
                            if (begin != null && end != null && days != null && relays != null) {
                                val tzLabel = if (tz == 4 && holOn) "HOL" else "TZ$tz"
                                val tzFields = listOf(begin, end, days, relays)
                                val dirty = tzFields.any { it.key in edits }
                                val pending = tzFields.any { it.value != it.gate }
                                val active = current(begin).isNotBlank() || current(end).isNotBlank()
                                val (pillText, pillTone) = when {
                                    dirty -> "Unsaved" to PillTone.Primary
                                    pending -> "Not sent" to PillTone.Success
                                    active -> "Active" to PillTone.Success
                                    else -> "Unused" to PillTone.Neutral
                                }
                                BubbleCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        StatusPill(tzLabel, tone = if (active) PillTone.Primary else PillTone.Neutral)
                                        StatusPill(pillText, tone = pillTone)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TimePickerField(
                                            label = "Start",
                                            value = current(begin),
                                            onValueChange = { edit(begin, it) },
                                            enabled = !readOnly,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TimePickerField(
                                            label = "End",
                                            value = current(end),
                                            onValueChange = { edit(end, it) },
                                            enabled = !readOnly,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    WeekdayToggleRow(
                                        bits = current(days),
                                        enabled = !readOnly,
                                        labelPrefix = tzLabel,
                                        onToggle = { d -> edit(days, toggleBitString(current(days), d, 7)) },
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Relays",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            availableRelays.forEach { r ->
                                                val on = current(relays).getOrNull(r) == '1'
                                                val rName = relayDisplayName(relayLabels, r)
                                                FilterChip(
                                                    selected = on,
                                                    enabled = !readOnly,
                                                    onClick = { edit(relays, toggleBitString(current(relays), r, 19)) },
                                                    label = { Text(rName) },
                                                    modifier = Modifier.semantics { contentDescription = "$tzLabel $rName" },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    BoardSection.HoldOpen -> {
                        itemsIndexed(HOLD_OPEN_LABELS, key = { _, lbl -> "ho-$lbl" }, contentType = { _, _ -> "hold_open_row" }) { rowIndex, lbl ->
                            val begin = byKey["holdOpen${lbl}Begin"]
                            val end = byKey["holdOpen${lbl}End"]
                            val days = byKey["holdOpen${lbl}Days"]
                            if (begin != null && end != null && days != null) {
                                val active = current(begin).isNotBlank() || current(end).isNotBlank()
                                BubbleCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        StatusPill(if (lbl == "Hol") "HOL" else "Row #$lbl", tone = if (active) PillTone.Primary else PillTone.Neutral)
                                        StatusPill(if (active) "Active" else "Unused", tone = if (active) PillTone.Success else PillTone.Neutral)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TimePickerField(
                                            label = "Start",
                                            value = current(begin),
                                            onValueChange = { edit(begin, it) },
                                            enabled = !readOnly,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TimePickerField(
                                            label = "End",
                                            value = current(end),
                                            onValueChange = { edit(end, it) },
                                            enabled = !readOnly,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    WeekdayToggleRow(
                                        bits = current(days),
                                        enabled = !readOnly,
                                        labelPrefix = "Row $lbl",
                                        onToggle = { d -> edit(days, toggleBitString(current(days), d, 7)) },
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Relays",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            availableRelays.forEach { r ->
                                                val rField = byKey["holdOpenRelay$r"]
                                                if (rField != null) {
                                                    val on = current(rField).getOrNull(rowIndex) == '1'
                                                    val rName = relayDisplayName(relayLabels, r)
                                                    FilterChip(
                                                        selected = on,
                                                        enabled = !readOnly,
                                                        onClick = { edit(rField, toggleBitString(current(rField), rowIndex, 8)) },
                                                        label = { Text(rName) },
                                                        modifier = Modifier.semantics { contentDescription = "Row $lbl $rName" },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    BoardSection.Apb -> {
                        val modeField = byKey["apbMode"]
                        val apbOn = modeField != null && current(modeField) != "Off"
                        val resyncAllField = byKey["apbResyncAll"]
                        val resyncAllOn = resyncAllField != null && current(resyncAllField) == "Yes"
                        val schedEnabledField = byKey["apbScheduleEnabled"]
                        val schedDaysField = byKey["apbScheduleDays"]
                        val schedTimeField = byKey["apbScheduleTime"]
                        val schedOn = schedEnabledField != null && current(schedEnabledField) == "Yes"

                        val visibleApbFields = sectionFields.filter { setting ->
                            when (setting.key) {
                                "apbMode" -> true
                                "apbScheduleDays", "apbScheduleTime" -> false // rendered inside combined apbScheduleEnabled card
                                "apbResyncList" -> apbOn && resyncAllOn
                                else -> apbOn
                            }
                        }

                        items(
                            visibleApbFields,
                            key = { it.key },
                            contentType = {
                                when (it.key) {
                                    "apbMode", "apbRelaysIn", "apbRelaysOut", "apbCounterEvery" -> "apb_chips"
                                    "apbResyncAll" -> "apb_switch"
                                    "apbScheduleEnabled" -> "apb_schedule"
                                    else -> "apb_text"
                                }
                            },
                        ) { setting ->
                            val cur = current(setting)
                            val dirty = setting.key in edits
                            val pending = setting.value != setting.gate
                            BubbleCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(setting.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                        BOARD_FIELD_HELP[setting.key]?.let { help ->
                                            HelpIconButton(title = setting.name, text = help)
                                        }
                                    }
                                    if (setting.key == "apbResyncAll" || setting.key == "apbScheduleEnabled") {
                                        Switch(
                                            checked = cur == "Yes",
                                            enabled = !readOnly,
                                            onCheckedChange = { edit(setting, if (it) "Yes" else "No") },
                                        )
                                    } else if (dirty || pending) {
                                        StatusPill(if (dirty) "Unsaved" else "Not sent", tone = if (dirty) PillTone.Primary else PillTone.Success)
                                    }
                                }
                                if (!setting.note.isNullOrBlank()) {
                                    Text(setting.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when (setting.key) {
                                    "apbMode" -> {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("Off", "Passive", "Active", "Trap").forEach { mode ->
                                                FilterChip(
                                                    selected = cur == mode,
                                                    enabled = !readOnly,
                                                    onClick = { edit(setting, mode) },
                                                    label = { Text(mode) },
                                                )
                                            }
                                        }
                                    }
                                    "apbRelaysIn", "apbRelaysOut" -> {
                                        val selectedRelays = cur.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            availableRelays.forEach { relay ->
                                                val on = relay in selectedRelays
                                                val rName = relayDisplayName(relayLabels, relay)
                                                FilterChip(
                                                    selected = on,
                                                    enabled = !readOnly && (on || selectedRelays.size < 10),
                                                    onClick = {
                                                        val next = if (on) selectedRelays - relay else selectedRelays + relay
                                                        edit(setting, next.sorted().joinToString(","))
                                                    },
                                                    label = { Text(rName) },
                                                    modifier = Modifier.semantics { contentDescription = "${setting.name} $rName" },
                                                )
                                            }
                                        }
                                    }
                                    "apbCounterEvery" -> {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("Off", "15 Mins", "30 Mins", "Hour").forEach { opt ->
                                                FilterChip(
                                                    selected = cur == opt,
                                                    enabled = !readOnly,
                                                    onClick = { edit(setting, opt) },
                                                    label = { Text(opt) },
                                                )
                                            }
                                        }
                                    }
                                    "apbCounterReset" -> {
                                        OutlinedTextField(
                                            value = cur,
                                            onValueChange = { edit(setting, it.filter(Char::isDigit).take(5)) },
                                            placeholder = { Text("0–65535") },
                                            singleLine = true,
                                            enabled = !readOnly,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    "apbResyncAll" -> Unit
                                    "apbScheduleEnabled" -> {
                                        if (schedOn) {
                                            if (schedTimeField != null) {
                                                TimePickerField(
                                                    label = "Re-Sync time",
                                                    value = current(schedTimeField),
                                                    onValueChange = { edit(schedTimeField, it) },
                                                    enabled = !readOnly,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                            if (schedDaysField != null) {
                                                WeekdayToggleRow(
                                                    bits = current(schedDaysField),
                                                    enabled = !readOnly,
                                                    labelPrefix = "Re-Sync",
                                                    onToggle = { d -> edit(schedDaysField, toggleBitString(current(schedDaysField), d, 7)) },
                                                )
                                            }
                                        }
                                    }
                                    "apbResyncList" -> {
                                        OutlinedTextField(
                                            value = cur,
                                            onValueChange = { edit(setting, it.filter { ch -> ch.isDigit() || ch == ',' }) },
                                            placeholder = { Text("Up to 5 device IDs, comma-separated") },
                                            singleLine = true,
                                            enabled = !readOnly,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    else -> if (setting.editable) {
                                        OutlinedTextField(
                                            value = cur,
                                            onValueChange = { edit(setting, if (setting.maxLength != null) it.take(setting.maxLength) else it) },
                                            singleLine = true,
                                            enabled = !readOnly,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    BoardSection.Display, BoardSection.System -> {
                        if (section == BoardSection.Display) {
                            item(key = "display-mode", contentType = "display_mode") {
                                BubbleCard {
                                    Text("LCD Message Layout", style = MaterialTheme.typography.titleSmall)
                                    val noBorder = BorderStroke(0.dp, Color.Transparent)
                                    SingleChoiceSegmentedButtonRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        space = 2.dp,
                                    ) {
                                        DisplayLayoutMode.entries.forEachIndexed { idx, mode ->
                                            SegmentedButton(
                                                selected = displayMode == mode,
                                                onClick = { displayMode = mode },
                                                shape = SegmentedButtonDefaults.itemShape(
                                                    index = idx,
                                                    count = DisplayLayoutMode.entries.size,
                                                ),
                                                border = noBorder,
                                                colors = SegmentedButtonDefaults.colors(
                                                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    activeBorderColor = Color.Transparent,
                                                    inactiveBorderColor = Color.Transparent,
                                                ),
                                                label = { Text(mode.label, maxLines = 1) },
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Switch between 2-line (Single Line) and 8-line display messages. Defaults to the layout currently populated on the gate.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(
                            sectionFields,
                            key = { it.key },
                            contentType = { if (it.editable) "setting_editable" else "setting_readonly" },
                        ) { setting ->
                            val cur = current(setting)
                            val dirty = setting.key in edits
                            val pending = setting.value != setting.gate
                            BubbleCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(setting.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                        BOARD_FIELD_HELP[setting.key]?.let { help ->
                                            HelpIconButton(title = setting.name, text = help)
                                        }
                                    }
                                    if (!setting.editable) {
                                        StatusPill(cur.ifBlank { "—" }, tone = PillTone.Neutral)
                                    } else if (dirty || pending) {
                                        StatusPill(if (dirty) "Unsaved" else "Not sent", tone = if (dirty) PillTone.Primary else PillTone.Success)
                                    }
                                }
                                if (!setting.note.isNullOrBlank()) {
                                    Text(setting.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (setting.editable) {
                                    when (setting.key) {
                                        "outsideLineDigit" -> OutlinedTextField(
                                            value = cur,
                                            onValueChange = { edit(setting, it.filter(Char::isDigit).take(1)) },
                                            placeholder = { Text("0–9 (or blank)") },
                                            singleLine = true,
                                            enabled = !readOnly,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        "talkTime" -> OutlinedTextField(
                                            value = cur,
                                            onValueChange = { edit(setting, it.filter { ch -> ch.isDigit() || ch == ':' }.take(5)) },
                                            placeholder = { Text("m:ss (e.g. 1:30)") },
                                            singleLine = true,
                                            enabled = !readOnly,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        else -> OutlinedTextField(
                                            value = cur,
                                            onValueChange = { next ->
                                                val ascii = next.filter { ch -> ch in ' '..'~' }
                                                edit(setting, if (setting.maxLength != null) ascii.take(setting.maxLength) else ascii)
                                            },
                                            singleLine = true,
                                            enabled = !readOnly,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                            modifier = Modifier.fillMaxWidth(),
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

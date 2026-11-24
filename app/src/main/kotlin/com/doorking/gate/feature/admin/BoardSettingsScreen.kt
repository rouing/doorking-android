package com.doorking.gate.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.doorking.gate.core.network.ApiClientFactory
import com.doorking.gate.core.network.ApiErrorResponse
import com.doorking.gate.core.network.DoorkingApi
import com.doorking.gate.core.network.GateSettingRowDto
import com.doorking.gate.core.ui.components.BubbleCard
import com.doorking.gate.core.ui.components.ErrorBanner
import com.doorking.gate.core.ui.components.FullScreenLoading
import com.doorking.gate.core.ui.components.MonthDayPickerField
import com.doorking.gate.core.ui.components.PillTone
import com.doorking.gate.core.ui.components.StatusPill
import com.doorking.gate.core.ui.components.TimePickerField
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

enum class BoardSection(val title: String, val group: String) {
    Facility("Facility Codes", "Facility Codes"),
    Holidays("Holidays", "Holidays"),
    Security("Security Levels", "Security"),
    HoldOpen("Hold Open", "Hold Open"),
    Display("Display", "Display"),
    Apb("Anti-Pass Back", "Anti-Pass Back"),
    System("Board System", "System"),
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BoardSettingsScreen(
    gatePhone: String,
    api: DoorkingApi,
    initialSection: BoardSection,
    readOnly: Boolean,
    onDraftChange: (SettingsDraftActions?) -> Unit,
    onMessage: (String) -> Unit = {},
    onSavedToWorkingCopy: () -> Unit,
) {
    var section by remember(initialSection) { mutableStateOf(initialSection) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var fields by remember { mutableStateOf<List<GateSettingRowDto>>(emptyList()) }
    val edits = remember { mutableStateMapOf<String, String>() }
    var securityLevel by remember { mutableIntStateOf(2) }
    var holidayYear by remember { mutableIntStateOf(LocalDate.now().year) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(initial: Boolean = false) {
        if (initial) loading = true else refreshing = true
        error = null
        scope.launch {
            runCatching { api.getSettings(gatePhone).fields }
                .onSuccess { fields = it; edits.clear(); loading = false; refreshing = false }
                .onFailure { error = ApiClientFactory.parseErrorMessage(it); loading = false; refreshing = false }
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
    LaunchedEffect(unsavedCount, readOnly) {
        if (readOnly) {
            onDraftChange(null)
        } else {
            onDraftChange(SettingsDraftActions(unsavedCount = unsavedCount, save = saveDraft, discard = discardDraft))
        }
    }
    DisposableEffect(Unit) {
        onDispose { onDraftChange(null) }
    }

    val sectionFields = remember(fields, section) {
        when (section) {
            BoardSection.Display -> fields.filter { it.group.startsWith("Display") }
            else -> fields.filter { it.group == section.group }
        }
    }

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
    val canClear = !readOnly && supportsClear && sectionFields.any { f ->
        val cleared = clearTarget(f)
        cleared != null && current(f) != cleared
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
            onRefresh = { load(initial = false) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Title row with right-aligned Red Clear button (disabled/grayed when nothing to clear)
                item(key = "header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (section != BoardSection.System) {
                                Text(section.title, style = MaterialTheme.typography.titleLarge)
                            }
                            if (unsavedCount > 0) {
                                Text(
                                    "$unsavedCount unsaved change(s) — tap Gate tab to Save or Discard.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
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

                if (error != null) item(key = "err") { ErrorBanner(error!!, onRetry = { load(initial = false) }) }

        when (section) {
            BoardSection.Facility -> {
                items(sectionFields, key = { it.key }) { setting ->
                    val cur = current(setting)
                    val dirty = setting.key in edits
                    val pending = setting.value != setting.gate
                    val isToggle = setting.key == "facilityEnabled"
                    val (pillText, pillTone) = when {
                        dirty -> "Unsaved" to PillTone.Primary
                        pending -> "Not sent" to PillTone.Success
                        isToggle -> (if (cur == "Yes") "Enabled" else "Disabled") to (if (cur == "Yes") PillTone.Success else PillTone.Neutral)
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
                            if (isToggle) {
                                Switch(
                                    checked = cur == "Yes",
                                    enabled = !readOnly,
                                    onCheckedChange = { edit(setting, if (it) "Yes" else "No") },
                                )
                            } else {
                                OutlinedTextField(
                                    value = cur,
                                    onValueChange = { edit(setting, it.filter(Char::isDigit).take(3)) },
                                    singleLine = true,
                                    enabled = !readOnly,
                                    placeholder = { Text("—") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(84.dp),
                                )
                            }
                            StatusPill(pillText, tone = pillTone)
                        }
                    }
                }
            }

            BoardSection.Holidays -> {
                val holFields = sectionFields.filter { it.key != "holidaysForSecurity" }
                val holSwitch = sectionFields.firstOrNull { it.key == "holidaysForSecurity" }
                item(key = "hol-tools") {
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
                    item(key = holSwitch.key) {
                        val cur = current(holSwitch) == "Yes"
                        BubbleCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Holidays for Security Levels (TZ4 → HOL)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Switch(checked = cur, enabled = !readOnly, onCheckedChange = { edit(holSwitch, if (it) "Yes" else "No") })
                            }
                        }
                    }
                }
                items(holFields.indices.toList(), key = { holFields[it].key }) { idx ->
                    val setting = holFields[idx]
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
                val holOn = fields.firstOrNull { it.key == "holidaysForSecurity" }?.let { current(it) == "Yes" } ?: false
                item(key = "sec-picker") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items((2..30).toList()) { lvl ->
                            FilterChip(
                                selected = securityLevel == lvl,
                                onClick = { securityLevel = lvl },
                                label = { Text("L${lvl.toString().padStart(2, '0')}") },
                            )
                        }
                    }
                }
                items(listOf(1, 2, 3, 4), key = { "tz-$securityLevel-$it" }) { tz ->
                    val byKey = sectionFields.associateBy { it.key }
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
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                DAY_LETTERS.forEachIndexed { d, letter ->
                                    val on = current(days).getOrNull(d) == '1'
                                    FilterChip(
                                        selected = on,
                                        enabled = !readOnly,
                                        onClick = { edit(days, toggleBitString(current(days), d, 7)) },
                                        label = { Text(letter) },
                                        modifier = Modifier.semantics { contentDescription = "$tzLabel ${DAY_FULL_NAMES[d]}" },
                                    )
                                }
                                listOf(0, 1, 2).forEach { r ->
                                    val on = current(relays).getOrNull(r) == '1'
                                    FilterChip(
                                        selected = on,
                                        enabled = !readOnly,
                                        onClick = { edit(relays, toggleBitString(current(relays), r, 19)) },
                                        label = { Text("R$r") },
                                        modifier = Modifier.semantics { contentDescription = "$tzLabel Relay $r" },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            BoardSection.HoldOpen -> {
                val labels = listOf("1", "2", "3", "4", "5", "6", "7", "Hol")
                val byKey = sectionFields.associateBy { it.key }
                items(labels.indices.toList(), key = { "ho-${labels[it]}" }) { rowIndex ->
                    val lbl = labels[rowIndex]
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
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                DAY_LETTERS.forEachIndexed { d, letter ->
                                    val on = current(days).getOrNull(d) == '1'
                                    FilterChip(
                                        selected = on,
                                        enabled = !readOnly,
                                        onClick = { edit(days, toggleBitString(current(days), d, 7)) },
                                        label = { Text(letter) },
                                        modifier = Modifier.semantics { contentDescription = "Row $lbl ${DAY_FULL_NAMES[d]}" },
                                    )
                                }
                                listOf(0, 1, 2).forEach { r ->
                                    val rField = byKey["holdOpenRelay$r"]
                                    if (rField != null) {
                                        val on = current(rField).getOrNull(rowIndex) == '1'
                                        FilterChip(
                                            selected = on,
                                            enabled = !readOnly,
                                            onClick = { edit(rField, toggleBitString(current(rField), rowIndex, 8)) },
                                            label = { Text("R$r") },
                                            modifier = Modifier.semantics { contentDescription = "Row $lbl Relay $r" },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            BoardSection.Apb -> {
                items(sectionFields, key = { it.key }) { setting ->
                    val cur = current(setting)
                    val dirty = setting.key in edits
                    val pending = setting.value != setting.gate
                    BubbleCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(setting.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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
                                    listOf(0, 1, 2).forEach { relay ->
                                        val on = relay in selectedRelays
                                        FilterChip(
                                            selected = on,
                                            enabled = !readOnly && (on || selectedRelays.size < 10),
                                            onClick = {
                                                val next = if (on) selectedRelays - relay else selectedRelays + relay
                                                edit(setting, next.sorted().joinToString(","))
                                            },
                                            label = { Text("R$relay") },
                                            modifier = Modifier.semantics { contentDescription = "${setting.name} Relay $relay" },
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
                            "apbResyncAll", "apbScheduleEnabled" -> Unit
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
                            "apbScheduleDays" -> {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    DAY_LETTERS.forEachIndexed { d, letter ->
                                        val on = cur.getOrNull(d) == '1'
                                        FilterChip(
                                            selected = on,
                                            enabled = !readOnly,
                                            onClick = { edit(setting, toggleBitString(cur, d, 7)) },
                                            label = { Text(letter) },
                                            modifier = Modifier.semantics { contentDescription = "Re-Sync ${DAY_FULL_NAMES[d]}" },
                                        )
                                    }
                                }
                            }
                            "apbScheduleTime" -> {
                                TimePickerField(
                                    label = "Re-Sync time",
                                    value = cur,
                                    onValueChange = { edit(setting, it) },
                                    enabled = !readOnly,
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
                items(sectionFields, key = { it.key }) { setting ->
                    val cur = current(setting)
                    val dirty = setting.key in edits
                    val pending = setting.value != setting.gate
                    BubbleCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(setting.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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

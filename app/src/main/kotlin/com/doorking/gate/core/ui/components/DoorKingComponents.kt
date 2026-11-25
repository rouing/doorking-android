package com.doorking.gate.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.doorking.gate.core.auth.ThemeMode
import com.doorking.gate.core.ui.theme.LocalDoorkingColors

enum class PillTone {
    Neutral,
    Primary,
    Success,
    Warning,
    Error,
}

/**
 * Compact solid Material 3 status pill reserved for states, roles, and access outcomes.
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
) {
    val semantic = LocalDoorkingColors.current
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        PillTone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        PillTone.Primary -> scheme.primaryContainer to scheme.onPrimaryContainer
        PillTone.Success -> semantic.successContainer to semantic.onSuccessContainer
        PillTone.Warning -> semantic.warningContainer to semantic.onWarningContainer
        PillTone.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Full-width tonal card (transactions, history, gate rows, resident entries).
 */
@Composable
fun BubbleCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            border = border,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            border = border,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/**
 * Full-width section card with header row and balanced 16dp internal spacing.
 * Pass `tonal = true` for the borderless tonal block style (`surfaceContainerHigh`, no outline).
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (tonal) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (tonal) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) {
                    trailing()
                }
            }
            content()
        }
    }
}

/**
 * Compact "?" icon button that opens a Material 3 help popup dialog explaining a setting in plain English.
 */
@Composable
fun HelpIconButton(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null) },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Got it") }
            },
        )
    }
    IconButton(
        onClick = { open = true },
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
            contentDescription = "Help for $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Accessible collapsible section card where the entire header row toggles expansion and reports state to TalkBack.
 */
@Composable
fun CollapsibleSectionCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    helpText: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (tonal) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (tonal) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse $title" else "Expand $title",
                        onClick = onToggle,
                    )
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!helpText.isNullOrBlank()) {
                        HelpIconButton(title = title, text = helpText)
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * 3-way borderless Material 3 segmented theme selector (System / Light / Dark).
 */
@Composable
fun ThemeModeSelector(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    inactiveContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val options = listOf(
        ThemeMode.SYSTEM to "System",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark",
    )
    val noBorder = BorderStroke(0.dp, Color.Transparent)
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth(),
        space = 2.dp,
    ) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                border = noBorder,
                colors = SegmentedButtonDefaults.colors(
                    inactiveContainerColor = inactiveContainerColor,
                    activeBorderColor = Color.Transparent,
                    inactiveBorderColor = Color.Transparent,
                ),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Error",
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/**
 * Material 3 contained borderless search pill bar (`surfaceContainerHigh` lighter gray fill, no outline).
 */
@Composable
fun SearchPillBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                text = placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                    )
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * M3 Expressive contained segmented credential list for displaying assigned access cards, clickers, and remotes.
 */
@Composable
fun AccessCredentialsSection(
    credentials: List<Pair<String, String>>, // (number, deviceLabel)
    onCopyNumber: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = "Access cards & remotes",
    topLevel: Boolean = false,
) {
    if (credentials.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (topLevel) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (topLevel) 12.dp else 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = if (topLevel) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    color = if (topLevel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusPill(text = "${credentials.size}", tone = PillTone.Primary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                credentials.forEachIndexed { index, (number, deviceLabel) ->
                    val topCorner = if (index == 0) 12.dp else 4.dp
                    val bottomCorner = if (index == credentials.lastIndex) 12.dp else 4.dp
                    val itemShape = RoundedCornerShape(
                        topStart = topCorner,
                        topEnd = topCorner,
                        bottomStart = bottomCorner,
                        bottomEnd = bottomCorner,
                    )
                    val icon = when {
                        deviceLabel.contains("click", ignoreCase = true) ||
                            deviceLabel.contains("remote", ignoreCase = true) ||
                            deviceLabel.contains("trans", ignoreCase = true) -> Icons.Rounded.Sensors
                        deviceLabel.contains("key", ignoreCase = true) ||
                            deviceLabel.contains("fob", ignoreCase = true) -> Icons.Rounded.Key
                        else -> Icons.Rounded.CreditCard
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = itemShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        onClick = { onCopyNumber?.invoke(number) },
                        enabled = onCopyNumber != null,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "#${number.trimStart('0').ifEmpty { number }}",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFeatureSettings = "tnum",
                                    ),
                                )
                                Text(
                                    text = deviceLabel.ifBlank { "Access Card" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (onCopyNumber != null) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy card number $number",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Converts a 24-hour `"HH:MM"` string into a user-friendly 12-hour display string (e.g. `"07:00"` -> `"7:00 AM"`).
 * Returns blank or unrecognized input unchanged.
 */
fun formatTime12Hour(value24: String): String {
    val trimmed = value24.trim()
    if (trimmed.isEmpty()) return ""
    val parts = trimmed.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return trimmed
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return trimmed
    if (hour !in 0..23 || minute !in 0..59) return trimmed
    val suffix = if (hour < 12) "AM" else "PM"
    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$hour12:${minute.toString().padStart(2, '0')} $suffix"
}

/**
 * Read-only tappable time field backed by Material 3 `TimePicker`.
 * Displays 12-hour AM/PM time to the user while storing and emitting 24-hour `"HH:MM"` (or `""`) for the gate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowClear: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }

    if (open) {
        val parts = value.split(":")
        val initHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val initMin = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val pickerState = rememberTimePickerState(
            initialHour = initHour,
            initialMinute = initMin,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val hh = pickerState.hour.toString().padStart(2, '0')
                        val mm = pickerState.minute.toString().padStart(2, '0')
                        onValueChange("$hh:$mm")
                        open = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (allowClear && value.isNotBlank()) {
                        TextButton(onClick = { onValueChange(""); open = false }) { Text("Clear") }
                    }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        )
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = formatTime12Hour(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("—") },
            trailingIcon = {
                Icon(Icons.Rounded.Schedule, contentDescription = "Pick $label")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(role = Role.Button, onClickLabel = "Pick $label") { open = true },
            )
        }
    }
}

private val SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DAYS_IN_MONTH = listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

/**
 * Read-only tappable holiday date field backed by a Month/Day selector dialog (stores `"M/D"` or `""`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonthDayPickerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }

    if (open) {
        val parts = value.split("/")
        var selectedMonth by remember { mutableIntStateOf(parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 12) ?: 1) }
        val maxDay = DAYS_IN_MONTH[selectedMonth - 1]
        var selectedDay by remember { mutableIntStateOf(parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, maxDay) ?: 1) }

        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Month", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SHORT_MONTHS.forEachIndexed { idx, name ->
                            val monthNum = idx + 1
                            FilterChip(
                                selected = selectedMonth == monthNum,
                                onClick = {
                                    selectedMonth = monthNum
                                    selectedDay = selectedDay.coerceAtMost(DAYS_IN_MONTH[idx])
                                },
                                label = { Text(name) },
                            )
                        }
                    }
                    Text("Day", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..DAYS_IN_MONTH[selectedMonth - 1]).forEach { day ->
                            FilterChip(
                                selected = selectedDay == day,
                                onClick = { selectedDay = day },
                                label = { Text("$day") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onValueChange("$selectedMonth/$selectedDay"); open = false }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (value.isNotBlank()) {
                        TextButton(onClick = { onValueChange(""); open = false }) { Text("Clear") }
                    }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        )
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("M/D") },
            trailingIcon = {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = "Pick $label")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(role = Role.Button, onClickLabel = "Pick $label") { open = true },
            )
        }
    }
}

/**
 * Unified Material 3 1.5.0 modal bottom sheet state that expands directly to full content height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberExpandedModalSheetState(): SheetState = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
)

/**
 * Material 3 1.5.0 `OutlinedSecureTextField` wrapper synced with String state, replacing deprecated
 * `PasswordVisualTransformation` / `VisualTransformation`.
 */
@Composable
fun PasswordOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visible: Boolean? = null,
    onToggleVisibility: (() -> Unit)? = null,
    showVisibilityToggle: Boolean = true,
    digitsOnly: Boolean = false,
    maxLength: Int? = null,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
) {
    val state = rememberTextFieldState(initialText = value)
    var localVisible by remember { mutableStateOf(false) }
    val isVisible = visible ?: localVisible

    LaunchedEffect(value) {
        if (state.text.toString() != value) {
            state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { current ->
            val filtered = if (digitsOnly) current.filter(Char::isDigit) else current
            val capped = if (maxLength != null) filtered.take(maxLength) else filtered
            if (capped != current) {
                state.setTextAndPlaceCursorAtEnd(capped)
            }
            if (capped != value) {
                onValueChange(capped)
            }
        }
    }

    OutlinedSecureTextField(
        state = state,
        enabled = enabled,
        label = { Text(label) },
        textObfuscationMode = if (isVisible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (digitsOnly) KeyboardType.NumberPassword else KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        onKeyboardAction = if (onDone != null) {
            { onDone() }
        } else {
            null
        },
        trailingIcon = if (showVisibilityToggle) {
            {
                IconButton(
                    onClick = {
                        if (onToggleVisibility != null) onToggleVisibility() else localVisible = !localVisible
                    },
                ) {
                    Icon(
                        imageVector = if (isVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (isVisible) "Hide password" else "Show password",
                    )
                }
            }
        } else {
            null
        },
        modifier = modifier,
    )
}

@Composable
fun FullScreenLoading(
    label: String = "Loading…",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

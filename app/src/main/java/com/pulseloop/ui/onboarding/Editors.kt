package com.pulseloop.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.SectionHeaderCaps
import com.pulseloop.ui.theme.PulseColors
import kotlin.math.roundToInt

/**
 * Ported from ProfileEditorView.swift + GoalEditorView.swift (iOS #48).
 * Reusable profile/goal editors shared between onboarding and Settings.
 */

// MARK: - ProfileEditor

@Composable
fun ProfileEditor(
    draft: ProfileDraft,
    onDraftChange: (ProfileDraft) -> Unit,
    modifier: Modifier = Modifier,
    showUnits: Boolean = true,
) {
    var activePicker by remember { mutableStateOf<ProfilePickerKind?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showUnits) {
            SectionHeaderCaps("Units")
            FormCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Measurement units",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = PulseColors.textPrimary,
                    )
                    SegmentedRow(
                        options = listOf("Metric", "Imperial"),
                        selectedIndex = if (draft.units == UnitSystem.METRIC) 0 else 1,
                        onSelect = { index ->
                            onDraftChange(draft.copy(units = if (index == 0) UnitSystem.METRIC else UnitSystem.IMPERIAL))
                        },
                    )
                }
            }
        }

        SectionHeaderCaps("Identity")
        FormCard {
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Name",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = PulseColors.textPrimary,
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (draft.name.isEmpty()) {
                            Text("Optional", fontSize = 14.sp, color = PulseColors.textMuted)
                        }
                        BasicTextField(
                            value = draft.name,
                            onValueChange = { onDraftChange(draft.copy(name = it)) },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = PulseColors.textPrimary,
                                textAlign = TextAlign.End,
                            ),
                            cursorBrush = SolidColor(PulseColors.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                HorizontalDivider(color = PulseColors.borderSubtle)

                Column(
                    Modifier.padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Sex",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = PulseColors.textPrimary,
                    )
                    SegmentedRow(
                        options = listOf("Not set", "Female", "Male", "Other"),
                        selectedIndex = when (draft.sex?.lowercase()) {
                            "female" -> 1
                            "male" -> 2
                            "other" -> 3
                            else -> 0
                        },
                        onSelect = { index ->
                            onDraftChange(
                                draft.copy(sex = when (index) {
                                    1 -> "female"
                                    2 -> "male"
                                    3 -> "other"
                                    else -> null
                                }),
                            )
                        },
                    )
                }
            }
        }

        SectionHeaderCaps("Body metrics", action = {
            Text("Optional", fontSize = 12.sp, color = PulseColors.textMuted)
        })
        FormCard {
            Column {
                PickerRow(
                    title = "Age",
                    value = draft.age?.let { "$it years" },
                    onClick = { activePicker = ProfilePickerKind.AGE },
                )
                HorizontalDivider(color = PulseColors.borderSubtle)
                PickerRow(
                    title = "Height",
                    value = heightLabel(draft),
                    onClick = { activePicker = ProfilePickerKind.HEIGHT },
                )
                HorizontalDivider(color = PulseColors.borderSubtle)
                PickerRow(
                    title = "Weight",
                    value = weightLabel(draft),
                    onClick = { activePicker = ProfilePickerKind.WEIGHT },
                )
            }
        }
    }

    when (activePicker) {
        ProfilePickerKind.AGE -> NumberPickerSheet(
            title = "Age",
            values = (13..100).toList(),
            initialValue = draft.age,
            fallback = 30,
            label = { "$it years" },
            onSave = { onDraftChange(draft.copy(age = it)) },
            onDismiss = { activePicker = null },
        )
        ProfilePickerKind.HEIGHT -> NumberPickerSheet(
            title = "Height",
            values = if (draft.units == UnitSystem.METRIC) (120..220).toList() else (48..87).toList(),
            initialValue = draft.heightDisplayValue,
            fallback = if (draft.units == UnitSystem.METRIC) 175 else 69,
            label = { value ->
                if (draft.units == UnitSystem.METRIC) "$value cm" else "${value / 12}' ${value % 12}\""
            },
            onSave = { onDraftChange(draft.settingHeight(it)) },
            onDismiss = { activePicker = null },
        )
        ProfilePickerKind.WEIGHT -> DecimalValueSheet(
            title = "Weight",
            initialValue = draft.weightDisplayValue,
            unit = if (draft.units == UnitSystem.METRIC) "kg" else "lb",
            validRange = if (draft.units == UnitSystem.METRIC) 35.0..250.0 else 77.0..551.0,
            onSave = { onDraftChange(draft.settingWeight(it)) },
            onDismiss = { activePicker = null },
        )
        null -> {}
    }
}

private enum class ProfilePickerKind { AGE, HEIGHT, WEIGHT }

private fun heightLabel(draft: ProfileDraft): String? {
    val value = draft.heightDisplayValue ?: return null
    return if (draft.units == UnitSystem.METRIC) "$value cm" else "${value / 12}' ${value % 12}\""
}

private fun weightLabel(draft: ProfileDraft): String? {
    val value = draft.weightDisplayValue ?: return null
    return "${LocalizedDecimalInput.format(value)} ${if (draft.units == UnitSystem.METRIC) "kg" else "lb"}"
}

@Composable
private fun FormCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        content()
    }
}

@Composable
private fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PulseColors.cardSoft)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) PulseColors.accent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) Color.White else PulseColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = PulseColors.textPrimary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value ?: "Not set",
            fontSize = 14.sp,
            color = if (value == null) PulseColors.textMuted else PulseColors.textSecondary,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = PulseColors.textMuted,
        )
    }
}

/** Bottom-sheet number picker with Clear + Done (iOS `IntegerPickerSheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberPickerSheet(
    title: String,
    values: List<Int>,
    initialValue: Int?,
    fallback: Int,
    label: (Int) -> String,
    onSave: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selection by remember { mutableIntStateOf(initialValue ?: fallback) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (values.indexOf(initialValue ?: fallback) - 2).coerceAtLeast(0),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PulseColors.secondaryBackground,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    onSave(null)
                    onDismiss()
                }) { Text("Clear", color = PulseColors.textSecondary) }
                Spacer(Modifier.weight(1f))
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    onSave(selection)
                    onDismiss()
                }) { Text("Done", color = PulseColors.accent, fontWeight = FontWeight.SemiBold) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(300.dp),
            ) {
                itemsIndexed(values) { _, value ->
                    val selected = value == selection
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selection = value }
                            .background(if (selected) PulseColors.accentSoft else Color.Transparent)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label(value),
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) PulseColors.textPrimary else PulseColors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bottom-sheet decimal text input with Clear + Done (iOS `DecimalValueSheet`, iOS #49).
 * Accepts both a comma and a period as the decimal separator via [LocalizedDecimalInput];
 * Done is disabled until the parsed value is inside [validRange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecimalValueSheet(
    title: String,
    initialValue: Double?,
    unit: String,
    validRange: ClosedFloatingPointRange<Double>,
    onSave: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(initialValue?.let { LocalizedDecimalInput.format(it) } ?: "") }
    val parsedValue = LocalizedDecimalInput.parse(text)?.takeIf { it in validRange }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PulseColors.secondaryBackground,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    onSave(null)
                    onDismiss()
                }) { Text("Clear", color = PulseColors.textSecondary) }
                Spacer(Modifier.weight(1f))
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = parsedValue != null,
                    onClick = {
                        onSave(parsedValue)
                        onDismiss()
                    },
                ) {
                    Text(
                        "Done",
                        color = if (parsedValue != null) PulseColors.accent else PulseColors.textMuted,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Enter your weight in $unit",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = PulseColors.textPrimary,
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(PulseColors.card)
                        .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            LocalizedDecimalInput.format(70.5),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.textMuted,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.textPrimary,
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        ),
                        cursorBrush = SolidColor(PulseColors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }

                Text(
                    "You can use either a comma or a period as the decimal separator.",
                    fontSize = 12.sp,
                    color = PulseColors.textMuted,
                )

                if (text.isNotEmpty() && parsedValue == null) {
                    Text(
                        "Enter a weight between ${validRange.start.toInt()} and ${validRange.endInclusive.toInt()} $unit.",
                        fontSize = 12.sp,
                        color = PulseColors.danger,
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

// MARK: - GoalEditor

@Composable
fun GoalEditor(
    draft: GoalDraft,
    onDraftChange: (GoalDraft) -> Unit,
    units: UnitSystem,
    modifier: Modifier = Modifier,
    includeWeeklyWorkouts: Boolean = true,
) {
    val distanceUnit = if (units == UnitSystem.METRIC) "km" else "mi"
    val distanceRange = if (units == UnitSystem.METRIC) 1f..30f else 1f..20f

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeaderCaps("Daily targets")
        GoalSliderCard(
            title = "Steps",
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            tint = PulseColors.steps,
            value = draft.steps.toFloat(),
            range = 2_000f..20_000f,
            step = 500f,
            label = "%,d".format(draft.steps.toInt()),
            onValueChange = { onDraftChange(draft.copy(steps = it)) },
        )
        GoalSliderCard(
            title = "Distance",
            icon = Icons.Filled.Place,
            tint = PulseColors.distance,
            value = draft.distance.toFloat(),
            range = distanceRange,
            step = 0.5f,
            label = "%.1f %s".format(draft.distance, distanceUnit),
            onValueChange = { onDraftChange(draft.copy(distance = it)) },
        )
        GoalSliderCard(
            title = "Calories",
            icon = Icons.Filled.LocalFireDepartment,
            tint = PulseColors.calories,
            value = draft.calories.toFloat(),
            range = 100f..2_000f,
            step = 50f,
            label = "${draft.calories.toInt()} cal",
            onValueChange = { onDraftChange(draft.copy(calories = it)) },
        )
        GoalSliderCard(
            title = "Active minutes",
            icon = Icons.Filled.Timer,
            tint = PulseColors.readiness,
            value = draft.activeMinutes.toFloat(),
            range = 10f..180f,
            step = 5f,
            label = "${draft.activeMinutes.toInt()} min",
            onValueChange = { onDraftChange(draft.copy(activeMinutes = it)) },
        )
        GoalSliderCard(
            title = "Sleep",
            icon = Icons.Filled.Bedtime,
            tint = PulseColors.sleep,
            value = draft.sleepHours.toFloat(),
            range = 5f..10f,
            step = 0.5f,
            label = "%.1f h".format(draft.sleepHours),
            onValueChange = { onDraftChange(draft.copy(sleepHours = it)) },
        )

        if (includeWeeklyWorkouts) {
            SectionHeaderCaps("Weekly target")
            GoalSliderCard(
                title = "Workouts",
                icon = Icons.Filled.DirectionsRun,
                tint = PulseColors.success,
                value = draft.workouts.toFloat(),
                range = 1f..7f,
                step = 1f,
                label = "${draft.workouts.toInt()} / week",
                onValueChange = { onDraftChange(draft.copy(workouts = it)) },
            )
        }
    }
}

@Composable
private fun GoalSliderCard(
    title: String,
    icon: ImageVector,
    tint: Color,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    label: String,
    onValueChange: (Double) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = tint)
            }
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = PulseColors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = tint,
            )
        }
        Slider(
            value = value,
            onValueChange = { raw ->
                // Snap to the step grid so values match the iOS stepped sliders.
                val snapped = (((raw - range.start) / step).roundToInt() * step + range.start)
                    .coerceIn(range.start, range.endInclusive)
                onValueChange(snapped.toDouble())
            },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = PulseColors.elevated,
            ),
        )
    }
}

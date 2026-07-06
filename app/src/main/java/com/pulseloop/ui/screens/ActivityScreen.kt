package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.ActivityMeta
import com.pulseloop.ui.components.ActivityRing
import com.pulseloop.ui.components.ActivityRings
import com.pulseloop.service.SleepFormat
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.viewmodels.ActivityViewModel
import com.pulseloop.util.Formats
import kotlinx.coroutines.launch
import java.util.Calendar

private val weekLabels = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * Activity dashboard — ported from ActivityView.swift: the daily summary card (colored stats +
 * concentric rings), "+ Record Activity" + history button, the TODAY workout list, and the
 * weekly-goal widget (progress ring + M–S day pills + tap-to-edit-goals).
 */
@Composable
fun ActivityScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: ActivityViewModel? = null,
    liveWorkout: com.pulseloop.service.LiveWorkoutManager? = null,
    // Heights of the glass top/bottom bars this screen scrolls under (0 when standalone).
    topBarPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomBarPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(ActivityViewModel.ActivityState()) })
    val context = LocalContext.current
    // remember{}: the ApiKeyStore constructor does Keystore + encrypted-prefs I/O — far
    // too expensive to repeat on every recomposition of this state-collecting screen.
    val units = remember { ApiKeyStore(context) }.resolvedUnitSystem
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var goalsOpen by remember { mutableStateOf(false) }

    val todayStart = com.pulseloop.util.TimeUtil.startOfTodayLocal()
    val todayWorkouts = state.finishedWorkouts.filter { it.startedAt >= todayStart }
    val todayIdx = ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1) + 6) % 7
    // recentDays is newest-first; index 0..6 = Monday..Sunday of this week's steps.
    val stepsByWeekday = weeklySteps(state, todayIdx)
    val days = weekLabels.mapIndexed { i, label ->
        WeeklyDay(label, completed = (stepsByWeekday[i] ?: 0) >= state.stepGoal, isToday = i == todayIdx)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = 16.dp + topBarPadding, bottom = 16.dp + bottomBarPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DailyActivitySummaryCard(state, units) }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "+ Record Activity",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .clip(CircleShape)
                        .background(PulseColors.accent)
                        .clickable { pickerOpen = true }
                        .wrapContentHeight(),
                )
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(PulseColors.card)
                        .border(1.dp, PulseColors.borderSubtle, CircleShape)
                        .clickable { historyOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CalendarMonth, "Workout history", tint = PulseColors.textSecondary)
                }
            }
        }

        item {
            Text(
                "TODAY",
                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp, color = PulseColors.textMuted,
            )
        }
        if (todayWorkouts.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(PulseColors.card)
                        .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(20.dp))
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("No workouts recorded today", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary)
                    Text("Start one manually when your ring misses an activity.", fontSize = 12.sp, color = PulseColors.textMuted)
                }
            }
        } else {
            items(todayWorkouts.size) { i ->
                ActivityWorkoutRow(todayWorkouts[i], units) {
                    navController?.navigate("activity_detail/${todayWorkouts[i].id}")
                }
            }
        }

        item { WeeklyGoalCard(state, days, onTap = { goalsOpen = true }) }
        item { Spacer(Modifier.height(64.dp)) }
    }

    if (pickerOpen) {
        RecordTypePickerDialog(
            onDismiss = { pickerOpen = false },
            onStart = { type, useGps ->
                pickerOpen = false
                scope.launch {
                    liveWorkout?.start(type, useGps)
                    navController?.navigate("record")
                }
            },
        )
    }
    if (historyOpen) {
        WorkoutHistoryDialog(
            sessions = state.finishedWorkouts,
            units = units,
            onDismiss = { historyOpen = false },
            onSelect = { id ->
                historyOpen = false
                navController?.navigate("activity_detail/$id")
            },
        )
    }
    if (goalsOpen && viewModel != null) {
        GoalEditorDialog(
            state = state,
            onDismiss = { goalsOpen = false },
            onSave = { steps, active, sleepMin, workouts ->
                goalsOpen = false
                scope.launch { viewModel.saveGoals(steps, active, sleepMin, workouts) }
            },
        )
    }
}

/** recentDays keyed to this week's Monday..Sunday slots (nulls before tracking started). */
private fun weeklySteps(state: ActivityViewModel.ActivityState, todayIdx: Int): Map<Int, Int> {
    val dayMs = 86_400_000L
    val todayStart = com.pulseloop.util.TimeUtil.startOfTodayLocal()
    val byDate = state.recentDays.associate { it.date to it.steps }
    return (0..todayIdx).associateWith { i ->
        byDate[todayStart - (todayIdx - i) * dayMs] ?: 0
    }
}

// ─────────────────── Daily summary card (stats + rings) ───────────────────

@Composable
private fun DailyActivitySummaryCard(state: ActivityViewModel.ActivityState, units: UnitSystem) {
    val shape = RoundedCornerShape(20.dp)
    val today = state.today
    val distValue = today?.distanceMeters?.let { Formats.distance(UnitConverter.distance(it, units)) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row {
                SummaryMetric("Steps", today?.steps?.let { Formats.count(it) } ?: "—", null, PulseColors.steps, Modifier.weight(1f))
                SummaryMetric("Distance", distValue ?: "—", if (distValue != null) UnitConverter.distanceUnit(units) else null, PulseColors.distance, Modifier.weight(1f))
            }
            SummaryMetric("Calories", today?.calories?.let { Formats.count(it.toInt()) } ?: "—", if (today?.calories != null) "cal" else null, PulseColors.calories)
        }
        ActivityRings(
            rings = listOf(
                ActivityRing(today?.steps?.toDouble(), state.stepGoal.toDouble(), PulseColors.steps),
                ActivityRing(today?.distanceMeters, state.distanceGoalMeters, PulseColors.distance),
                ActivityRing(today?.calories, state.caloriesGoal.toDouble(), PulseColors.calories),
            ),
            size = 112.dp,
            strokeWidth = 11.dp,
            ringSpacing = 5.dp,
        )
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, unit: String?, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            label.uppercase(),
            fontSize = 15.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp, color = color, maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                value, fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
                color = PulseColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (unit != null) {
                Text(unit, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = PulseColors.textMuted, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
    }
}

// ─────────────────── Workout row ───────────────────

/** One workout in the TODAY list / history sheet (ActivityWorkoutRow in Swift). */
@Composable
fun ActivityWorkoutRow(session: ActivitySessionEntity, units: UnitSystem, onTap: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val durationSec = session.endedAt?.let { ((it - session.startedAt) / 1000 - session.totalPauseSeconds).toInt() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .clickable(onClick = onTap)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PulseColors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ActivityMeta.icon(session.type), null, tint = PulseColors.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ActivityMeta.label(session.type),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(SleepFormat.clockTime(session.startedAt), fontSize = 11.sp, color = PulseColors.textMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (durationSec != null) {
                    Text(durationLabel(durationSec), fontSize = 12.sp, color = PulseColors.textSecondary)
                }
                session.distanceMeters?.takeIf { it > 0 }?.let {
                    Text(
                        "%.2f %s".format(UnitConverter.distance(it, units), UnitConverter.distanceUnit(units)),
                        fontSize = 12.sp, color = PulseColors.textSecondary,
                    )
                }
                session.avgHeartRate?.let {
                    Text("${it.toInt()} bpm avg", fontSize = 12.sp, color = PulseColors.textSecondary)
                }
                Spacer(Modifier.weight(1f))
                if (session.useGps && session.gpsPointCount > 1) {
                    Icon(Icons.Filled.Map, null, tint = PulseColors.textMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

private fun durationLabel(seconds: Int): String = Formats.durationCompact(seconds / 60)

// ─────────────────── Weekly goal widget ───────────────────

data class WeeklyDay(val label: String, val completed: Boolean, val isToday: Boolean)

@Composable
private fun WeeklyGoalCard(state: ActivityViewModel.ActivityState, days: List<WeeklyDay>, onTap: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val activeMinutes = state.today?.activeMinutes ?: 0
    val activeDayCount = days.count { it.completed }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .clickable(onClick = onTap)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressRing(
                value = activeMinutes.toDouble(),
                max = state.activeMinutesGoal.toDouble(),
                color = PulseColors.steps,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$activeMinutes", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
                    Text("MIN", fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, color = PulseColors.textMuted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "WEEKLY GOAL",
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp, color = PulseColors.textMuted,
                )
                Text("$activeDayCount of 7 active days", fontSize = 16.sp, color = PulseColors.textPrimary)
            }
        }
        WeeklyPillCalendar(days)
        Text(
            "TAP TO EDIT GOALS",
            fontSize = 10.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp, color = PulseColors.textMuted,
        )
    }
}

@Composable
private fun ProgressRing(
    value: Double,
    max: Double,
    color: Color,
    size: androidx.compose.ui.unit.Dp = 84.dp,
    stroke: androidx.compose.ui.unit.Dp = 9.dp,
    center: @Composable () -> Unit,
) {
    val progress = if (max <= 0) 0f else (value / max).coerceIn(0.0, 1.0).toFloat()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2
            drawArc(
                color = PulseColors.elevated,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(this.size.width - strokePx, this.size.height - strokePx),
                style = androidx.compose.ui.graphics.drawscope.Stroke(strokePx),
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(this.size.width - strokePx, this.size.height - strokePx),
                style = androidx.compose.ui.graphics.drawscope.Stroke(strokePx, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        center()
    }
}

@Composable
private fun WeeklyPillCalendar(days: List<WeeklyDay>) {
    Row(Modifier.fillMaxWidth()) {
        days.forEach { day ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 24.dp, height = 40.dp)
                        .clip(CircleShape)
                        .background(if (day.completed) PulseColors.steps.copy(alpha = 0.22f) else PulseColors.cardSoft)
                        .border(
                            1.dp,
                            when {
                                day.completed -> PulseColors.steps.copy(alpha = 0.5f)
                                day.isToday -> PulseColors.accent.copy(alpha = 0.6f)
                                else -> PulseColors.borderSubtle
                            },
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (day.completed) "✓" else if (day.isToday) "•" else "",
                        fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        color = if (day.completed) PulseColors.steps else PulseColors.textPrimary,
                    )
                }
                Text(
                    day.label,
                    fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
                    color = if (day.isToday) PulseColors.textPrimary else PulseColors.textMuted,
                )
            }
        }
    }
}

// ─────────────────── Dialogs ───────────────────

/** Workout-type picker (RecordSelectView, condensed to a dialog). GPS toggle for outdoor types. */
@Composable
private fun RecordTypePickerDialog(onDismiss: () -> Unit, onStart: (String, Boolean) -> Unit) {
    var selected by remember { mutableStateOf("run") }
    var useGps by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActivityMeta.ORDER.forEach { type ->
                    val active = type == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) PulseColors.accentSoft else Color.Transparent)
                            .clickable { selected = type }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(ActivityMeta.icon(type), null, tint = PulseColors.accent, modifier = Modifier.size(18.dp))
                        Column {
                            Text(ActivityMeta.label(type), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(ActivityMeta.helper(type), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (ActivityMeta.gpsCapable(selected)) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Track GPS route", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = useGps, onCheckedChange = { useGps = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(selected, useGps && ActivityMeta.gpsCapable(selected)) }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** All finished workouts (WorkoutHistorySheet). */
@Composable
private fun WorkoutHistoryDialog(
    sessions: List<ActivitySessionEntity>,
    units: UnitSystem,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workout history") },
        text = {
            if (sessions.isEmpty()) {
                Text("No workouts yet — recorded workouts will appear here.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                    items(sessions.size) { i ->
                        ActivityWorkoutRow(sessions[i], units) { onSelect(sessions[i].id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Goal steppers for the fields the Android goal entity has (GoalEditorSheet, adapted). */
@Composable
private fun GoalEditorDialog(
    state: ActivityViewModel.ActivityState,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, Int) -> Unit,
) {
    var steps by remember { mutableStateOf(state.stepGoal.toFloat()) }
    var active by remember { mutableStateOf(state.activeMinutesGoal.toFloat()) }
    var sleepH by remember { mutableStateOf(state.sleepMinutesGoal / 60f) }
    var workouts by remember { mutableStateOf(state.workoutsPerWeekGoal.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit goals") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalSlider("Steps", Formats.count((steps / 500).toInt() * 500), steps, 2000f..20000f) { steps = it }
                GoalSlider("Active minutes", "${active.toInt()} min", active, 10f..180f) { active = it }
                GoalSlider("Sleep", "%.1f h".format((sleepH * 2).toInt() / 2f), sleepH, 5f..10f) { sleepH = it }
                GoalSlider("Workouts / week", "${workouts.toInt()}", workouts, 1f..7f) { workouts = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave((steps / 500).toInt() * 500, active.toInt(), ((sleepH * 2).toInt() * 30), workouts.toInt())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GoalSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

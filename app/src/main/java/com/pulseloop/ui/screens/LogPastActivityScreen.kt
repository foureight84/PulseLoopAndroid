package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.ManualActivityError
import com.pulseloop.service.ManualActivityService
import com.pulseloop.ui.components.ActivityMeta
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch

/**
 * Ported from LogPastActivityView.swift (iOS #57d): a form for a completed workout the ring
 * missed. Creation goes through [ManualActivityService], the same shared path the
 * `create_activity_session_from_description` coach tool uses, so a manually logged past session
 * credits the daily rollup exactly like a live-recorded one.
 */
@Composable
fun LogPastActivityScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    // Captured once, like iOS's `maximumDate` — a stable upper bound keeps the date/time pickers
    // from reconfiguring on every recomposition.
    val maximumDate = remember { System.currentTimeMillis() }
    var selectedType by remember { mutableStateOf("run") }
    var startedAt by remember { mutableLongStateOf(maximumDate - 3600_000L) }
    var durationMinutes by remember { mutableIntStateOf(60) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val endedAt = startedAt + durationMinutes * 60_000L
    val isValid = durationMinutes > 0 && endedAt <= maximumDate

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PulseColors.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, tint = PulseColors.textSecondary, onTap = onBack)
                Spacer(Modifier.width(12.dp))
                Text("Log Past Activity", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What did you do?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(
                    "Choose an activity, when it started, and how long it lasted.",
                    fontSize = 14.sp, color = PulseColors.textMuted,
                )
            }
        }

        item { SectionLabel("Activity type") }
        item {
            ActivityTypeGrid(selectedType) { type ->
                selectedType = type
                saveError = null
            }
        }

        item { SectionLabel("When") }
        item {
            EditFieldRow("Started") {
                DateTimeButton(startedAt) { startedAt = it; saveError = null }
            }
        }
        item {
            EditFieldRow("Ends") {
                Text(
                    formatDateTime(endedAt),
                    fontSize = 14.sp,
                    color = if (isValid) PulseColors.textSecondary else PulseColors.warning,
                )
            }
        }

        item { SectionLabel("Duration") }
        item {
            PastActivityDurationCard(durationMinutes) { newValue ->
                durationMinutes = newValue
                saveError = null
            }
        }

        if (!isValid) {
            item { Text("The workout must finish before now.", fontSize = 12.sp, color = PulseColors.warning) }
        }
        saveError?.let { error ->
            item { Text(error, fontSize = 12.sp, color = PulseColors.danger) }
        }

        item {
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val session = ManualActivityService.create(
                                db = db,
                                type = selectedType,
                                startedAt = startedAt,
                                durationMinutes = durationMinutes.toDouble(),
                                now = maximumDate,
                            )
                            onSaved(session.id)
                        } catch (e: ManualActivityError) {
                            saveError = e.message
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = isValid && !saving,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = PulseColors.accent),
            ) {
                Icon(Icons.Filled.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Log Activity", fontWeight = FontWeight.SemiBold)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp, color = PulseColors.textMuted,
    )
}

@Composable
private fun ActivityTypeGrid(selectedType: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActivityMeta.ORDER.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { type ->
                    ActivityTypeButton(type, isSelected = type == selectedType, onSelect = onSelect, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActivityTypeButton(type: String, isSelected: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (isSelected) PulseColors.accentSoft else PulseColors.cardSoft)
            .border(1.5.dp, if (isSelected) PulseColors.accent.copy(alpha = 0.9f) else Color.Transparent, shape)
            .clickable { onSelect(type) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isSelected) Color.White.copy(alpha = 0.22f) else PulseColors.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ActivityMeta.icon(type), null,
                tint = if (isSelected) Color.White else PulseColors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            ActivityMeta.label(type),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = if (isSelected) Color.White else PulseColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(Icons.Filled.CheckCircle, null, tint = PulseColors.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PastActivityDurationCard(minutes: Int, onChange: (Int) -> Unit) {
    val atFloor = minutes <= 5
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PulseColors.cardSoft)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DurationStepButton(Icons.Filled.Remove, enabled = !atFloor) { onChange(maxOf(5, minutes - 5)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(durationText(minutes), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text("DURATION", fontSize = 10.sp, letterSpacing = 1.1.sp, color = PulseColors.textMuted)
            }
            DurationStepButton(Icons.Filled.Add, enabled = true) { onChange(minutes + 5) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60, 90).forEach { value ->
                val selected = minutes == value
                Text(
                    "${value}m",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) PulseColors.accent else PulseColors.textSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(if (selected) PulseColors.accentSoft else PulseColors.card)
                        .border(1.dp, if (selected) PulseColors.accent.copy(alpha = 0.9f) else Color.Transparent, CircleShape)
                        .clickable { onChange(value) }
                        .wrapContentHeight(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DurationStepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(PulseColors.card)
            .then(if (enabled) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) PulseColors.textPrimary else PulseColors.textMuted)
    }
}

private fun durationText(minutes: Int): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0 -> "$remaining min"
        remaining == 0 -> "$hours hr"
        else -> "$hours hr $remaining min"
    }
}

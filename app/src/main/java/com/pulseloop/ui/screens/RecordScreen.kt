package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseloop.service.HeartRateZones
import kotlinx.coroutines.delay

/**
 * Ported from RecordViews.swift.
 * Live workout recording screen with HR zone, distance, pace, and elapsed time.
 */
@Composable
fun RecordScreen(
    activityName: String = "Workout",
    elapsedSeconds: Int = 0,
    distanceMeters: Double = 0.0,
    heartRate: Int? = null,
    spO2: Int? = null,
    calories: Double = 0.0,
    workoutSteps: Int = 0,
    hrvMs: Double? = null,
    isPaused: Boolean = false,
    hrZone: HeartRateZones.Zone = HeartRateZones.Zone.REST,
    splits: List<Int> = emptyList(),
    onLap: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val elapsed = formatElapsed(elapsedSeconds)
    val pace = if (distanceMeters >= 50 && elapsedSeconds > 0) {
        val p = (elapsedSeconds.toDouble() / (distanceMeters / 1000.0))
        formatPace(p)
    } else "--"

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Activity name + status
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(activityName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (isPaused) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text("PAUSED", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Large elapsed + HR
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(elapsed, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                val hrText = heartRate?.let { "$it bpm" } ?: "-- bpm"
                val zoneColor = when (hrZone) {
                    HeartRateZones.Zone.REST, HeartRateZones.Zone.FAT_BURN -> com.pulseloop.ui.theme.MetricColors.ZoneGood
                    HeartRateZones.Zone.CARDIO -> com.pulseloop.ui.theme.MetricColors.ZoneBorderline
                    HeartRateZones.Zone.PEAK -> com.pulseloop.ui.theme.MetricColors.ZoneElevated
                    HeartRateZones.Zone.MAX -> com.pulseloop.ui.theme.MetricColors.ZoneConcern
                }
                Text(hrText, style = MaterialTheme.typography.headlineLarge, color = zoneColor)
                Text(hrZone.label, style = MaterialTheme.typography.labelMedium, color = zoneColor)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Stat tiles
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StatTile("Distance", if (distanceMeters >= 1000) "%.1f km".format(distanceMeters / 1000) else "%.0f m".format(distanceMeters)) }
            item { StatTile("Pace", pace) }
            item { StatTile("SpO₂", spO2?.let { "$it%" } ?: "--") }
            item { StatTile("Calories", if (calories >= 1) "%.0f kcal".format(calories) else "--") }
            item { StatTile("Steps", if (workoutSteps > 0) "%,d".format(workoutSteps) else "--") }
            item { StatTile("HRV est.", hrvMs?.let { "%.0f ms".format(it) } ?: "--") }
        }

        // Splits — successive differences between lap marks, newest first.
        if (splits.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Splits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val laps = splits.mapIndexed { i, mark -> mark - (if (i == 0) 0 else splits[i - 1]) }
            laps.forEachIndexed { i, dur ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lap ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatElapsed(dur), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Lap button (only while running, not paused)
        if (!isPaused) {
            OutlinedButton(onClick = onLap, Modifier.fillMaxWidth().height(48.dp).padding(bottom = 8.dp)) {
                Icon(Icons.Filled.Flag, "Lap")
                Spacer(Modifier.width(8.dp))
                Text("Lap")
            }
        }

        // Action buttons
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isPaused) {
                Button(onClick = onResume, Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Filled.PlayArrow, "Resume")
                    Spacer(Modifier.width(8.dp))
                    Text("Resume")
                }
            } else {
                OutlinedButton(onClick = onPause, Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Filled.Pause, "Pause")
                    Spacer(Modifier.width(8.dp))
                    Text("Pause")
                }
            }
            Button(onClick = onFinish, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Stop, "Finish")
                Spacer(Modifier.width(8.dp))
                Text("Finish")
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatElapsed(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatPace(secondsPerKm: Double): String {
    val min = (secondsPerKm / 60).toInt()
    val sec = (secondsPerKm % 60).toInt()
    return "%d'%02d\"/km".format(min, sec)
}

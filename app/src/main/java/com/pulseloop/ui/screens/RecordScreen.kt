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
    isPaused: Boolean = false,
    hrZone: HeartRateZones.Zone = HeartRateZones.Zone.REST,
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
                    HeartRateZones.Zone.REST, HeartRateZones.Zone.FAT_BURN -> Color(0xFF4CAF50)
                    HeartRateZones.Zone.CARDIO -> Color(0xFFFFC107)
                    HeartRateZones.Zone.PEAK -> Color(0xFFFF9800)
                    HeartRateZones.Zone.MAX -> Color(0xFFF44336)
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
        }

        Spacer(Modifier.weight(1f))

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

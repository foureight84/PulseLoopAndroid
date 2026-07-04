package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ui.components.SimpleLineChart
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Workout review — tapped from a row in the Activity screen's "Recent Workouts" card.
 * Shows the session's stats plus a heart-rate trace (when samples exist), and lets the
 * user delete the session outright.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    db: PulseLoopDatabase,
) {
    var loading by remember { mutableStateOf(true) }
    var session by remember { mutableStateOf<ActivitySessionEntity?>(null) }
    var hrSamples by remember { mutableStateOf<List<Double>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) {
        loading = true
        val loaded = db.activitySessionDao().byId(sessionId)
        session = loaded
        hrSamples = if (loaded != null) {
            db.measurementDao()
                .range(MeasurementKind.HEART_RATE.name, loaded.startedAt, loaded.endedAt ?: System.currentTimeMillis())
                .map { it.value }
        } else emptyList()
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back")
            }
            Text(
                session?.type ?: "Workout",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            session == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Workout not found", style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> {
                val wo = session!!
                val elapsedSeconds = wo.endedAt?.let { (it - wo.startedAt) / 1000 }?.toInt() ?: 0
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                val startedAt = Instant.ofEpochMilli(wo.startedAt).atZone(ZoneId.systemDefault())
                val dateText = startedAt.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
                val timeText = startedAt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                StatRow("Date", "$dateText · $timeText")
                                StatRow("Duration", "%d:%02d".format(minutes, seconds))
                                StatRow("Calories", wo.calories?.let { "%.0f kcal".format(it) } ?: "--")
                                if (wo.useGps) {
                                    StatRow("Distance", wo.distanceMeters?.let { "%.2f km".format(it / 1000) } ?: "--")
                                }
                                if (wo.avgHeartRate != null) StatRow("Avg HR", "%.0f bpm".format(wo.avgHeartRate))
                                if (wo.minHeartRate != null) StatRow("Min HR", "%.0f bpm".format(wo.minHeartRate))
                                if (wo.maxHeartRate != null) StatRow("Max HR", "%.0f bpm".format(wo.maxHeartRate))
                            }
                        }
                    }

                    if (hrSamples.isNotEmpty()) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        "Heart Rate",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    SimpleLineChart(
                                        points = hrSamples,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Workout")
                        }
                    }
                }

                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text("Delete Workout?") },
                        text = { Text("This will permanently remove this workout session. This can't be undone.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteDialog = false
                                scope.launch {
                                    db.activitySessionDao().delete(sessionId)
                                    onBack()
                                }
                            }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

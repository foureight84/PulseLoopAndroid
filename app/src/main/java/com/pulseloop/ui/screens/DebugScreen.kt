package com.pulseloop.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.diagnostics.DiagnosticsExporter
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ported from DebugView.swift.
 * Developer diagnostics: raw packet trace + DB stats + export + live event log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    packetCount: Int = 0,
    dbStats: Map<String, Int> = emptyMap(),
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liveEvents = remember { mutableStateListOf<LiveEventEntry>() }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Subscribe to live events from the ring
    LaunchedEffect(Unit) {
        PulseEventBus.events.collect { event ->
            val dir = when (event) {
                is PulseEvent.RawPacket -> if (event.direction == com.pulseloop.ring.PacketDirection.INCOMING) "↓" else "↑"
                else -> "↓"  // all decoded events come from the ring
            }
            val entry = LiveEventEntry(
                time = timeFmt.format(Date()),
                direction = dir,
                label = labelFor(event),
                detail = detailFor(event),
                color = colorFor(event),
            )
            liveEvents.add(0, entry)
            if (liveEvents.size > 100) liveEvents.removeAt(liveEvents.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Ring Event Log (command-response, not streaming) ───────
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Ring Events", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${liveEvents.size} captured", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "↓ = ring → app (response)   ↑ = app → ring (command). Events only arrive when the app queries the ring.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        // Force command buttons — prove command-response behavior
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val coordinator = com.pulseloop.service.RingSyncCoordinator(
                                            com.pulseloop.ring.RingBLEClient(context), PulseLoopDatabase.getInstance(context)
                                        )
                                        coordinator.syncNow()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("Sync Now", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = {
                                    val db = PulseLoopDatabase.getInstance(context)
                                    val bleClient = com.pulseloop.ring.RingBLEClient(context)
                                    scope.launch {
                                        if (bleClient.hasPermissions()) {
                                            bleClient.connectLastKnown()
                                            kotlinx.coroutines.delay(8000)
                                            bleClient.disconnect()
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("Connect + Query", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (liveEvents.isEmpty()) {
                            Text("No events yet. Sync the ring or trigger a measurement to see data.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp))
                        } else {
                            Spacer(Modifier.height(4.dp))
                            liveEvents.take(20).forEach { entry ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(entry.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                                    Text(entry.direction, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
                                    Icon(Icons.Filled.Circle, null, Modifier.size(8.dp), tint = entry.color)
                                    Spacer(Modifier.width(4.dp))
                                    Text(entry.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.width(80.dp))
                                    Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            // ── Raw Packets ─────────────────────────────────────────────
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Raw Packets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("$packetCount packets captured", style = MaterialTheme.typography.bodyMedium)
                        Text("(incoming/outgoing BLE frames)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Database ────────────────────────────────────────────────
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Database", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        dbStats.forEach { (table, count) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(table, style = MaterialTheme.typography.bodyMedium)
                                Text("$count rows", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // ── Diagnostics Export ──────────────────────────────────────
            item {
                // Default ON every time: the export is always privacy-safe unless the user
                // explicitly opts out for this export. Never persists "off".
                var maskSensitive by remember { mutableStateOf(true) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Export app, device, and wearable logs as JSON.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Mask sensitive data", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (maskSensitive)
                                        "Removes health values, ring serial & MAC addresses. Keeps models, opcodes & errors."
                                    else
                                        "OFF — includes full unmasked BLE frames (for protocol debugging only).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (maskSensitive) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error,
                                )
                            }
                            Switch(checked = maskSensitive, onCheckedChange = { maskSensitive = it })
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val db = PulseLoopDatabase.getInstance(context)
                                    try {
                                        val intent = DiagnosticsExporter.shareIntent(context, db, mask = maskSensitive)
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Share, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (maskSensitive) "Export Diagnostics" else "Export Full (Unmasked)")
                        }
                    }
                }
            }

            // ── App Info ────────────────────────────────────────────────
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("App Info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("PulseLoop v1.0.0 (Android)", style = MaterialTheme.typography.bodyMedium)
                        Text("Port from iOS · Open Source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── Live Event Helpers ──────────────────────────────────────────────────────

private data class LiveEventEntry(
    val time: String,
    val direction: String,
    val label: String,
    val detail: String,
    val color: Color,
)

private fun labelFor(event: PulseEvent): String = when (event) {
    is PulseEvent.HeartRateSample -> "HR"
    is PulseEvent.HeartRateComplete -> "HR Done"
    is PulseEvent.Spo2Result -> "SpO₂"
    is PulseEvent.Spo2Complete -> "SpO₂ Done"
    is PulseEvent.HistoryMeasurement -> when (event.kind) {
        com.pulseloop.ring.MeasurementKind.HEART_RATE -> "HR History"
        com.pulseloop.ring.MeasurementKind.SPO2 -> "SpO₂ History"
        com.pulseloop.ring.MeasurementKind.STRESS -> "Stress"
        com.pulseloop.ring.MeasurementKind.FATIGUE -> "Fatigue"
        com.pulseloop.ring.MeasurementKind.HRV -> "HRV"
        com.pulseloop.ring.MeasurementKind.TEMPERATURE -> "Temp"
        com.pulseloop.ring.MeasurementKind.BLOOD_PRESSURE_SYSTOLIC -> "BP Sys"
        com.pulseloop.ring.MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> "BP Dia"
        com.pulseloop.ring.MeasurementKind.BLOOD_SUGAR -> "Glucose"
    }
    is PulseEvent.BatteryLevel -> "Battery"
    is PulseEvent.ActivityUpdate -> "Activity"
    is PulseEvent.ActivityBucket -> "Act Bucket"
    is PulseEvent.SleepTimeline -> "Sleep"
    is PulseEvent.StressSample -> "Stress"
    is PulseEvent.HrvSample -> "HRV"
    is PulseEvent.TemperatureSample -> "Temp"
    is PulseEvent.DeviceStateChanged -> "BLE State"
    is PulseEvent.DeviceIdentified -> "Device ID"
    is PulseEvent.DeviceForgotten -> "Forgot"
    is PulseEvent.SyncProgress -> "Sync"
    is PulseEvent.FirmwareVersion -> "FW Ver"
    is PulseEvent.RawPacket -> "Raw Pkt"
    is PulseEvent.ActivitySyncReset -> "Act Reset"
}

private fun detailFor(event: PulseEvent): String = when (event) {
    is PulseEvent.HeartRateSample -> "${event.bpm} bpm"
    is PulseEvent.Spo2Result -> "${event.value}%"
    is PulseEvent.HistoryMeasurement -> "${event.value.toInt()} ${event.kind.unit}"
    is PulseEvent.BatteryLevel -> "${event.percent}%"
    is PulseEvent.ActivityUpdate -> "${event.steps} steps"
    is PulseEvent.ActivityBucket -> "${event.steps} st · ${String.format("%.0f", event.distanceMeters)}m"
    is PulseEvent.SleepTimeline -> "${event.stages.size} min"
    is PulseEvent.StressSample -> "${event.value}"
    is PulseEvent.HrvSample -> "${event.value} ms"
    is PulseEvent.TemperatureSample -> "${"%.1f".format(event.celsius)}°C"
    is PulseEvent.DeviceStateChanged -> event.state.name
    is PulseEvent.DeviceIdentified ->
        com.pulseloop.wearables.WearableModel.model(event.wearableModelID)?.displayName
            ?: event.deviceType.displayName
    is PulseEvent.SyncProgress -> event.stage
    is PulseEvent.FirmwareVersion -> "V${event.version ?: 0}"
    is PulseEvent.RawPacket -> hexDump(event.data) + " ${event.direction.name}"
    else -> ""
}

private fun hexDump(data: ByteArray): String {
    if (data.size <= 8) return data.joinToString(" ") { "%02x".format(it) }
    return data.take(8).joinToString(" ") { "%02x".format(it) } + "…"
}

private fun colorFor(event: PulseEvent): Color = when (event) {
    is PulseEvent.HeartRateSample, is PulseEvent.HeartRateComplete -> Color(0xFFE53935)
    is PulseEvent.Spo2Result -> Color(0xFF1E88E5)
    is PulseEvent.HistoryMeasurement -> when (event.kind) {
        com.pulseloop.ring.MeasurementKind.HEART_RATE -> Color(0xFFE53935)
        com.pulseloop.ring.MeasurementKind.SPO2 -> Color(0xFF1E88E5)
        com.pulseloop.ring.MeasurementKind.STRESS -> Color(0xFF8E24AA)
        com.pulseloop.ring.MeasurementKind.FATIGUE -> Color(0xFFFB8C00)
        com.pulseloop.ring.MeasurementKind.HRV -> Color(0xFF43A047)
        com.pulseloop.ring.MeasurementKind.TEMPERATURE -> Color(0xFFFF7043)
        com.pulseloop.ring.MeasurementKind.BLOOD_PRESSURE_SYSTOLIC -> Color(0xFFE91E63)
        com.pulseloop.ring.MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> Color(0xFFF06292)
        com.pulseloop.ring.MeasurementKind.BLOOD_SUGAR -> Color(0xFF00BCD4)
    }
    is PulseEvent.BatteryLevel -> Color(0xFF66BB6A)
    is PulseEvent.ActivityUpdate, is PulseEvent.ActivityBucket -> Color(0xFFFF9800)
    is PulseEvent.SleepTimeline -> Color(0xFF7E57C2)
    is PulseEvent.StressSample -> Color(0xFF8E24AA)
    is PulseEvent.HrvSample -> Color(0xFF43A047)
    is PulseEvent.TemperatureSample -> Color(0xFFFF7043)
    is PulseEvent.DeviceStateChanged -> Color(0xFF90A4AE)
    is PulseEvent.DeviceIdentified -> Color(0xFF42A5F5)
    is PulseEvent.SyncProgress -> Color(0xFF78909C)
    is PulseEvent.FirmwareVersion -> Color(0xFF90A4AE)
    else -> Color(0xFFBDBDBD)
}

package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseloop.BuildConfig
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.RawPacketEntity
import com.pulseloop.data.entity.WearableLogEntity
import com.pulseloop.diagnostics.DiagnosticsExporter
import com.pulseloop.diagnostics.DiagnosticsViewModel
import com.pulseloop.ring.PacketDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ported from DebugView.swift.
 * Developer diagnostics: ring event log + raw packet trace + DB inspection + export.
 *
 * Everything shown here is read from Room through [DiagnosticsViewModel]. Capture itself is done by
 * the application-scoped `DiagnosticsSubscriber`, so leaving this screen loses nothing, and its
 * actions drive the app's own ring connection rather than a second client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(viewModel: DiagnosticsViewModel, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packets by viewModel.packets.collectAsState()
    val packetCount by viewModel.packetCount.collectAsState()
    val events by viewModel.events.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val status by viewModel.status.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    var expandedTable by remember { mutableStateOf<String?>(null) }
    var showPackets by remember { mutableStateOf(false) }
    var showAllEvents by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Ring Event Log ──────────────────────────────────────────
            item {
                DebugCard("Ring Events", trailing = "${events.size} recent") {
                    Text(
                        "Connection, command and reply events, stored as they happen. Times are local.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { viewModel.sync(connect = false) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Sync Now", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { viewModel.sync(connect = true) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Connect + Query", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (status.isNotBlank()) {
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (events.isEmpty()) {
                        Text("No events yet. Sync the ring or trigger a measurement to see data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp))
                    } else {
                        events.take(if (showAllEvents) 40 else 5).forEach { EventRow(it, timeFmt) }
                        TextButton(onClick = { showAllEvents = !showAllEvents }) {
                            Text(if (showAllEvents) "Show fewer events" else "Show more events")
                        }
                    }
                }
            }

            // ── Raw Packets ─────────────────────────────────────────────
            item {
                DebugCard("Raw Packets") {
                    Text("$packetCount packets captured", style = MaterialTheme.typography.bodyMedium)
                    Text("↓ = ring → app (response)   ↑ = app → ring (command)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showPackets = !showPackets }) {
                        Text(if (showPackets) "Hide packets" else "Show recent packets")
                    }
                    if (showPackets) packets.forEach { PacketRow(it, timeFmt) }
                }
            }

            // ── Database ────────────────────────────────────────────────
            item {
                DebugCard("Database") {
                    Text("Row counts include demo and real rows. Tap a table to see its newest rows. " +
                        "Stored health rows are never included in a diagnostics export.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    counts.forEach { count ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                expandedTable = if (expandedTable == count.name) null else count.name
                                viewModel.selectTable(count.name)
                            }) {
                                Text(count.name, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("${count.count} rows", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (expandedTable == count.name) {
                            when (count.name) {
                                "raw_packets" -> Text("See Raw Packets above.", style = MaterialTheme.typography.bodySmall)
                                "wearable_logs" -> Text("See Ring Events above.", style = MaterialTheme.typography.bodySmall)
                                else -> {
                                    val selected = rows.filter { it.tableName == count.name }
                                    if (selected.isEmpty()) Text("No rows to display.", style = MaterialTheme.typography.bodySmall)
                                    selected.forEach { row ->
                                        Text("${timeFmt.format(Date(row.timestamp))} · ${row.detail}",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
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
                var exportError by remember { mutableStateOf<String?>(null) }
                DebugCard("Diagnostics") {
                    Text("Export app, device, and wearable logs as JSON.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val db = PulseLoopDatabase.getInstance(context)
                                    context.startActivity(DiagnosticsExporter.shareIntent(context, db, mask = maskSensitive))
                                    exportError = null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    exportError = "Could not export diagnostics."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (maskSensitive) "Export Diagnostics" else "Export Full (Unmasked)")
                    }
                    exportError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            // ── App Info ────────────────────────────────────────────────
            item {
                DebugCard("App Info") {
                    Text("PulseLoop ${BuildConfig.VERSION_NAME} (Android)", style = MaterialTheme.typography.bodyMedium)
                    Text("Port from iOS · Open Source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DebugCard(
    title: String,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                trailing?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun EventRow(event: WearableLogEntity, timeFmt: SimpleDateFormat) {
    val isError = event.levelRaw == "ERROR"
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Filled.Circle, null,
            Modifier.padding(top = 5.dp).size(8.dp),
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${timeFmt.format(Date(event.timestamp))} · ${event.deviceTypeRaw} · ${event.message}",
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PacketRow(packet: RawPacketEntity, timeFmt: SimpleDateFormat) {
    val arrow = if (packet.directionRaw == PacketDirection.INCOMING.name) "↓" else "↑"
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "$arrow ${timeFmt.format(Date(packet.timestamp))} · ${packet.deviceTypeRaw.orEmpty()} · ${packet.decodedKind}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(packet.hexPayload, style = MaterialTheme.typography.bodySmall)
        packet.decodedJSON?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

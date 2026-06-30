package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.navigation.NavController
import com.pulseloop.service.HeartRateZones
import com.pulseloop.service.SleepCoach
import com.pulseloop.service.SleepFormat
import com.pulseloop.service.SleepInsights
import com.pulseloop.service.SleepScore
import com.pulseloop.service.SleepScoreResult
import com.pulseloop.ui.components.LegendDot
import com.pulseloop.ui.components.MetricThresholdTable
import com.pulseloop.ui.components.MetricThresholds
import com.pulseloop.ui.components.MetricTile
import com.pulseloop.ui.components.SimpleDualLineChart
import com.pulseloop.ui.components.SimpleLineChart
import com.pulseloop.ui.components.ThresholdBar
import com.pulseloop.ui.components.TrendChart
import com.pulseloop.ui.components.bpZone
import com.pulseloop.ui.viewmodels.*
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Today dashboard — ported from TodayView.swift.
 * Shows daily summary: steps, calories, distance, active minutes,
 * heart rate, SpO2, plus a mini sparkline for each.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun TodayScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: TodayViewModel? = null,
    coordinator: com.pulseloop.service.RingSyncCoordinator? = null,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(TodayViewModel.TodayState()) })
    val syncPct = coordinator?.syncProgress?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val units = ApiKeyStore(LocalContext.current).resolvedUnitSystem
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            scope.launch {
                coordinator?.pullToRefresh()
                kotlinx.coroutines.delay(1500)
                isRefreshing = false
            }
        },
    )

    Box(Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Today", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    // Connection status
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(
                            Icons.Filled.Bluetooth, null,
                            modifier = Modifier.size(14.dp),
                            tint = if (state.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (syncPct != null) (if (syncPct > 0) "Syncing $syncPct%" else "Syncing…")
                            else if (state.isConnected) "Connected · ${state.batteryPercent}%"
                            else if (state.deviceState == "CONNECTING") "Connecting…"
                            else "Disconnected",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Last data refresh indicator
                        if (state.lastUpdated > 0) {
                            val secondsAgo = (System.currentTimeMillis() - state.lastUpdated) / 1000
                            Text(
                                if (secondsAgo < 5) "just now" else "${secondsAgo}s ago",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                Row {
                    if (navController != null) {
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(Icons.Filled.Settings, "Settings")
                        }
                        IconButton(onClick = {
                            if (state.isConnected) {
                                // Already connected — sync now
                                scope.launch { coordinator?.syncNow() }
                            } else {
                                navController.navigate("pairing")
                            }
                        }) {
                            Icon(
                                if (state.isConnected) Icons.Filled.Sync else Icons.Filled.BluetoothConnected,
                                contentDescription = if (state.isConnected) "Sync" else "Pair Ring",
                                tint = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "Steps",
                    value = formatNumber(state.steps),
                    unit = "steps",
                    trend = null,
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "Calories",
                    value = state.calories?.let { formatNumber(it.toInt()) } ?: "--",
                    unit = "kcal",
                    trend = null,
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "Distance",
                    value = state.distanceMeters?.let { "%.1f".format(UnitConverter.distance(it, units)) } ?: "--",
                    unit = UnitConverter.distanceUnit(units),
                    trend = null,
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    label = "Active",
                    value = state.activeMinutes?.toString() ?: "--",
                    unit = "min",
                    trend = null,
                )
            }
        }
        // Distance + Active come from VM data above (no duplicate)

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Heart Rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.heartRate?.let { "$it bpm" } ?: "-- bpm",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(state.restingHR?.let { "Resting · %.0f bpm".format(it) } ?: "No recent data",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("SpO₂", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.spo2?.let { "$it%" } ?: "--%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(if (state.spo2 != null) "Latest reading" else "No recent data",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Modifier.weight(1f), "Sleep", state.sleepMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "--", "last night", null)
                MetricTile(Modifier.weight(1f), "Battery", "${state.batteryPercent}%", if (state.isConnected) "connected" else "--", null)
            }
        }

        // Blood pressure (shown whenever the ring supports it)
        if (state.supportsBP) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Blood Pressure", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val hasBp = state.bloodPressureSystolic != null || state.bloodPressureDiastolic != null
                        Text(
                            "${state.bloodPressureSystolic ?: "--"} / ${state.bloodPressureDiastolic ?: "--"}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (hasBp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(if (hasBp) "mmHg" else "No recent data — take a measurement",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Blood sugar (shown whenever the ring supports it)
        if (state.supportsGlucose) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Blood Sugar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.bloodSugar?.let { String.format("%.1f", it) } ?: "--",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (state.bloodSugar != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(if (state.bloodSugar != null) "mg/dL" else "No recent data",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            if (state.steps == null) {
                Text(
                    "No ring data yet — pair a ring to see your metrics",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * Vitals dashboard — ported from VitalsView.swift.
 * Shows historical trends: HR, SpO2, HRV, stress, temperature with real data from Room.
 */
@Composable
fun VitalsScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: VitalsViewModel? = null,
    coordinator: com.pulseloop.service.RingSyncCoordinator? = null,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(VitalsViewModel.VitalsState()) })
    val scope = rememberCoroutineScope()
    var measuring by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(0) }
    val measureSeconds = com.pulseloop.service.RingSyncCoordinator.COMBINED_MEASURE_SECONDS
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vitals", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Live measurements and trends", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Combined spot measurement (0x23): one tap captures BP, SpO₂, stress,
                // fatigue and blood sugar — the same flow the official app's "Measurement" button uses.
                // Only shown for rings that support BP or blood sugar (56ff/Jring).
                if (coordinator != null && (state.supportsBP || state.supportsGlucose)) {
                    Button(
                        enabled = !measuring,
                        onClick = {
                            measuring = true
                            remaining = measureSeconds
                            scope.launch {
                                val ticker = launch {
                                    while (remaining > 0) { kotlinx.coroutines.delay(1000); remaining-- }
                                }
                                try {
                                    coordinator.measureCombined()
                                } finally {
                                    ticker.cancel()
                                    remaining = 0
                                    measuring = false
                                    viewModel?.refreshNow()  // show the new reading immediately
                                }
                            }
                        },
                    ) {
                        Text(
                            if (measuring) "Measuring… ${remaining}s" else "Measure",
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
            }
            if (measuring) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { ((measureSeconds - remaining).toFloat() / measureSeconds).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Keep still — measuring blood pressure, SpO₂, stress, fatigue & blood sugar…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Heart Rate
        item {
            Card(Modifier.fillMaxWidth().clickable { navController?.navigate("vitals/hr") }) {
                Column(Modifier.padding(16.dp)) {
                    Text("Heart Rate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    if (state.hrSamples.isNotEmpty()) {
                        val avg = state.hrSamples.average().toInt()
                        val min = state.hrSamples.min().toInt()
                        val max = state.hrSamples.max().toInt()
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(state.latestHr?.toString() ?: "--", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                            Text(" bpm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        val restingText = state.restingHr?.let { " · Resting: %.0f".format(it) } ?: ""
                        Text("Range: $min – $max · Avg: $avg$restingText bpm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MetricThresholdTable.forKind(MeasurementKind.HEART_RATE)?.let { th ->
                            Spacer(Modifier.height(8.dp))
                            ThresholdBar(value = state.latestHr?.toDouble(), thresholds = th)
                        }
                        Spacer(Modifier.height(12.dp))
                        SimpleLineChart(points = state.hrSamples, color = androidx.compose.ui.graphics.Color(0xFFE53935), thresholds = MetricThresholdTable.forKind(MeasurementKind.HEART_RATE))
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("--", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" bpm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text("No HR samples yet — sync your ring to start your trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // SpO2
        item {
            Card(Modifier.fillMaxWidth().clickable { navController?.navigate("vitals/spo2") }) {
                Column(Modifier.padding(16.dp)) {
                    Text("Blood Oxygen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    if (state.spo2Samples.isNotEmpty()) {
                        val avg = state.spo2Samples.average().toInt()
                        val min = state.spo2Samples.min().toInt()
                        val max = state.spo2Samples.max().toInt()
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(state.latestSpo2?.toString() ?: "--", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                            Text(" %", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text("Range: $min – $max% · Avg: $avg%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MetricThresholdTable.forKind(MeasurementKind.SPO2)?.let { th ->
                            Spacer(Modifier.height(8.dp))
                            ThresholdBar(value = state.latestSpo2?.toDouble(), thresholds = th)
                        }
                        Spacer(Modifier.height(12.dp))
                        SimpleLineChart(points = state.spo2Samples, color = androidx.compose.ui.graphics.Color(0xFF1E88E5), thresholds = MetricThresholdTable.forKind(MeasurementKind.SPO2))
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("--", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" %", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text("No SpO₂ samples yet — take a reading to start your trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Stress (capability-gated)
        if (state.supportsStress) {
            item {
                Card(Modifier.fillMaxWidth().clickable { navController?.navigate("vitals/stress") }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Stress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val latest = state.latestStress?.toInt() ?: 0
                        // The ring's valid stress range starts at 10 (official bands: 10-20 excellent …),
                        // so treat anything below that as no reading rather than a misleading "Excellent".
                        if (state.stressSamples.isNotEmpty() && latest >= 10) {
                            // Bands match the official app's "Mental stress" interpretation
                            // (<20 excellent, <40 good, <60 normal, <80 poor, else very poor).
                            val label = when {
                                latest < 20 -> "Excellent"
                                latest < 40 -> "Good"
                                latest < 60 -> "Normal"
                                latest < 80 -> "Poor"
                                else -> "Very Poor"
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(label, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                Text("  $latest / 100", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            MetricThresholdTable.forKind(MeasurementKind.STRESS)?.let { th ->
                                Spacer(Modifier.height(8.dp))
                                ThresholdBar(value = state.latestStress, thresholds = th)
                            }
                            Spacer(Modifier.height(12.dp))
                            SimpleLineChart(points = state.stressSamples, color = androidx.compose.ui.graphics.Color(0xFF8E24AA), thresholds = MetricThresholdTable.forKind(MeasurementKind.STRESS))
                        } else {
                            Text("No stress data yet — take a measurement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Fatigue (capability-gated) — TYPE_FATIGUE (byte[5]) from the combined measurement
        if (state.supportsFatigue) {
            item {
                Card(Modifier.fillMaxWidth().clickable { navController?.navigate("vitals/fatigue") }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Fatigue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val latest = state.latestFatigue?.toInt() ?: 0
                        // The ring's valid fatigue range starts at 10 (official bands: 10-20 excellent …),
                        // so treat anything below that as no reading rather than a misleading "Excellent".
                        if (state.fatigueSamples.isNotEmpty() && latest >= 10) {
                            // Bands match the official app's "Body fatigue index" interpretation
                            // (<20 excellent, <45 good, <60 normal, <80 poor, else very poor).
                            val label = when {
                                latest < 20 -> "Excellent"
                                latest < 45 -> "Good"
                                latest < 60 -> "Normal"
                                latest < 80 -> "Poor"
                                else -> "Very Poor"
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(label, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                Text("  $latest / 100", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            MetricThresholdTable.forKind(MeasurementKind.FATIGUE)?.let { th ->
                                Spacer(Modifier.height(8.dp))
                                ThresholdBar(value = state.latestFatigue, thresholds = th)
                            }
                            Spacer(Modifier.height(12.dp))
                            SimpleLineChart(points = state.fatigueSamples, color = androidx.compose.ui.graphics.Color(0xFFFB8C00), thresholds = MetricThresholdTable.forKind(MeasurementKind.FATIGUE))
                        } else {
                            Text("No fatigue data yet — take a measurement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // HRV (capability-gated)
        if (state.supportsHrv) {
            item {
                Card(Modifier.fillMaxWidth().clickable { navController?.navigate("vitals/hrv") }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("HRV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val hrvVal = state.latestHrv
                        if (hrvVal != null && state.hrvSamples.isNotEmpty()) {
                            val hrvAvg = state.hrvSamples.average().toInt()
                            val hrvMin = state.hrvSamples.min().toInt()
                            val hrvMax = state.hrvSamples.max().toInt()
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(String.format("%.0f", hrvVal), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                Text(" ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            Text("Range: $hrvMin – $hrvMax · Avg: $hrvAvg ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MetricThresholdTable.forKind(MeasurementKind.HRV)?.let { th ->
                                Spacer(Modifier.height(8.dp))
                                ThresholdBar(value = state.latestHrv, thresholds = th)
                            }
                            Spacer(Modifier.height(12.dp))
                            SimpleLineChart(points = state.hrvSamples, color = androidx.compose.ui.graphics.Color(0xFF43A047), thresholds = MetricThresholdTable.forKind(MeasurementKind.HRV))
                        } else {
                            Text("No HRV data yet — HRV builds up over a few hours of wear.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Temperature (capability-gated)
        if (state.supportsTemp) {
            item {
                Card(Modifier.fillMaxWidth().clickable { navController?.navigate("vitals/temp") }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Skin Temperature", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val tempVal = state.latestTemp
                        if (tempVal != null && state.tempSamples.isNotEmpty()) {
                            val units = com.pulseloop.settings.ApiKeyStore(LocalContext.current).resolvedUnitSystem
                            val displayTemp = com.pulseloop.settings.UnitConverter.temperature(tempVal, units)
                            val displayUnit = com.pulseloop.settings.UnitConverter.temperatureUnit(units)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(String.format("%.1f", displayTemp), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                Text(" $displayUnit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            val tempAvg = state.tempSamples.average()
                            val tempMin = state.tempSamples.min()
                            val tempMax = state.tempSamples.max()
                            Text(
                                String.format("Range: %.1f – %.1f · Avg: %.1f $displayUnit",
                                    UnitConverter.temperature(tempMin, units),
                                    UnitConverter.temperature(tempMax, units),
                                    UnitConverter.temperature(tempAvg, units)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MetricThresholdTable.forKind(MeasurementKind.TEMPERATURE)?.let { th ->
                                Spacer(Modifier.height(8.dp))
                                ThresholdBar(value = tempVal, thresholds = th)
                            }
                            Spacer(Modifier.height(12.dp))
                            SimpleLineChart(points = state.tempSamples, color = androidx.compose.ui.graphics.Color(0xFFFF7043))
                        } else {
                            Text("No temperature data yet — temperature trends appear after overnight wear.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Blood Pressure (capability-gated)
        if (state.supportsBP) {
            item {
                Card(Modifier.fillMaxWidth().clickable { navController?.navigate("vitals/bp") }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Blood Pressure", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val bpSysColor = androidx.compose.ui.graphics.Color(0xFF5E35B1)
                        val bpDiaColor = androidx.compose.ui.graphics.Color(0xFFB39DDB)
                        if (state.bpSystolic != null || state.bpDiastolic != null) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${state.bpSystolic ?: "--"} / ${state.bpDiastolic ?: "--"}", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                Text(" mmHg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            if (state.bpSysSamples.isNotEmpty()) {
                                val sysMin = state.bpSysSamples.min().toInt()
                                val sysMax = state.bpSysSamples.max().toInt()
                                val sysAvg = state.bpSysSamples.average().toInt()
                                val diaAvg = state.bpDiaSamples.average().takeIf { state.bpDiaSamples.isNotEmpty() }?.toInt()
                                Text(
                                    "Sys $sysMin – $sysMax · Avg $sysAvg${diaAvg?.let { "/$it" } ?: ""} mmHg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // BP threshold bar: position at systolic, zone from worse of sys/dia
                                MetricThresholdTable.forKind(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC)?.let { th ->
                                    Spacer(Modifier.height(8.dp))
                                    ThresholdBar(
                                        value = state.bpSystolic?.toDouble(),
                                        thresholds = th,
                                        overrideZone = bpZone(state.bpSystolic?.toDouble(), state.bpDiastolic?.toDouble()),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                SimpleDualLineChart(
                                    seriesA = state.bpSysSamples,
                                    seriesB = state.bpDiaSamples,
                                    colorA = bpSysColor,
                                    colorB = bpDiaColor,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    LegendDot("Systolic", bpSysColor)
                                    LegendDot("Diastolic", bpDiaColor)
                                }
                            }
                        } else {
                            Text("No blood pressure data yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (measuring) {
                            Text("Measuring… updates when complete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        // Blood Sugar (capability-gated)
        if (state.supportsGlucose) {
            item {
                Card(Modifier.fillMaxWidth().clickable { navController?.navigate("vitals/glucose") }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Blood Sugar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        if (state.bloodSugar != null) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(String.format("%.1f", state.bloodSugar), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                Text(" mg/dL", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            if (state.glucoseSamples.isNotEmpty()) {
                                val gMin = state.glucoseSamples.min()
                                val gMax = state.glucoseSamples.max()
                                val gAvg = state.glucoseSamples.average()
                                Text(
                                    String.format("Range: %.1f – %.1f · Avg %.1f mg/dL", gMin, gMax, gAvg),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                MetricThresholdTable.forKind(MeasurementKind.BLOOD_SUGAR)?.let { th ->
                                    Spacer(Modifier.height(8.dp))
                                    ThresholdBar(value = state.bloodSugar, thresholds = th)
                                }
                                Spacer(Modifier.height(12.dp))
                                SimpleLineChart(points = state.glucoseSamples, color = androidx.compose.ui.graphics.Color(0xFF00897B), thresholds = MetricThresholdTable.forKind(MeasurementKind.BLOOD_SUGAR))
                            }
                        } else {
                            Text("No blood sugar data yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (measuring) {
                            Text("Measuring… updates when complete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * Sleep dashboard — ported from SleepView.swift.
 */
@Composable
fun SleepScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: SleepViewModel? = null,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(SleepViewModel.SleepState()) })
    val lastNight = state.lastNight
    val totalHr = lastNight?.totalMinutes?.let { it / 60 }
    val totalMin = lastNight?.totalMinutes?.let { it % 60 }
    val timeStr = if (totalHr != null) "${totalHr}h ${totalMin}m" else "--"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Sleep", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                if (navController != null) {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, "Settings")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Last Night card ─────────────────────────────────────────
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Last Night", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(timeStr, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    if (lastNight != null) {
                        Text(
                            "${SleepFormat.clockTime(lastNight.startAt)} – ${SleepFormat.clockTime(lastNight.endAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("No sleep data yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── Sleep Score card ─────────────────────────────────────────
        state.score?.let { s: SleepScoreResult ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Sleep Score", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("${s.score} / 100", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        Text(s.label.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StagePill("Deep", "${s.deepPct}%", Color(0xFF7C4DFF))
                            StagePill("Light", "${s.lightPct}%", Color(0xFF64B5F6))
                            if (s.awakePct != null) StagePill("Awake", "${s.awakePct}%", Color(0xFFFF8A65))
                        }
                    }
                }
            }
        }

        // ── Coach insight card ───────────────────────────────────────
        state.coach?.let { c: SleepCoach ->
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(c.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(c.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.chips.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                c.chips.forEach { chip: String ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text(chip, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Recent nights ────────────────────────────────────────────
        if (state.recentSessions.size > 1) {
            item {
                Text("Recent Nights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            state.recentSessions.drop(1).forEach { session ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                java.time.Instant.ofEpochMilli(session.date).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text("${session.totalMinutes / 60}h ${session.totalMinutes % 60}m", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** Small label used as an inline sleep stage badge. */
@Composable
private fun StagePill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StageBadge(label: String, duration: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Text(duration, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Activity dashboard — ported from ActivityView.swift.
 */
@Composable
fun ActivityScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: ActivityViewModel? = null,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(ActivityViewModel.ActivityState()) })
    val today = state.recentDays.firstOrNull()
    val todaySteps = today?.steps ?: 0
    val todayDistance = today?.distanceMeters ?: 0.0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                if (navController != null) {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, "Settings")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Modifier.weight(1f), "Steps", formatNumber(todaySteps), "today", null)
                MetricTile(Modifier.weight(1f), "Distance", if (todayDistance > 0) "%.1f".format(todayDistance / 1000) else "--", "km", null)
            }
        }

        if (state.recentWorkouts.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Recent Workouts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        state.recentWorkouts.forEach { wo ->
                            val elapsed = wo.endedAt?.let { (it - wo.startedAt) / 1000 }?.toInt() ?: 0
                            val min = elapsed / 60
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(wo.type, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${wo.distanceMeters?.let { "%.1f km · ".format(it / 1000) } ?: ""}${min} min",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { navController?.navigate("record") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("Start Workout")
            }
        }
    }
}

/**
 * Coach chat screen — ported from CoachView.swift.
 */
@Composable
fun CoachScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: CoachViewModel? = null,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember {
        mutableStateOf(CoachViewModel.CoachState(
            messages = listOf(CoachViewModel.ChatMessage("assistant",
                "Hi! I'm your PulseLoop coach. I can answer questions about your sleep, heart rate, activity, and recovery. What would you like to know?"))
        ))
    })
    var inputText by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Coach", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (navController != null) {
                IconButton(onClick = { navController.navigate("settings") }) {
                    Icon(Icons.Filled.Settings, "Settings")
                }
            }
        }

        // Messages
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages.size) { idx ->
                val msg = state.messages[idx]
                val isUser = msg.role == "user"
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (isUser) "You" else "🤖 Coach",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (state.isThinking) {
                item {
                    Text(
                        "Coach is thinking…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }

            if (state.error != null) {
                item {
                    Text(
                        "Error: ${state.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }

        // Input
        Surface(
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask your coach…") },
                    singleLine = false,
                    maxLines = 3,
                    enabled = !state.isThinking,
                )
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && viewModel != null) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !state.isThinking,
                ) {
                    Icon(Icons.Filled.Send, "Send")
                }
            }
        }
    }
}

private fun formatNumber(value: Int?): String {
    if (value == null) return "--"
    return "%,d".format(value)
}

// ──────────────────────── Vital Detail Screen ────────────────────────

/**
 * Detail screen for a single health metric — tapped from a Vitals panel.
 * Shows trend chart over Today/Week/Month, zone bar, stats, explainer,
 * and a non-medical disclaimer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalDetailScreen(
    metric: String,
    onBack: () -> Unit,
    db: com.pulseloop.data.PulseLoopDatabase,
    apiKeyStore: com.pulseloop.settings.ApiKeyStore? = null,
) {
    val context = LocalContext.current
    val units = apiKeyStore?.resolvedUnitSystem ?: com.pulseloop.settings.UnitSystem.METRIC
    val vm = remember { VitalDetailViewModel(db, metric, apiKeyStore, units) }
    val state by vm.state.collectAsState()

    val title = metricDisplayName(metric)

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back")
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.points.isEmpty()) {
            // Empty state
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "No ${title.lowercase()} data for this period",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sync your ring or take a measurement",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. Period selector — Today · Week · Month
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Period.entries.forEach { p ->
                            FilterChip(
                                selected = state.period == p,
                                onClick = { vm.setPeriod(p) },
                                label = { Text(p.label) },
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }

                // 2. Date navigator
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.prev() }) {
                            Icon(Icons.Filled.ChevronLeft, "Previous")
                        }
                        Text(
                            text = dateLabel(state.anchor, state.period),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(
                            onClick = { vm.next() },
                            enabled = vm.canGoForward(),
                        ) {
                            Icon(Icons.Filled.ChevronRight, "Next")
                        }
                    }
                }

                // 3. Trend chart (pinch to zoom, drag to scrub)
                item {
                    TrendChart(
                        points = state.points,
                        labels = state.labels,
                        color = state.thresholds?.zones?.firstOrNull()?.color
                            ?: MaterialTheme.colorScheme.primary,
                        secondary = state.secondary,
                        colorSecondary = androidx.compose.ui.graphics.Color(0xFFB39DDB),
                        legendPrimary = if (state.isBP) "Systolic" else null,
                        legendSecondary = if (state.isBP) "Diastolic" else null,
                        thresholds = state.thresholds,
                        timestamps = state.timestamps,
                        tooltipTimeFormatter = { ts -> tooltipTime(ts, state.period) },
                    )
                }

                // 4. Trend read
                item {
                    val (arrow, trendText) = trendCopy(state.trend, state.thresholds?.higherIsBetter ?: false)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = arrow,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = trendText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 5. Stat tiles — Latest · Avg · Min · Max
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatTile(
                            modifier = Modifier.weight(1f),
                            label = "Latest",
                            value = state.latest?.let { formatStat(it, metric) } ?: "--",
                            unit = state.thresholds?.unitLabel ?: "",
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            label = "Avg",
                            value = state.avg?.let { formatStat(it, metric) } ?: "--",
                            unit = state.thresholds?.unitLabel ?: "",
                        )
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatTile(
                            modifier = Modifier.weight(1f),
                            label = "Min",
                            value = state.min?.let { formatStat(it, metric) } ?: "--",
                            unit = state.thresholds?.unitLabel ?: "",
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            label = "Max",
                            value = state.max?.let { formatStat(it, metric) } ?: "--",
                            unit = state.thresholds?.unitLabel ?: "",
                        )
                    }
                }
                // Resting HR — heart rate only
                state.resting?.let { resting ->
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatTile(
                                modifier = Modifier.weight(1f),
                                label = "Resting",
                                value = formatStat(resting, metric),
                                unit = state.thresholds?.unitLabel ?: "",
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                // 6. Threshold bar + legend
                state.thresholds?.let { th ->
                    item {
                        val barValue = if (state.isBP) state.latest else (state.latest ?: state.avg)
                        ThresholdBar(
                            value = barValue,
                            thresholds = th,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            th.zones.forEach { zone ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .background(zone.color, CircleShape),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        zone.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // 7. Explainer
                item {
                    Text(
                        text = metricExplainer(metric),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 8. Disclaimer
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = DISCLAIMER_TEXT,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private const val DISCLAIMER_TEXT =
    "For reference only — not a substitute for medical devices, and not a medical diagnosis. " +
    "Wearable readings can vary; talk to a healthcare professional about any health concerns."

private fun metricDisplayName(metric: String): String = when (metric) {
    "hr"      -> "Heart Rate"
    "spo2"    -> "Blood Oxygen"
    "stress"  -> "Stress"
    "fatigue" -> "Fatigue"
    "hrv"     -> "HRV"
    "temp"    -> "Skin Temperature"
    "bp"      -> "Blood Pressure"
    "glucose" -> "Blood Sugar"
    else      -> metric
}

private fun metricExplainer(metric: String): String = when (metric) {
    "hr" -> "Resting heart rate reflects your cardiovascular fitness. Lower resting HR generally indicates better fitness, though individual baselines vary."
    "spo2" -> "Blood oxygen saturation (SpO₂) indicates how well your body absorbs oxygen. Most healthy people have levels above 95%."
    "stress" -> "Stress estimation is based on heart rate variability patterns. Higher values suggest more physiological stress; lower values reflect relaxation."
    "fatigue" -> "Fatigue index estimates your body's recovery state. Higher values suggest accumulated fatigue; lower values indicate you're well-rested."
    "hrv" -> "Heart rate variability (HRV) reflects autonomic nervous system balance. Higher HRV is typically associated with better recovery and resilience. HRV is highly personal — compare against your own baseline."
    "temp" -> "Skin temperature is not body temperature. It can reflect environmental exposure and circadian rhythms. Informational only."
    "bp" -> "Blood pressure readings consist of systolic (pressure during heartbeats) and diastolic (pressure between beats). Ranges are general wellness references, not diagnostic."
    "glucose" -> "Blood sugar levels fluctuate throughout the day. Ranges differ for fasting vs. post-meal measurements. Treat ranges as rough guides only."
    else -> ""
}

private fun dateLabel(anchor: Long, period: Period): String {
    val zone = java.time.ZoneId.systemDefault()
    val dt = java.time.Instant.ofEpochMilli(anchor).atZone(zone)
    return when (period) {
        Period.DAY -> dt.toLocalDate().toString()
        Period.WEEK -> {
            val end = dt.plusDays(6)
            "${dt.toLocalDate()} – ${end.toLocalDate()}"
        }
        Period.MONTH -> "${dt.month.name.take(3)} ${dt.year}"
    }
}

/** Precise time label for the chart scrub tooltip, formatted per aggregation period. */
private fun tooltipTime(ts: Long, period: Period): String {
    val z = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
    return when (period) {
        Period.DAY -> z.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault()))
        Period.WEEK -> "${z.dayOfWeek.name.take(3)} ${z.dayOfMonth}"
        Period.MONTH -> "${z.month.name.take(3)} ${z.dayOfMonth}"
    }
}

private fun formatStat(value: Double, metric: String): String = when (metric) {
    "temp" -> "%.1f".format(value)
    "glucose" -> "%.1f".format(value)
    "hrv" -> "%.0f".format(value)
    else -> "%.0f".format(value)
}

private fun trendCopy(trend: VitalDetailViewModel.Trend, higherIsBetter: Boolean): Pair<String, String> {
    val arrow = when (trend) {
        VitalDetailViewModel.Trend.UP   -> "↑"
        VitalDetailViewModel.Trend.DOWN -> "↓"
        VitalDetailViewModel.Trend.FLAT -> "→"
    }
    val text = when (trend) {
        VitalDetailViewModel.Trend.FLAT -> "Holding steady."
        VitalDetailViewModel.Trend.DOWN ->
            if (higherIsBetter) "Trending down vs the previous period."
            else "Trending down — generally a positive direction."
        VitalDetailViewModel.Trend.UP ->
            if (higherIsBetter) "Trending up — generally a positive direction."
            else "Trending up vs the previous period."
    }
    return Pair(arrow, text)
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "$label $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

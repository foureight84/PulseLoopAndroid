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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.pulseloop.data.entity.ActivityGpsPointEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.ActivityMeta
import com.pulseloop.ui.components.WorkoutMapView
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finished-workout summary — ported from RecordSummaryComponents.swift (WorkoutMetricsSections):
 * hero header (icon circle + type + date range), the 3-column hero band (distance/duration/pace
 * for GPS workouts; duration/active-min/calories indoors), a secondary stat grid, the GPS route
 * (Canvas polyline — Android divergence, no map SDK), and per-km splits with relative pace bars.
 */
@Composable
fun WorkoutSummaryScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val units = ApiKeyStore(context).resolvedUnitSystem
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<ActivitySessionEntity?>(null) }
    var points by remember { mutableStateOf<List<ActivityGpsPointEntity>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        session = db.activitySessionDao().byId(sessionId)
        points = db.activityGpsPointDao().forSession(sessionId)
            .filter { it.accepted }.sortedBy { it.timestamp }
    }

    val s = session ?: run {
        Box(Modifier.fillMaxSize().background(PulseColors.background))
        return
    }
    val durationSec = s.endedAt?.let { ((it - s.startedAt) / 1000 - s.totalPauseSeconds).toInt() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PulseColors.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, tint = PulseColors.textSecondary, onTap = onBack)
                Spacer(Modifier.weight(1f))
                CircleIconButton(Icons.Filled.Delete, tint = PulseColors.danger, onTap = { confirmDelete = true })
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(PulseColors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ActivityMeta.icon(s.type), null, tint = PulseColors.accent, modifier = Modifier.size(34.dp))
                }
                Text(ActivityMeta.label(s.type), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
                Text(dateRange(s), fontSize = 13.sp, color = PulseColors.textMuted)
            }
        }
        item { SummaryHeroBand(s, durationSec, units) }
        item { StatsGrid(s) }
        if (s.useGps) {
            item { WorkoutMapView(points = points) }
            item { SplitsCard(points, units) }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete workout?") },
            text = { Text("This removes the session and its recorded route. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        db.activitySessionDao().upsert(s.copy(statusRaw = "deleted", updatedAt = System.currentTimeMillis()))
                        onBack()
                    }
                }) { Text("Delete", color = PulseColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onTap: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** "Today · 6:05 – 6:43 PM" (WorkoutMetricsSections.dateRange). */
private fun dateRange(s: ActivitySessionEntity): String {
    val cal = Calendar.getInstance()
    val startCal = Calendar.getInstance().apply { timeInMillis = s.startedAt }
    val sameDay = cal.get(Calendar.YEAR) == startCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == startCal.get(Calendar.DAY_OF_YEAR)
    val yesterday = (Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }).let {
        it.get(Calendar.YEAR) == startCal.get(Calendar.YEAR) && it.get(Calendar.DAY_OF_YEAR) == startCal.get(Calendar.DAY_OF_YEAR)
    }
    val day = when {
        sameDay -> "Today"
        yesterday -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(s.startedAt))
    }
    val ended = s.endedAt ?: return day
    val start = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(s.startedAt))
    val end = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ended))
    return "$day · $start – $end"
}

/** Three large headline stats: GPS → distance/duration/pace; indoor → duration/active/calories. */
@Composable
private fun SummaryHeroBand(s: ActivitySessionEntity, durationSec: Int?, units: UnitSystem) {
    data class M(val value: String, val label: String, val tint: Color)
    val dur = durationSec?.let { ActivityMeta.duration(it) } ?: "—"
    val metrics = if (s.useGps) {
        val dist = s.distanceMeters?.let { "%.2f".format(UnitConverter.distance(it, units)) }
        val paceUnit = ActivityMeta.paceUnit(units)
        listOf(
            M(dist ?: "—", UnitConverter.distanceUnit(units).uppercase(), PulseColors.distance),
            M(dur, "DURATION", PulseColors.textPrimary),
            M(ActivityMeta.pace(s.distanceMeters, durationSec, units) ?: "—", "PACE $paceUnit", PulseColors.accent),
        )
    } else {
        listOf(
            M(dur, "DURATION", PulseColors.textPrimary),
            M(durationSec?.let { "${it / 60}" } ?: "—", "ACTIVE MIN", PulseColors.success),
            M(s.calories?.let { "${it.toInt()}" } ?: "—", "CALORIES", PulseColors.calories),
        )
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .padding(vertical = 18.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metrics.forEachIndexed { i, m ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = m.tint, maxLines = 1)
                Text(m.label, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, color = PulseColors.textMuted)
            }
            if (i != metrics.lastIndex) {
                Box(Modifier.width(1.dp).height(40.dp).background(PulseColors.borderSubtle))
            }
        }
    }
}

/** Secondary stat tiles, 3-per-row (WorkoutMetricsSections.statsGrid). */
@Composable
private fun StatsGrid(s: ActivitySessionEntity) {
    val stats = buildList {
        if (s.useGps) add("Calories" to (s.calories?.let { "${it.toInt()}" } ?: "—"))
        add("Avg HR" to (s.avgHeartRate?.let { "${it.toInt()}" } ?: "—"))
        add("Max HR" to (s.maxHeartRate?.let { "${it.toInt()}" } ?: "—"))
        add("Min HR" to (s.minHeartRate?.let { "${it.toInt()}" } ?: "—"))
        add("SpO₂" to (s.latestSpO2?.let { "${it.toInt()}%" } ?: "—"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    val shape = RoundedCornerShape(16.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(PulseColors.card)
                            .border(1.dp, PulseColors.borderSubtle, shape)
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
                        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, color = PulseColors.textMuted)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Per-km (or per-mi) splits with relative pace bars; fastest split highlighted (SplitsTable). */
@Composable
private fun SplitsCard(points: List<ActivityGpsPointEntity>, units: UnitSystem) {
    val splitMeters = if (units == UnitSystem.IMPERIAL) 1609.344 else 1000.0
    val splitLabel = if (units == UnitSystem.IMPERIAL) "MI" else "KM"
    val paceUnit = ActivityMeta.paceUnit(units)
    val splits = remember(points, splitMeters) { kmSplitSeconds(points, splitMeters) }
    if (splits.isEmpty()) return

    val fastest = splits.min()
    val slowest = splits.max()
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("SPLITS", fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, color = PulseColors.textMuted)
        splits.forEachIndexed { index, seconds ->
            val isFastest = seconds == fastest
            val frac = if (slowest > fastest) (seconds - fastest) / (slowest - fastest) else 0.0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "$splitLabel ${index + 1}",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = PulseColors.textSecondary,
                    modifier = Modifier.width(44.dp),
                )
                Box(Modifier.weight(1f).height(8.dp)) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(PulseColors.cardSoft))
                    Box(
                        Modifier
                            // Faster = longer bar (invert the fraction).
                            .fillMaxWidth((0.25f + 0.75f * (1f - frac.toFloat())).coerceIn(0.05f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(if (isFastest) PulseColors.accent else PulseColors.distance),
                    )
                }
                Text(
                    paceLabel(seconds, paceUnit),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (isFastest) PulseColors.accent else PulseColors.textPrimary,
                    modifier = Modifier.width(72.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/** Round to whole seconds *before* the min/sec split — 299.85 s must show 5:00 (iOS #43). */
private fun paceLabel(secPerUnit: Double, paceUnit: String): String {
    val total = Math.round(secPerUnit).toInt()
    return "%d:%02d %s".format(total / 60, total % 60, paceUnit)
}

/** Elapsed seconds per completed split, walking the GPS trace (kmSplitSeconds in Swift). */
private fun kmSplitSeconds(points: List<ActivityGpsPointEntity>, splitMeters: Double): List<Double> {
    if (points.size < 2) return emptyList()
    val splits = mutableListOf<Double>()
    var accumulated = 0.0
    var splitStartTs = points.first().timestamp
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        accumulated += haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        if (accumulated >= splitMeters) {
            splits.add((b.timestamp - splitStartTs) / 1000.0)
            splitStartTs = b.timestamp
            accumulated -= splitMeters
        }
    }
    return splits
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

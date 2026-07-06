package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.service.MetricKind
import com.pulseloop.service.MetricZone
import com.pulseloop.service.VitalsThresholdEngine
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.ui.components.*
import com.pulseloop.util.Formats
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.viewmodels.TodayViewModel
import com.pulseloop.ui.viewmodels.VitalCardState
import com.pulseloop.ui.viewmodels.VitalsCardFactory
import com.pulseloop.ui.viewmodels.VitalsViewModel
import com.pulseloop.ui.viewmodels.SleepViewModel
import com.pulseloop.ui.viewmodels.toCardInputs
import kotlinx.coroutines.launch

/**
 * Today dashboard — ported from TodayView.swift + TodayTiles.swift (iOS #35 tile re-layout).
 * Hero insight card, then a 2-column grid of half-width metric tiles (activity loop, sleep bar,
 * zone charts, gauges, dual BP gauge), then the coach message card. Tiles are capability-gated
 * like the Vitals screen; card interpretation math is factory-built off the composition path.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun TodayScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: TodayViewModel? = null,
    coordinator: com.pulseloop.service.RingSyncCoordinator? = null,
    vitalsViewModel: VitalsViewModel? = null,
    sleepViewModel: SleepViewModel? = null,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(TodayViewModel.TodayState()) })
    val vitals by (vitalsViewModel?.state?.collectAsState() ?: remember { mutableStateOf(VitalsViewModel.VitalsState()) })
    val sleep by (sleepViewModel?.state?.collectAsState() ?: remember { mutableStateOf(SleepViewModel.SleepState()) })
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // remember{}: the ApiKeyStore constructor does Keystore + encrypted-prefs I/O — far
    // too expensive to repeat on every recomposition of this state-collecting screen.
    val units = remember { ApiKeyStore(context) }.resolvedUnitSystem
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

    // Same factory the Vitals screen and the widget publisher use (iOS TodayStore reuses
    // VitalsCardFactory) — one shared Inputs construction via toCardInputs.
    val cards = remember(vitals, units) {
        val inputs = vitals.toCardInputs(units)
        MetricKind.entries.associateWith { VitalsCardFactory.card(it, inputs, vitals.profile) }
    }
    val hero = remember(state, sleep) { deriveHero(state, sleep) }

    // The tile list in iOS grid order, then chunked into 2-column rows.
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        add { m -> ActivityTileFor(state, units, m) { navController?.navigate("activity") } }
        add { m -> SleepTileFor(sleep, m) { navController?.navigate("sleep") } }
        add { m -> ChartTile(cards.getValue(MetricKind.HEART_RATE), vitals.hrSamples, m) { navController?.navigate("vitals/hr") } }
        add { m -> ChartTile(cards.getValue(MetricKind.SPO2), vitals.spo2Samples, m) { navController?.navigate("vitals/spo2") } }
        if (vitals.supportsHrv) add { m -> ChartTile(cards.getValue(MetricKind.HRV), vitals.hrvSamples, m) { navController?.navigate("vitals/hrv") } }
        if (vitals.supportsTemp) add { m -> ChartTile(cards.getValue(MetricKind.TEMPERATURE), vitals.tempSamples, m) { navController?.navigate("vitals/temp") } }
        if (vitals.supportsStress) add { m -> GaugeTile(cards.getValue(MetricKind.STRESS), m) { navController?.navigate("vitals/stress") } }
        if (vitals.supportsFatigue) add { m -> GaugeTile(cards.getValue(MetricKind.FATIGUE), m) { navController?.navigate("vitals/fatigue") } }
        if (vitals.supportsGlucose) add { m -> GaugeTile(cards.getValue(MetricKind.GLUCOSE), m) { navController?.navigate("vitals/glucose") } }
        if (vitals.supportsBP) add { m -> BloodPressureTile(cards.getValue(MetricKind.BLOOD_PRESSURE), vitals, m) { navController?.navigate("vitals/bp") } }
    }

    Box(Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeroInsightCard(hero.title, hero.summary, hero.chips) }

            items(tiles.chunked(2).size) { rowIndex ->
                val row = tiles.chunked(2)[rowIndex]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tile -> tile(Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                val summary = state.coachSummary
                CoachMessageCard(
                    headline = summary?.title ?: "Want a recap?",
                    body = summary?.body
                        ?: "Want a summary from the latest ring context? Tap to open the coach.",
                    chips = summary?.let { parseChips(it.chipsJSON) } ?: emptyList(),
                    onTap = { navController?.navigate("coach") },
                )
                Spacer(Modifier.height(64.dp))
            }
        }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

// ─────────────────────────── Tiles ───────────────────────────

@Composable
private fun ActivityTileFor(
    state: TodayViewModel.TodayState,
    units: com.pulseloop.settings.UnitSystem,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val distText = state.distanceMeters?.let { Formats.distance(UnitConverter.distance(it, units)) }
    ActivityTile(
        rings = listOf(
            ActivityRing(state.steps?.toDouble(), state.stepGoal.toDouble(), PulseColors.steps),
            ActivityRing(state.distanceMeters, state.distanceGoalMeters, PulseColors.distance),
            ActivityRing(state.calories, state.caloriesGoal.toDouble(), PulseColors.calories),
        ),
        values = buildList {
            state.steps?.let { add(ActivityTileValue("STEPS", Formats.count(it), PulseColors.steps)) }
            distText?.let { add(ActivityTileValue(UnitConverter.distanceUnit(units), it, PulseColors.distance)) }
            state.calories?.let { add(ActivityTileValue("CAL", Formats.count(it.toInt()), PulseColors.calories)) }
        },
        modifier = modifier,
        onTap = onTap,
    )
}

@Composable
private fun SleepTileFor(sleep: SleepViewModel.SleepState, modifier: Modifier, onTap: () -> Unit) {
    val session = sleep.lastNight
    val segments = remember(sleep) { buildSleepStageSegments(sleep.lastNightBlocks) }
    SleepTile(
        durationText = session?.let { Formats.hoursMinutes(it.totalMinutes) },
        segments = segments,
        score = sleep.score?.score ?: session?.score,
        modifier = modifier,
        onTap = onTap,
    )
}

/** Half-width chart tile: latest value + compact zone-colored line (TodayChartTile in Swift). */
@Composable
private fun ChartTile(card: VitalCardState, values: List<Double>, modifier: Modifier, onTap: () -> Unit) {
    TodayTile(label = card.title, color = ZonePalette.accent(card.metric), modifier = modifier, onTap = onTap) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                card.valueText,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = PulseColors.textPrimary,
                maxLines = 1,
            )
            card.unitText?.let {
                Text(
                    it, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = PulseColors.textMuted, maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (card.isEmpty) {
            Text(card.statusText, fontSize = 12.sp, color = PulseColors.textMuted)
        } else {
            ZoneLineChart(
                samples = card.samples,
                zones = card.zones,
                yDomain = card.yDomain,
                accent = ZonePalette.accent(card.metric),
                referenceBands = card.referenceBands,
                dashedRules = card.dashedRules,
                showPoints = card.metric == MetricKind.SPO2,
                showAxes = false,
                height = 56.dp,
            )
        }
    }
}

/** Half-width gauge tile: the Vitals ring gauge scaled down (TodayGaugeTile in Swift). */
@Composable
private fun GaugeTile(card: VitalCardState, modifier: Modifier, onTap: () -> Unit) {
    TodayTile(label = card.title, color = ZonePalette.accent(card.metric), modifier = modifier, onTap = onTap) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // A gauge needs only ONE reading — card.isEmpty is chart logic (<2 samples) and
            // would blank the glucose gauge (one fasting reading per day).
            if (card.valueText.toDoubleOrNull() == null) {
                Text(card.statusText, fontSize = 12.sp, color = PulseColors.textMuted)
            } else {
                // 108dp is what actually fits the 168dp tile after chrome (118 on iOS whose
                // padding differs); the gauge draws its inscribed square either way.
                VitalRingGauge(
                    value = card.valueText.toDoubleOrNull() ?: 0.0,
                    domain = card.yDomain,
                    zones = card.zones,
                    valueColor = card.statusToken.toColor(),
                    centerValue = card.valueText,
                    centerStatus = card.statusText,
                    size = 108.dp,
                    lineWidth = 11.dp,
                )
            }
        }
    }
}

/** Systolic + diastolic as two small gauges side by side (TodayBloodPressureTile in Swift). */
@Composable
private fun BloodPressureTile(
    card: VitalCardState,
    vitals: VitalsViewModel.VitalsState,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    TodayTile(label = card.title, color = PulseColors.bloodPressure, modifier = modifier, onTap = onTap) {
        val sys = vitals.bpSystolic?.toDouble()
        val dia = vitals.bpDiastolic?.toDouble()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (sys == null || dia == null) {
                Text("No reading", fontSize = 12.sp, color = PulseColors.textMuted)
            } else {
                val sysZones = VitalsThresholdEngine.zones(MetricKind.BLOOD_PRESSURE, vitals.profile)
                val diaZones = VitalsThresholdEngine.diastolicReferenceZones()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BpRingColumn("SYS", sys, 80.0..190.0, sysZones, PulseColors.bloodPressure, Modifier.weight(1f))
                    BpRingColumn("DIA", dia, 50.0..130.0, diaZones, PulseColors.bloodPressure.copy(alpha = 0.7f), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BpRingColumn(
    title: String,
    value: Double,
    domain: ClosedFloatingPointRange<Double>,
    zones: List<MetricZone>,
    fallback: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val valueColor = zones.firstOrNull {
        (it.lower ?: Double.NEGATIVE_INFINITY) <= value && value < (it.upper ?: Double.POSITIVE_INFINITY)
    }?.colorToken?.toColor() ?: fallback
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        VitalRingGauge(
            value = value,
            domain = domain,
            zones = zones,
            valueColor = valueColor,
            centerValue = "${value.toInt()}",
            size = 66.dp,
            lineWidth = 7.dp,
        )
        Text(
            title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp, color = PulseColors.textMuted,
        )
    }
}

// ─────────────────────────── Hero derivation (TodayInsights) ───────────────────────────

private data class Hero(val title: String, val summary: String, val chips: List<ToneChip>)

/** Ported from TodayInsights.deriveHero (TodayView.swift), minus the iOS calibration branch. */
private fun deriveHero(state: TodayViewModel.TodayState, sleep: SleepViewModel.SleepState): Hero {
    val steps = state.steps ?: return Hero(
        title = "Waiting for first sync",
        summary = "Sync your ring to start collecting movement, heart rate, blood oxygen, and recovery context.",
        chips = listOf(
            ToneChip("Baseline pending", ChipTone.WARN),
            ToneChip(if (state.heartRate == null) "HR pending" else "HR collected", ChipTone.NEUTRAL),
            ToneChip("Sleep pending", ChipTone.WARN),
        ),
    )

    val series = state.steps7d.map { it.toDouble() }
    val prior = series.dropLast(1)
    val base = if (prior.isNotEmpty() && prior.average() > 0) prior.average()
        else if (series.isNotEmpty()) series.average() else 0.0
    val stepsDelta = if (base == 0.0) 0 else (((steps - base) / base) * 100).toInt()

    val title = when {
        stepsDelta >= 20 -> "Building momentum"
        stepsDelta <= -20 -> "Take it easy"
        else -> "Steady build"
    }

    val hrStr = state.heartRate?.let { "$it bpm" } ?: "—"
    val night = sleep.lastNight
    val summary = if (night == null) {
        "You're at %,d steps. Sync after waking to add recovery context.".format(steps)
    } else {
        "You're at %,d steps, %dh %02dm of sleep, and your latest reading is %s.".format(
            steps, night.totalMinutes / 60, night.totalMinutes % 60, hrStr,
        )
    }

    val stepsTone = if (stepsDelta > 5) ChipTone.UP else if (stepsDelta < -5) ChipTone.DOWN else ChipTone.NEUTRAL
    val hrTone = when {
        state.heartRate == null -> ChipTone.WARN
        state.heartRate < 100 -> ChipTone.NEUTRAL
        else -> ChipTone.WARN
    }
    return Hero(
        title = title,
        summary = summary,
        chips = listOf(
            ToneChip(
                if (series.size > 1) "Steps ${if (stepsDelta >= 0) "+" else ""}$stepsDelta%" else "Steps collected",
                stepsTone,
            ),
            ToneChip(if (state.heartRate == null) "HR pending" else "HR collected", hrTone),
            ToneChip(if (night != null) "Sleep synced" else "Sleep pending", if (night != null) ChipTone.NEUTRAL else ChipTone.WARN),
        ),
    )
}

/** Coach summary chips arrive as a JSON string array. */
private fun parseChips(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as? kotlinx.serialization.json.JsonArray ?: return emptyList() }
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    } catch (_: Exception) {
        emptyList()
    }
}

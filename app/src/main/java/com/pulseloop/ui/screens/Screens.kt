package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.sp
import com.pulseloop.service.MetricKind
import com.pulseloop.ui.components.TrendChart
import com.pulseloop.ui.components.ZoneLineChart
import com.pulseloop.ui.components.VitalCard
import com.pulseloop.ui.components.VitalRingGauge
import com.pulseloop.ui.components.ZonePalette
import com.pulseloop.ui.components.toColor
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.viewmodels.*
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.ui.dashboard.CustomizeCardsButton
import com.pulseloop.ui.dashboard.DashboardCard
import com.pulseloop.ui.dashboard.EditableCard
import com.pulseloop.ui.dashboard.HiddenMetricsTray
import com.pulseloop.ui.dashboard.MetricPrefsStore
import com.pulseloop.ui.dashboard.MetricScope
import com.pulseloop.ui.dashboard.ReorderDoneBar
import com.pulseloop.ui.dashboard.rememberDashboardEditState
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * Vitals dashboard — ported from VitalsView.swift.
 * Shows historical trends: HR, SpO2, HRV, stress, temperature with real data from Room.
 */
@Composable
fun VitalsScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: VitalsViewModel? = null,
    coordinator: com.pulseloop.service.RingSyncCoordinator? = null,
    // Heights of the glass top/bottom bars this screen scrolls under (0 when standalone).
    topBarPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomBarPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(VitalsViewModel.VitalsState()) })
    val scope = rememberCoroutineScope()
    var measuring by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(0) }
    // Two measurement flavours:
    //  • combined (56ff/Jring): one 0x23 packet → BP + SpO₂ + stress + fatigue + blood sugar
    //  • spot (Colmi): sequential live HR + SpO₂ via the real-time command (0x69)
    // Colmi has no BP/glucose hardware, so it never qualifies for the combined flow.
    val combinedMode = state.supportsBP || state.supportsGlucose
    val spotMode = !combinedMode && (state.supportsManualHr || state.supportsManualSpo2)
    val measureSeconds = if (combinedMode)
        com.pulseloop.service.RingSyncCoordinator.COMBINED_MEASURE_SECONDS
    else
        com.pulseloop.service.RingSyncCoordinator.SPOT_MEASURE_SECONDS
    // Card chrome state (value / status / trend / footer) is factory-built once per state
    // emission, off the composition path — the cards below run no threshold math.
    // remember{}: the ApiKeyStore constructor does Keystore + encrypted-prefs I/O — too
    // expensive to repeat on every recomposition of this state-collecting screen.
    val vitalsScreenContext = LocalContext.current
    val vitalsUnits = remember { ApiKeyStore(vitalsScreenContext) }.resolvedUnitSystem
    val cards = remember(state, vitalsUnits) {
        val inputs = state.toCardInputs(vitalsUnits)
        MetricKind.entries.associateWith { metric -> VitalsCardFactory.card(metric, inputs, state.profile) }
    }

    // Per-metric visibility + user order (iOS #64), read reactively (also the iOS #70 reactivity).
    val prefsStore = remember { MetricPrefsStore.get(vitalsScreenContext) }
    val prefs by prefsStore.prefs.collectAsState()
    val editState = rememberDashboardEditState(MetricScope.VITALS, prefsStore)

    val supported: (DashboardCard) -> Boolean = { card ->
        when (card) {
            DashboardCard.HEART_RATE, DashboardCard.SPO2 -> true
            DashboardCard.BLOOD_PRESSURE -> state.supportsBP
            DashboardCard.HRV -> state.supportsHrv
            DashboardCard.STRESS -> state.supportsStress
            DashboardCard.FATIGUE -> state.supportsFatigue
            DashboardCard.GLUCOSE -> state.supportsGlucose
            DashboardCard.TEMPERATURE -> state.supportsTemp
            else -> false   // Activity/Sleep are Today-only
        }
    }
    val allSupported = DashboardCard.vitalsDefault.filter(supported)
    SideEffect { editState.configure(allSupported, DashboardCard.vitalsDefault) }

    val visibleKeys = allSupported.filter { !prefs.isHidden(it, MetricScope.VITALS) }.map { it.key }.toSet()
    val visibleOrdered: List<DashboardCard> = run {
        val base = if (editState.editing) editState.liveOrder
            else prefsStore.resolvedOrder(visibleKeys, DashboardCard.vitalsDefault.map { it.key }, MetricScope.VITALS)
                .mapNotNull { DashboardCard.fromKey(it) }
        base.filter { it.key in visibleKeys }
    }
    val hiddenCards = allSupported.filter { prefs.isHidden(it, MetricScope.VITALS) }

    // Dispatch a card id to its full-width Vitals card (order/visibility decided above).
    val vitalsCardFor: @Composable (DashboardCard) -> Unit = { card ->
        when (card) {
            DashboardCard.HEART_RATE -> VitalCard(
                state = cards.getValue(MetricKind.HEART_RATE),
                onTap = { navController?.navigate("vitals/hr") },
            ) {
                if (state.hrSamples.isNotEmpty()) {
                    val c = cards.getValue(MetricKind.HEART_RATE)
                    ZoneLineChart(
                        samples = c.samples, zones = c.zones, yDomain = c.yDomain,
                        accent = ZonePalette.accent(MetricKind.HEART_RATE),
                        referenceBands = c.referenceBands, dashedRules = c.dashedRules,
                    )
                } else {
                    Text("No HR samples yet — sync your ring to start your trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DashboardCard.SPO2 -> VitalCard(
                state = cards.getValue(MetricKind.SPO2),
                onTap = { navController?.navigate("vitals/spo2") },
            ) {
                if (state.spo2Samples.isNotEmpty()) {
                    val c = cards.getValue(MetricKind.SPO2)
                    ZoneLineChart(
                        samples = c.samples, zones = c.zones, yDomain = c.yDomain,
                        accent = ZonePalette.accent(MetricKind.SPO2),
                        referenceBands = c.referenceBands, dashedRules = c.dashedRules,
                        showPoints = true,
                    )
                } else {
                    Text("No SpO₂ samples yet — take a reading to start your trend.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DashboardCard.BLOOD_PRESSURE -> VitalCard(
                state = cards.getValue(MetricKind.BLOOD_PRESSURE),
                onTap = { navController?.navigate("vitals/bp") },
            ) {
                val sys = state.bpSystolic?.toDouble()
                val dia = state.bpDiastolic?.toDouble()
                if (sys != null && dia != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        BpGaugeColumn(
                            title = "Systolic", value = sys, domain = 80.0..190.0,
                            zones = cards.getValue(MetricKind.BLOOD_PRESSURE).zones,
                            fallback = ZonePalette.accent(MetricKind.BLOOD_PRESSURE),
                            modifier = Modifier.weight(1f),
                        )
                        BpGaugeColumn(
                            title = "Diastolic", value = dia, domain = 50.0..130.0,
                            zones = com.pulseloop.service.VitalsThresholdEngine.diastolicReferenceZones(),
                            fallback = ZonePalette.accent(MetricKind.BLOOD_PRESSURE).copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text("No blood pressure data yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (measuring) {
                    Text("Measuring… updates when complete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            DashboardCard.HRV -> VitalCard(
                state = cards.getValue(MetricKind.HRV),
                onTap = { navController?.navigate("vitals/hrv") },
            ) {
                if (state.latestHrv != null && state.hrvSamples.isNotEmpty()) {
                    val c = cards.getValue(MetricKind.HRV)
                    ZoneLineChart(
                        samples = c.samples, zones = c.zones, yDomain = c.yDomain,
                        accent = ZonePalette.accent(MetricKind.HRV),
                        referenceBands = c.referenceBands, dashedRules = c.dashedRules,
                    )
                } else {
                    Text("No HRV data yet — HRV builds up over a few hours of wear.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DashboardCard.STRESS -> VitalGaugeCardItem(
                card = cards.getValue(MetricKind.STRESS),
                hasReading = state.stressSamples.isNotEmpty() && (state.latestStress?.toInt() ?: 0) >= 10,
                emptyText = "No stress data yet — take a measurement.",
                onTap = { navController?.navigate("vitals/stress") },
            )
            DashboardCard.FATIGUE -> VitalGaugeCardItem(
                card = cards.getValue(MetricKind.FATIGUE),
                hasReading = state.fatigueSamples.isNotEmpty() && (state.latestFatigue?.toInt() ?: 0) >= 10,
                emptyText = "No fatigue data yet — take a measurement.",
                onTap = { navController?.navigate("vitals/fatigue") },
            )
            DashboardCard.GLUCOSE -> VitalGaugeCardItem(
                card = cards.getValue(MetricKind.GLUCOSE),
                hasReading = state.bloodSugar != null,
                emptyText = "No blood sugar data yet.",
                measuring = measuring,
                onTap = { navController?.navigate("vitals/glucose") },
            )
            DashboardCard.TEMPERATURE -> VitalCard(
                state = cards.getValue(MetricKind.TEMPERATURE),
                onTap = { navController?.navigate("vitals/temp") },
            ) {
                val tempVal = state.latestTemp
                if (tempVal != null && state.tempSamples.isNotEmpty()) {
                    val c = cards.getValue(MetricKind.TEMPERATURE)
                    ZoneLineChart(
                        samples = c.samples, zones = c.zones, yDomain = c.yDomain,
                        accent = ZonePalette.accent(MetricKind.TEMPERATURE),
                        referenceBands = c.referenceBands, dashedRules = c.dashedRules,
                    )
                } else {
                    Text("No temperature data yet — temperature trends appear after overnight wear.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {}
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = 16.dp + topBarPadding, bottom = 16.dp + bottomBarPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vitals", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Live measurements and trends", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Measure button: combined (0x23) for 56ff/Jring, or sequential live
                // HR + SpO₂ (0x69) for Colmi. Hidden for rings that support neither.
                if (coordinator != null && (combinedMode || spotMode)) {
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
                                    if (combinedMode) coordinator.measureCombined() else coordinator.measureSpot()
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
                    if (combinedMode)
                        "Keep still — measuring blood pressure, SpO₂, stress, fatigue & blood sugar…"
                    else
                        "Keep still — measuring heart rate & SpO₂…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Card order mirrors VitalsView.swift (HR, SpO₂, BP, HRV, Stress, Fatigue, Glucose, Temp)
        // by default, but honors the user's per-metric visibility + order (iOS #64). Long-press a
        // card to reorder/hide; the Hidden tray restores.
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (editState.editing) ReorderDoneBar { editState.exit() }
                else CustomizeCardsButton { editState.enterEditing() }
            }
        }

        items(visibleOrdered, key = { it.key }) { card ->
            EditableCard(editState, card, Modifier.fillMaxWidth()) {
                vitalsCardFor(card)
            }
        }

        if (editState.editing) {
            item { HiddenMetricsTray(hiddenCards) { editState.setHidden(it, false) } }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * A one-line context footer for gauge cards (iOS `gaugeFooter`): subtitle + trend delta,
 * e.g. "Lower is calmer · +2 vs earlier".
 */
private fun gaugeFooter(card: VitalCardState): String? {
    val parts = listOfNotNull(card.subtitleText, card.trend.deltaText)
    return if (parts.isEmpty()) card.lastUpdatedText else parts.joinToString(" · ")
}

/**
 * Full-width ring-gauge card for stress/fatigue/glucose (VitalGaugeCard/VitalGlucoseCard in
 * Swift). No top value row — the gauge center IS the value; the footer carries context + trend.
 */
@Composable
private fun VitalGaugeCardItem(
    card: VitalCardState,
    hasReading: Boolean,
    emptyText: String,
    measuring: Boolean = false,
    onTap: () -> Unit,
) {
    VitalCard(state = card, showsValueRow = false, footerOverride = gaugeFooter(card), onTap = onTap) {
        if (hasReading) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                VitalRingGauge(
                    value = card.latestValue ?: 0.0,
                    domain = card.yDomain,
                    zones = card.zones,
                    valueColor = card.statusToken.toColor(),
                    centerValue = card.valueText,
                    centerUnit = card.unitText,
                    centerStatus = card.statusText,
                    size = 190.dp,
                    lineWidth = 16.dp,
                )
            }
        } else {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (measuring) {
            Text("Measuring… updates when complete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** One BP gauge column (VitalBloodPressureCard.ringColumn): 130dp ring + SYSTOLIC/DIASTOLIC caption. */
@Composable
private fun BpGaugeColumn(
    title: String,
    value: Double,
    domain: ClosedFloatingPointRange<Double>,
    zones: List<com.pulseloop.service.MetricZone>,
    fallback: Color,
    modifier: Modifier = Modifier,
) {
    // Color the value arc by the zone the reading falls in (so a normal reading is green, not
    // the metric's pink accent). Falls back to the metric color if no zone matches.
    val valueColor = zones.firstOrNull {
        (it.lower ?: Double.NEGATIVE_INFINITY) <= value && value < (it.upper ?: Double.POSITIVE_INFINITY)
    }?.colorToken?.toColor() ?: fallback
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VitalRingGauge(
            value = value,
            domain = domain,
            zones = zones,
            valueColor = valueColor,
            centerValue = "${value.toInt()}",
            centerUnit = "mmHg",
            size = 130.dp,
            lineWidth = 11.dp,
        )
        Text(
            title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = PulseColors.textMuted,
        )
    }
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
    // Glucose display unit for the detail chart's zones/axis/stats (iOS #43 §3); the VM already
    // converts the plotted readings, this converts the reference layer the screen owns.
    val gUnit = apiKeyStore?.preferredGlucoseUnit ?: com.pulseloop.service.GlucoseUnit.MGDL
    val vm = remember { VitalDetailViewModel(db, metric, apiKeyStore, units) }
    val state by vm.state.collectAsState()

    val title = metricDisplayName(metric)

    Column(Modifier.fillMaxSize()) {
        // Inline nav bar (iOS .navigationBarTitleDisplayMode(.inline)): back on the leading
        // edge, title centered.
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Filled.ArrowBack, "Back")
            }
            Text(
                title,
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // iOS MetricDetailView layout: period selector, chart card, stats strip,
            // reference zones, "what this means", and a warning disclaimer for estimated
            // metrics. The chart keeps the Android-only interactivity (scrub/zoom/pan) and
            // date paging.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // 1. Period selector — Today · Week · Month (iOS segmented picker)
                item {
                    PeriodSegmentedControl(selected = state.period, onSelect = { vm.setPeriod(it) })
                }

                // 2. Chart card: unit label + interactive trend chart (24dp continuous corners).
                item {
                    val cardShape = RoundedCornerShape(24.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(PulseColors.card)
                            .border(1.dp, PulseColors.borderSubtle, cardShape)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Date navigator row: unit label leading (iOS), pager trailing
                        // (Android-only paging kept from PR #5).
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.thresholds?.unitLabel ?: "",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PulseColors.textMuted,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.prev() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.ChevronLeft, "Previous", tint = PulseColors.textMuted)
                                }
                                Text(
                                    text = dateLabel(state.anchor, state.period),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = PulseColors.textSecondary,
                                )
                                IconButton(
                                    onClick = { vm.next() },
                                    enabled = vm.canGoForward(),
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.ChevronRight, "Next",
                                        tint = if (vm.canGoForward()) PulseColors.textMuted else PulseColors.textMuted.copy(alpha = 0.3f),
                                    )
                                }
                            }
                        }
                        if (state.points.size < 2) {
                            Box(
                                Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Not enough data for this period.",
                                    fontSize = 13.sp,
                                    color = PulseColors.textMuted,
                                )
                            }
                        } else {
                            TrendChart(
                                points = state.points,
                                labels = state.labels,
                                color = state.thresholds?.zones?.firstOrNull()?.color
                                    ?: MaterialTheme.colorScheme.primary,
                                secondary = state.secondary,
                                colorSecondary = ZonePalette.accent(MetricKind.BLOOD_PRESSURE).copy(alpha = 0.5f),
                                legendPrimary = if (state.isBP) "Systolic" else null,
                                legendSecondary = if (state.isBP) "Diastolic" else null,
                                // Points arrive in display units from the VM; convert the zone/axis
                                // reference to match so temp bands (°F) and glucose bands (mmol/L)
                                // line up with the plotted line (iOS #43 §2/§3).
                                thresholds = displayThresholds(state.thresholds, metric, units, gUnit),
                                timestamps = state.timestamps,
                                tooltipTimeFormatter = { ts -> tooltipTime(ts, state.period) },
                            )
                        }
                    }
                }

                // 3. Stats strip — LATEST · AVERAGE · MIN · MAX in one row with dividers.
                item {
                    val cardShape = RoundedCornerShape(24.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(PulseColors.card)
                            .border(1.dp, PulseColors.borderSubtle, cardShape)
                            .padding(horizontal = 10.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DetailStat("Latest", state.latest?.let { formatStat(it, metric, gUnit) } ?: "--", Modifier.weight(1f))
                        DetailStatDivider()
                        DetailStat("Average", state.avg?.let { formatStat(it, metric, gUnit) } ?: "--", Modifier.weight(1f))
                        DetailStatDivider()
                        DetailStat("Min", state.min?.let { formatStat(it, metric, gUnit) } ?: "--", Modifier.weight(1f))
                        DetailStatDivider()
                        DetailStat("Max", state.max?.let { formatStat(it, metric, gUnit) } ?: "--", Modifier.weight(1f))
                    }
                }

                // 4. Reference zones — colored dot + label + range per zone.
                if (state.engineZones.isNotEmpty()) {
                    item {
                        val cardShape = RoundedCornerShape(20.dp)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(PulseColors.card)
                                .border(1.dp, PulseColors.borderSubtle, cardShape)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "REFERENCE ZONES",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                color = PulseColors.textMuted,
                            )
                            state.engineZones.forEach { zone ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(zone.colorToken.toColor(), CircleShape),
                                    )
                                    Text(
                                        zone.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = PulseColors.textPrimary,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        zoneRangeText(zone, metric, units, gUnit),
                                        fontSize = 12.sp,
                                        color = PulseColors.textMuted,
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. What this means
                item {
                    val cardShape = RoundedCornerShape(20.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(PulseColors.card)
                            .border(1.dp, PulseColors.borderSubtle, cardShape)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "WHAT THIS MEANS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                            color = PulseColors.textMuted,
                        )
                        Text(
                            metricExplainer(metric),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = PulseColors.textSecondary,
                        )
                    }
                }

                // 6. Estimated-metric disclaimer (BP + glucose only, iOS warning card).
                metricDisclaimer(metric)?.let { disclaimer ->
                    item {
                        val cardShape = RoundedCornerShape(20.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(ZonePalette.ZoneAmber.copy(alpha = 0.10f))
                                .border(1.dp, ZonePalette.ZoneAmber.copy(alpha = 0.30f), cardShape)
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Filled.Warning, null,
                                tint = ZonePalette.ZoneAmber,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                disclaimer,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = PulseColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** iOS-style segmented control for Today/Week/Month. */
@Composable
private fun PeriodSegmentedControl(selected: Period, onSelect: (Period) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PulseColors.cardSoft)
            .padding(2.dp),
    ) {
        Period.entries.forEach { p ->
            val isSelected = selected == p
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) PulseColors.textPrimary else PulseColors.textSecondary,
                )
            }
        }
    }
}

/** One column of the LATEST/AVERAGE/MIN/MAX strip. */
@Composable
private fun DetailStat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
            color = PulseColors.textMuted,
            maxLines = 1,
        )
        Text(
            value,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailStatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(34.dp)
            .background(PulseColors.borderSubtle),
    )
}

/** "60–101" / "≥ 120" / "< 60" for a reference zone, converted to display units. */
private fun zoneRangeText(
    zone: com.pulseloop.service.MetricZone,
    metric: String,
    units: UnitSystem,
    gUnit: com.pulseloop.service.GlucoseUnit,
): String {
    fun display(v: Double): Double = when (metric) {
        "temp" -> UnitConverter.temperature(v, units)
        "glucose" -> gUnit.fromMgdl(v)
        else -> v
    }
    fun fmt(v: Double): String = when {
        metric == "temp" -> "%.1f".format(display(v))
        metric == "glucose" && gUnit == com.pulseloop.service.GlucoseUnit.MMOL -> "%.1f".format(display(v))
        else -> "${display(v).toInt()}"
    }
    val lo = zone.lower
    val hi = zone.upper
    return when {
        lo != null && hi != null -> "${fmt(lo)}–${fmt(hi)}"
        lo != null -> "≥ ${fmt(lo)}"
        hi != null -> "< ${fmt(hi)}"
        else -> ""
    }
}

/** Warning-card copy for estimated metrics (iOS `disclaimerText`); null hides the card. */
private fun metricDisclaimer(metric: String): String? = when (metric) {
    "glucose" ->
        "Estimated wellness metric — not for dosing or diabetes decisions. No smart ring or watch is " +
        "FDA-authorized to measure or estimate glucose on its own."
    "bp" ->
        "Ring blood pressure is an estimate. Calibrate against a validated cuff in Settings → Calibration, " +
        "and talk to a clinician about persistent high or low readings."
    else -> null
}

/** Detail titles use MetricKind.title's sentence case (iOS navigation titles). */
private fun metricDisplayName(metric: String): String = when (metric) {
    "hr"      -> "Heart rate"
    "spo2"    -> "Blood oxygen"
    "stress"  -> "Stress"
    "fatigue" -> "Fatigue"
    "hrv"     -> "HRV"
    "temp"    -> "Skin temperature"
    "bp"      -> "Blood pressure"
    "glucose" -> "Blood sugar"
    else      -> metric
}

/** "WHAT THIS MEANS" copy, verbatim from iOS MetricDetailView `explainerText`. */
private fun metricExplainer(metric: String): String = when (metric) {
    "hr" -> "Resting heart rate reflects how hard your heart works at rest. A typical adult range is 60–100 bpm; " +
        "fitness, medication, caffeine, and stress all shift it."
    "spo2" -> "Blood oxygen (SpO₂) is the percentage of oxygen your blood carries. 95–100% is normal; " +
        "altitude and lung conditions can lower it."
    "hrv" -> "Heart-rate variability is the variation between beats. It's highly individual, so we track it " +
        "against your personal baseline rather than a universal cutoff."
    "bp" -> "Blood pressure is systolic over diastolic (mmHg). The category is the worse of the two. " +
        "A ring estimate is not a substitute for a cuff."
    "stress" -> "A device wellness score from 0–100 based on heart-rate patterns. Lower is calmer. " +
        "It's an estimate, not a medical stress measure."
    "fatigue" -> "A device wellness score from 0–100 estimating tiredness. Higher means more fatigue. " +
        "It's a ring estimate, not a clinical scale."
    "glucose" -> "An estimated glucose value. No smart ring is cleared to measure glucose, so treat this " +
        "as a wellness estimate only."
    "temp" -> "Skin temperature from the ring runs cooler than core body temperature. " +
        "Trends over time matter more than any single reading."
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

private fun formatStat(value: Double, metric: String, gUnit: com.pulseloop.service.GlucoseUnit): String = when (metric) {
    "temp" -> "%.1f".format(value)
    // Values arrive already in the display unit; mmol/L wants a decimal, mg/dL is whole.
    "glucose" -> if (gUnit == com.pulseloop.service.GlucoseUnit.MMOL) "%.1f".format(value) else "%.0f".format(value)
    "hrv" -> "%.0f".format(value)
    else -> "%.0f".format(value)
}

/**
 * Convert an engine [MetricThresholds] (canonical units) into the chart's display units so the
 * zone bands and y-axis match the VM's already-converted points — temperature to °F, glucose to
 * mmol/L (iOS #43 §2/§3). Other metrics (and null) pass through unchanged.
 */
private fun displayThresholds(
    t: com.pulseloop.ui.components.MetricThresholds?,
    metric: String,
    units: UnitSystem,
    gUnit: com.pulseloop.service.GlucoseUnit,
): com.pulseloop.ui.components.MetricThresholds? {
    if (t == null || (metric != "temp" && metric != "glucose")) return t
    fun d(v: Double): Double =
        if (metric == "temp") UnitConverter.temperature(v, units) else gUnit.fromMgdl(v)
    return t.copy(
        displayMin = d(t.displayMin),
        displayMax = d(t.displayMax),
        zones = t.zones.map { it.copy(start = d(it.start), end = d(it.end)) },
    )
}


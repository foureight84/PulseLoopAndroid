package com.pulseloop.ui.screens

import com.pulseloop.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.pulseloop.ring.RingDeviceType
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
    /** Which control is counting down, so only that button shows the timer (issue #66). */
    var measuringLabel by remember { mutableStateOf("") }
    /** The running leg's own window, so the progress bar measures the run and not the full sweep. */
    var measureTotal by remember { mutableStateOf(0) }
    /** What the running leg is actually measuring — "heart rate & SpO₂" is wrong for a single leg. */
    var measureCaption by remember { mutableStateOf("") }
    // Set when a spot measurement finishes with every leg FAILED — iOS #66's "an honest retry":
    // the refusal gate keeps bad values off screen, and this surfaces the failure with iOS's
    // per-kind copy instead of silently re-enabling the button (second-pass finding #30).
    var measureFailed by remember { mutableStateOf(false) }
    // Set alongside [measureFailed] when the CRP ring reported it wasn't on the finger, so the
    // failure copy tells the user to put the ring on rather than the generic "hold still" (issue #29).
    var measureNotWornHint by remember { mutableStateOf(false) }
    var measureDisconnectedHint by remember { mutableStateOf(false) }
    // Two measurement flavours:
    //  • combined (56ff/Jring): one 0x23 packet → BP + SpO₂ + stress + fatigue + blood sugar
    //  • spot (Colmi): sequential live HR + SpO₂ via the real-time command (0x69)
    // Colmi has no BP/glucose hardware, so it never qualifies for the combined flow.
    // Manual BP/glucose capability alone is not evidence of Jring's 0x23 combined packet.
    // In particular YCBT/R10M advertises those vitals but requires sequential 03/2f modes;
    // routing it to measureCombined() silently called a protocol no-op.
    val combinedMode = coordinator?.supportsCombinedMeasurement == true &&
        (state.supportsBP || state.supportsGlucose)
    val spotMode = !combinedMode && (
        state.supportsManualHr || state.supportsManualSpo2 || state.supportsBP
    )
    // The spot bound is per-ring: a family that ends its own HR measurement gets a longer
    // ceiling (issue #59), and the countdown must not run out while the leg is still measuring.
    val measureSeconds = if (combinedMode)
        com.pulseloop.service.RingSyncCoordinator.COMBINED_MEASURE_SECONDS
    else
        coordinator?.spotMeasureSeconds
            ?: com.pulseloop.service.RingSyncCoordinator.SPOT_MEASURE_SECONDS
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
                    Text(stringResource(R.string.vitals_empty_hr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    // Issue #103: the card's ESTIMATED chip says *that*; this says what, and that a
                    // spot reading is still a measurement.
                    if (state.spo2IsEstimated) {
                        Text(stringResource(R.string.vitals_spo2_estimated_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(stringResource(R.string.vitals_empty_spo2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            title = stringResource(R.string.bp_systolic), value = sys, domain = 80.0..190.0,
                            zones = cards.getValue(MetricKind.BLOOD_PRESSURE).zones,
                            fallback = ZonePalette.accent(MetricKind.BLOOD_PRESSURE),
                            modifier = Modifier.weight(1f),
                        )
                        BpGaugeColumn(
                            title = stringResource(R.string.bp_diastolic), value = dia, domain = 50.0..130.0,
                            zones = com.pulseloop.service.VitalsThresholdEngine.diastolicReferenceZones(),
                            fallback = ZonePalette.accent(MetricKind.BLOOD_PRESSURE).copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(stringResource(R.string.vitals_empty_bp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (measuring) {
                    Text(stringResource(R.string.vitals_measuring_updates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                    Text(stringResource(R.string.vitals_empty_hrv), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DashboardCard.STRESS -> VitalGaugeCardItem(
                card = cards.getValue(MetricKind.STRESS),
                // The `>= 10` floor reads a ring's 0 as "nothing measured". A derived score has no
                // such sentinel — its whole 0–100 range is meaningful, and a genuinely calm day
                // scores below 10 — so the floor would hide exactly the reading it was asked for.
                hasReading = state.stressSamples.isNotEmpty() &&
                    (state.stressIsDerived || (state.latestStress?.toInt() ?: 0) >= 10),
                emptyText = stringResource(R.string.vitals_empty_stress),
                // Issue #67: say so on the card itself. A derived figure presented as a measurement
                // would be worse than the empty card it replaces.
                footnote = if (state.stressIsDerived) stringResource(R.string.vitals_stress_derived_short) else null,
                onTap = { navController?.navigate("vitals/stress") },
            )
            DashboardCard.FATIGUE -> VitalGaugeCardItem(
                card = cards.getValue(MetricKind.FATIGUE),
                hasReading = state.fatigueSamples.isNotEmpty() && (state.latestFatigue?.toInt() ?: 0) >= 10,
                emptyText = stringResource(R.string.vitals_empty_fatigue),
                onTap = { navController?.navigate("vitals/fatigue") },
            )
            DashboardCard.GLUCOSE -> VitalGaugeCardItem(
                card = cards.getValue(MetricKind.GLUCOSE),
                hasReading = state.bloodSugar != null,
                emptyText = stringResource(R.string.vitals_empty_glucose),
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
                    Text(stringResource(R.string.vitals_empty_temp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(stringResource(R.string.vitals_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.vitals_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Measure button: combined (0x23) for 56ff/Jring, or sequential live
                // HR + SpO₂ (0x69) for Colmi. Hidden for rings that support neither.
                if (coordinator != null && (combinedMode || spotMode)) {
                    val failedState = com.pulseloop.service.RingSyncCoordinator.MeasureState.FAILED

                    /**
                     * Run one measurement, whatever its shape, with its own countdown.
                     *
                     * [failed] is asked only about the legs *this* run started. The leg states
                     * persist as DONE/FAILED after a run, so a blanket "did HR or SpO₂ fail?" read
                     * a previous run's verdict — a failed SpO₂ attempt made the next successful
                     * heart-rate reading report failure.
                     */
                    fun runMeasurement(
                        seconds: Int,
                        label: String,
                        caption: String,
                        failed: () -> Boolean,
                        leg: suspend () -> Unit,
                    ) {
                        measuring = true
                        measuringLabel = label
                        measureFailed = false
                        measureNotWornHint = false
                        measureDisconnectedHint = false
                        remaining = seconds
                        measureTotal = seconds
                        measureCaption = caption
                        scope.launch {
                            val countdownFlow = coordinator.spotCountdownRemaining
                            val ticker = launch {
                                if (coordinator.activeDeviceType == RingDeviceType.CRP) {
                                    countdownFlow.collect { sec ->
                                        if (sec != null) remaining = sec
                                    }
                                } else {
                                    while (remaining > 0) { kotlinx.coroutines.delay(1000); remaining-- }
                                }
                            }
                            try {
                                leg()
                            } finally {
                                ticker.cancel()
                                remaining = 0
                                measuring = false
                                if (failed()) {
                                    measureFailed = true
                                    measureNotWornHint = coordinator.measureNotWorn
                                    measureDisconnectedHint = coordinator.measureDisconnected
                                }
                                viewModel?.refreshNow()  // show the new reading immediately
                            }
                        }
                    }

                    // Issue #66: heart rate and blood oxygen are separate measurements on a ring
                    // that runs them sequentially — 45 s against 75 s, with completely different
                    // sample spacing — so a user after one should not be held for the other. The
                    // combined flow keeps its single button: there it really is one packet.
                    val labelHr = stringResource(R.string.abbr_hr)
                    val labelSpo2 = stringResource(R.string.abbr_spo2)
                    val capHr = stringResource(R.string.vitals_caption_hr)
                    val capSpo2 = stringResource(R.string.vitals_caption_spo2)
                    val capCombined = stringResource(R.string.vitals_caption_combined)
                    val capSpot = stringResource(R.string.vitals_caption_spot)
                    val separateLegs = !combinedMode &&
                        coordinator.canMeasureHeartRate && coordinator.canMeasureSpO2
                    // The legs the two separate controls don't cover. `measureSpot()` was the only
                    // caller of the BP and HRV legs in the app, so replacing it with HR + SpO₂
                    // buttons took manual BP and HRV away from a ring that advertises them — they
                    // get their own control rather than disappearing.
                    // A ring that streams HRV gets it on its own control in the row below.
                    val hrvOnRemaining = coordinator.canMeasureHrv && !coordinator.streamsSpotVitals
                    val remainingLegsLabel = when {
                        coordinator.canMeasureBloodPressure && hrvOnRemaining -> stringResource(R.string.vitals_bp_and_hrv)
                        coordinator.canMeasureBloodPressure -> stringResource(R.string.abbr_bp)
                        hrvOnRemaining -> stringResource(R.string.abbr_hrv)
                        else -> null
                    }
                    // On-demand HRV / stress / temperature, one control each (issue #66's rule).
                    val capLegs = remainingLegsLabel?.let { stringResource(R.string.vitals_caption_fmt, it) } ?: ""
                    val spotVitals = coordinator.measurableSpotVitals
                    if (separateLegs) {
                      // Stacked, not side by side: this sits in the header Row beside the title,
                      // so a second Row here would be laid out horizontally and crush the title.
                      Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                      ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !measuring,
                                onClick = {
                                    runMeasurement(
                                        coordinator.heartRateMeasureSeconds, labelHr,
                                        caption = capHr,
                                        failed = { coordinator.hrState == failedState },
                                    ) { coordinator.measureHeartRateOnly() }
                                },
                            ) {
                                Text(
                                    if (measuring && measuringLabel == labelHr) stringResource(R.string.label_with_seconds, labelHr, remaining) else labelHr,
                                    color = androidx.compose.ui.graphics.Color.White,
                                )
                            }
                            Button(
                                enabled = !measuring,
                                onClick = {
                                    runMeasurement(
                                        coordinator.spo2OnlyMeasureSeconds, labelSpo2,
                                        caption = capSpo2,
                                        failed = { coordinator.spo2State == failedState },
                                    ) { coordinator.measureSpO2Only() }
                                },
                            ) {
                                Text(
                                    if (measuring && measuringLabel == labelSpo2) stringResource(R.string.label_with_seconds, labelSpo2, remaining) else labelSpo2,
                                    color = androidx.compose.ui.graphics.Color.White,
                                )
                            }
                            if (remainingLegsLabel != null) {
                                Button(
                                    enabled = !measuring,
                                    onClick = {
                                        runMeasurement(
                                            coordinator.remainingLegsMeasureSeconds, remainingLegsLabel,
                                            caption = capLegs,
                                            failed = {
                                                (coordinator.canMeasureBloodPressure &&
                                                    coordinator.bloodPressureState == failedState) ||
                                                    (coordinator.canMeasureHrv && coordinator.hrvState == failedState)
                                            },
                                        ) { coordinator.measureRemainingLegs() }
                                    },
                                ) {
                                    Text(
                                        if (measuring && measuringLabel == remainingLegsLabel)
                                            stringResource(R.string.label_with_seconds, remainingLegsLabel, remaining) else remainingLegsLabel,
                                        color = androidx.compose.ui.graphics.Color.White,
                                    )
                                }
                            }
                        }
                        if (spotVitals.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (vital in spotVitals) {
                                    val label = spotVitalLabel(vital)
                                    val capVital = stringResource(R.string.vitals_caption_fmt, spotVitalDescription(vital))
                                    Button(
                                        enabled = !measuring,
                                        // Three to a row beside the title: tighter than the default 24 dp.
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        onClick = {
                                            runMeasurement(
                                                coordinator.spotVitalMeasureSeconds, label,
                                                caption = capVital,
                                                failed = { coordinator.vitalState(vital) == failedState },
                                            ) { coordinator.measureVitalOnly(vital) }
                                        },
                                    ) {
                                        Text(
                                            if (measuring && measuringLabel == label) stringResource(R.string.label_with_seconds, label, remaining) else label,
                                            color = androidx.compose.ui.graphics.Color.White,
                                        )
                                    }
                                }
                            }
                        }
                      }
                    } else {
                    Button(
                        enabled = !measuring,
                        onClick = {
                            runMeasurement(
                                measureSeconds, "all",
                                caption = if (combinedMode) capCombined else capSpot,
                                failed = {
                                    !combinedMode &&
                                        (coordinator.hrState == failedState || coordinator.spo2State == failedState)
                                },
                            ) {
                                if (combinedMode) coordinator.measureCombined() else coordinator.measureSpot()
                            }
                        },
                    ) {
                        Text(
                            if (measuring) stringResource(R.string.vitals_measuring_seconds, remaining) else stringResource(R.string.vitals_measure),
                            color = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                    }
                }
            }
            if (measuring) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    // Against the *running* leg's own window, not the whole sweep's: an HR-only run
                    // counts down from 48 s while the sweep bound is 123 s, so the shared divisor
                    // started the bar 61 % full and it never described the run.
                    progress = {
                        val total = measureTotal.coerceAtLeast(1)
                        ((total - remaining).toFloat() / total).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    measureCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (measureFailed && !measuring) {
                Spacer(Modifier.height(8.dp))
                // iOS MeasurementKindPresentation failure copy ("…Keep the ring snug and your
                // hand still, then try again."), shown until the next attempt.
                Text(
                    when {
                        measureDisconnectedHint ->
                            stringResource(R.string.vitals_fail_disconnected)
                        measureNotWornHint ->
                            stringResource(R.string.vitals_fail_not_worn)
                        else ->
                            stringResource(R.string.vitals_fail_generic)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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
    /** Replaces the usual footer when the value isn't a measurement — see issue #67. */
    footnote: String? = null,
    onTap: () -> Unit,
) {
    VitalCard(
        state = card,
        showsValueRow = false,
        footerOverride = footnote ?: gaugeFooter(card),
        onTap = onTap,
    ) {
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
            Text(stringResource(R.string.vitals_measuring_updates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
            centerUnit = stringResource(R.string.unit_mmhg),
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
    val scheduledSpo2Prefs = remember { com.pulseloop.settings.ScheduledSpo2Prefs(context) }
    val vm = remember { VitalDetailViewModel(db, metric, apiKeyStore, units, scheduledSpo2Prefs) }
    val state by vm.state.collectAsState()

    val title = metricDisplayName(metric)
    val disclaimerText = metricDisclaimer(metric, state.isDerived)

    Column(Modifier.fillMaxSize()) {
        // Inline nav bar (iOS .navigationBarTitleDisplayMode(.inline)): back on the leading
        // edge, title centered.
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
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
                                    Icon(Icons.Filled.ChevronLeft, stringResource(R.string.action_previous), tint = PulseColors.textMuted)
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
                                        Icons.Filled.ChevronRight, stringResource(R.string.action_next),
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
                                    stringResource(R.string.detail_not_enough_data),
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
                                legendPrimary = if (state.isBP) stringResource(R.string.bp_systolic) else null,
                                legendSecondary = if (state.isBP) stringResource(R.string.bp_diastolic) else null,
                                // Points arrive in display units from the VM; convert the zone/axis
                                // reference to match so temp bands (°F) and glucose bands (mmol/L)
                                // line up with the plotted line (iOS #43 §2/§3).
                                thresholds = displayThresholds(state.thresholds, metric, units, gUnit),
                                timestamps = state.timestamps,
                                tooltipTimeFormatter = { ts -> tooltipTime(ts, state.period) },
                                // The whole calendar period, gaps included (issue #102).
                                timeDomain = state.windowStart to state.windowEnd,
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
                        DetailStat(stringResource(R.string.stat_latest), state.latest?.let { formatStat(it, metric, gUnit) } ?: "--", Modifier.weight(1f))
                        DetailStatDivider()
                        DetailStat(stringResource(R.string.stat_average), state.avg?.let { formatStat(it, metric, gUnit) } ?: "--", Modifier.weight(1f))
                        DetailStatDivider()
                        DetailStat(stringResource(R.string.stat_min), state.min?.let { formatStat(it, metric, gUnit) } ?: "--", Modifier.weight(1f))
                        DetailStatDivider()
                        DetailStat(stringResource(R.string.stat_max), state.max?.let { formatStat(it, metric, gUnit) } ?: "--", Modifier.weight(1f))
                    }
                }

                // 4. Readings — every individual measurement in the window, newest first, each
                // deletable (issue #60). Collapsed by default: a Month of all-day history runs to
                // hundreds of rows, and the chart above is what the screen is normally for.
                if (state.readings.isNotEmpty()) {
                    item {
                        ReadingsCard(
                            readings = state.readings,
                            metric = metric,
                            period = state.period,
                            unitLabel = state.thresholds?.unitLabel ?: "",
                            gUnit = gUnit,
                            onDelete = { vm.deleteReading(it) },
                        )
                    }
                }

                // 5. Reference zones — colored dot + label + range per zone.
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
                                stringResource(R.string.detail_reference_zones),
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

                // 6. What this means
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
                            stringResource(R.string.detail_what_this_means),
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

                // 7. Estimated-metric disclaimer (BP + glucose, iOS warning card; plus derived
                // stress, issue #67 — the card that opened this chart labels its number, and this
                // chart shows the same number, so it says the same thing here).
                disclaimerText?.let { disclaimer ->
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

/**
 * The window's individual readings, with a delete affordance per row (issue #60).
 *
 * Deletion only — a recorded health value can be removed but never edited into a different
 * number — and always behind a confirmation, because it cannot be undone: the row is gone, and a
 * reading the ring supplied is tombstoned so the next sync of that day won't restore it.
 */
@Composable
private fun ReadingsCard(
    readings: List<VitalDetailViewModel.Reading>,
    metric: String,
    period: Period,
    unitLabel: String,
    gUnit: com.pulseloop.service.GlucoseUnit,
    onDelete: (VitalDetailViewModel.Reading) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VitalDetailViewModel.Reading?>(null) }
    // Rows are composed eagerly inside one LazyColumn item — the parent's 18dp item spacing means
    // the card can't be split across items without losing its chrome — so the list is paged rather
    // than rendered whole. A Month of 5-minute all-day history is thousands of readings; a page of
    // PAGE_SIZE is what someone scanning for a bad measurement actually reads.
    var visibleCount by remember(readings.size) { mutableStateOf(READINGS_PAGE_SIZE) }
    val cardShape = RoundedCornerShape(20.dp)

    fun readingText(r: VitalDetailViewModel.Reading): String {
        val primary = formatStat(r.value, metric, gUnit)
        return r.secondary?.let { "$primary/${formatStat(it, metric, gUnit)}" } ?: primary
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, cardShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.detail_readings),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = PulseColors.textMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                readings.size.toString(),
                fontSize = 12.sp,
                color = PulseColors.textMuted,
                modifier = Modifier.padding(end = 6.dp),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.detail_hide_readings) else stringResource(R.string.detail_show_readings),
                tint = PulseColors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            // Not a nested LazyColumn: this card already sits inside one, and nesting two
            // scrollers in the same axis crashes Compose. Bounded by [visibleCount] instead.
            readings.take(visibleCount).forEach { reading ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tooltipTime(reading.timestamp, period),
                        fontSize = 13.sp,
                        color = PulseColors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        readingText(reading),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = PulseColors.textPrimary,
                    )
                    if (unitLabel.isNotEmpty()) {
                        Text(
                            " $unitLabel",
                            fontSize = 11.sp,
                            color = PulseColors.textMuted,
                        )
                    }
                    if (reading.isEstimated) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.badge_estimated),
                            fontSize = 10.sp,
                            color = PulseColors.warning,
                            modifier = Modifier
                                .background(PulseColors.warning.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    IconButton(
                        onClick = { pendingDelete = reading },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = stringResource(R.string.detail_delete_reading),
                            tint = PulseColors.textMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (readings.size > visibleCount) {
                TextButton(
                    onClick = { visibleCount += READINGS_PAGE_SIZE },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    val more = minOf(READINGS_PAGE_SIZE, readings.size - visibleCount)
                    Text(pluralStringResource(R.plurals.detail_show_more, more, more), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    pendingDelete?.let { reading ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.detail_delete_reading_title)) },
            text = {
                Text(
                    buildString {
                        val valueText = readingText(reading) + if (unitLabel.isNotEmpty()) " $unitLabel" else ""
                        append(stringResource(R.string.detail_delete_reading_body, valueText, tooltipTime(reading.timestamp, period)))
                        // Say so plainly rather than letting a user wonder why the reading
                        // didn't come back after a re-sync — that is deliberate.
                        if (reading.fromHistory) {
                            append(" ")
                            append(stringResource(R.string.detail_delete_reading_history))
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(reading)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** How many readings the list shows before asking (issue #60) — see [ReadingsCard]. */
private const val READINGS_PAGE_SIZE = 50

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
                    stringResource(p.labelRes),
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

/** Issue #103: the SpO₂ card's footnote, shared with the detail disclaimer so they never drift. */
@Composable
private fun spotVitalLabel(vital: com.pulseloop.ring.SpotVital): String = when (vital) {
    com.pulseloop.ring.SpotVital.HRV -> stringResource(R.string.abbr_hrv)
    com.pulseloop.ring.SpotVital.STRESS -> stringResource(R.string.spot_label_stress)
    com.pulseloop.ring.SpotVital.TEMPERATURE -> stringResource(R.string.spot_label_temp)
}

@Composable
private fun spotVitalDescription(vital: com.pulseloop.ring.SpotVital): String = when (vital) {
    com.pulseloop.ring.SpotVital.HRV -> stringResource(R.string.spot_desc_hrv)
    com.pulseloop.ring.SpotVital.STRESS -> stringResource(R.string.spot_desc_stress)
    com.pulseloop.ring.SpotVital.TEMPERATURE -> stringResource(R.string.spot_desc_temp)
}

/**
 * Warning-card copy for estimated metrics (iOS `disclaimerText`); null hides the card.
 *
 * [isDerived] is the issue #67 case: a stress chart computed from HRV because the ring reports no
 * stress at all. It gets the card for the same reason BP and glucose do — the number on screen is
 * an inference, and the user is entitled to know that without leaving the chart. For SpO₂ it is
 * issue #103: hourly readings this ring computes from heart rate rather than measures.
 */
@Composable
private fun metricDisclaimer(metric: String, isDerived: Boolean): String? = when {
    metric == "stress" && isDerived -> stringResource(R.string.disclaimer_stress_derived)
    metric == "spo2" && isDerived ->
        stringResource(R.string.vitals_spo2_estimated_note) + " " + stringResource(R.string.disclaimer_spo2_derived)
    metric == "glucose" -> stringResource(R.string.disclaimer_glucose)
    metric == "bp" -> stringResource(R.string.disclaimer_bp)
    else -> null
}

/** Detail titles use MetricKind.title's sentence case (iOS navigation titles). */
@Composable
private fun metricDisplayName(metric: String): String = when (metric) {
    "hr"      -> stringResource(R.string.metric_heart_rate)
    "spo2"    -> stringResource(R.string.metric_blood_oxygen)
    "stress"  -> stringResource(R.string.metric_stress)
    "fatigue" -> stringResource(R.string.metric_fatigue)
    "hrv"     -> stringResource(R.string.abbr_hrv)
    "temp"    -> stringResource(R.string.metric_skin_temperature)
    "bp"      -> stringResource(R.string.metric_blood_pressure)
    "glucose" -> stringResource(R.string.metric_blood_sugar)
    else      -> metric
}

/** "WHAT THIS MEANS" copy, verbatim from iOS MetricDetailView `explainerText`. */
@Composable
private fun metricExplainer(metric: String): String = when (metric) {
    "hr" -> stringResource(R.string.explainer_hr)
    "spo2" -> stringResource(R.string.explainer_spo2)
    "hrv" -> stringResource(R.string.explainer_hrv)
    "bp" -> stringResource(R.string.explainer_bp)
    "stress" -> stringResource(R.string.explainer_stress)
    "fatigue" -> stringResource(R.string.explainer_fatigue)
    "glucose" -> stringResource(R.string.explainer_glucose)
    "temp" -> stringResource(R.string.explainer_temp)
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
        Period.MONTH -> dt.format(com.pulseloop.util.LocaleFormats.formatter("LLLyyyy", "MMM yyyy"))
    }
}

/** Precise time label for the chart scrub tooltip, formatted per aggregation period. */
private fun tooltipTime(ts: Long, period: Period): String {
    val z = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
    return when (period) {
        Period.DAY -> z.format(com.pulseloop.util.LocaleFormats.time())
        Period.WEEK -> z.format(com.pulseloop.util.LocaleFormats.formatter("EEEd", "EEE d"))
        Period.MONTH -> z.format(com.pulseloop.util.LocaleFormats.formatter("MMMd", "MMM d"))
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


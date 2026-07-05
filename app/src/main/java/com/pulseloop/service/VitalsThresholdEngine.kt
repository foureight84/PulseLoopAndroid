package com.pulseloop.service

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Centralized medical reference-range engine for vitals. The single source of truth for zones,
 * interpretation, and value→color mapping. Views/charts/gauges/legends all call through here so a
 * threshold lives in exactly one place. Ported from Services/VitalsThresholdEngine.swift — zone
 * boundaries and special cases must stay identical to iOS.
 *
 * IMPORTANT: nothing here is a diagnosis. Labels are conservative ("Typical", "Elevated", "Low")
 * and ring-estimated metrics (blood pressure, glucose, fatigue) always carry `isEstimated`.
 *
 * Reference ranges encoded (defaults; users can later override via profile):
 * - HR: resting adult 60–100 bpm normal; athletes lower; effort zones from `220 − age`.
 * - SpO₂: 95–100 normal; <95 watch; ≤92 high; ≤88 critical (altitude shifts expectations).
 * - HRV: baseline-deviation, NOT absolute cutoffs (highly individual).
 * - Stress / Fatigue: device 0–100 scores, quartile zones.
 * - BP: AHA categories; card category = worse of systolic and diastolic.
 * - Glucose: fasting / 2-hour / random thresholds; conservative when context unknown; always Estimated.
 */
object VitalsThresholdEngine {

    // ─────────────────────────── Public entry points ───────────────────────────

    /**
     * The reference zones for a metric, given the user's physiology and the measurement context.
     * For HRV these are baseline-relative and require [baseline]; call [interpret] for the verdict.
     */
    fun zones(
        metric: MetricKind,
        profile: UserPhysiologyProfile,
        context: MetricContext = MetricContext(),
        baseline: BaselineStats? = null,
    ): List<MetricZone> = when (metric) {
        MetricKind.HEART_RATE -> heartRateZones(profile)
        MetricKind.SPO2 -> spo2Zones(profile)
        MetricKind.HRV -> hrvZones(baseline)
        MetricKind.STRESS -> stressZones()
        MetricKind.FATIGUE -> fatigueZones()
        MetricKind.BLOOD_PRESSURE -> systolicZones()   // legend uses systolic; card shows both
        MetricKind.GLUCOSE -> glucoseZones(context.measurement)
        MetricKind.TEMPERATURE -> temperatureZones()
    }

    /** Interpret a single scalar value. (Blood pressure has its own two-input entry point.) */
    fun interpret(
        value: Double,
        metric: MetricKind,
        profile: UserPhysiologyProfile,
        context: MetricContext = MetricContext(),
        baseline: BaselineStats? = null,
    ): MetricInterpretation = when (metric) {
        MetricKind.HRV -> interpretHRV(value, baseline)
        MetricKind.GLUCOSE -> interpretGlucose(value, context)
        else -> {
            val zones = zones(metric, profile, context, baseline)
            val zone = zoneContaining(value, zones)
            MetricInterpretation(
                primaryZone = zone,
                allZones = zones,
                displayLabel = zone.label,
                explanation = zone.explanation,
                confidenceLabel = null,
                isEstimated = false,
            )
        }
    }

    /**
     * The token a chart/legend/gauge should use at [value]: exactly the zone that contains it. The
     * line, reference band, gauge arc, stat dot, and status label all go through this (or the same
     * zone list), so the same zone is always the same color. (For metrics whose normal zone IS the
     * metric accent — HR, HRV — an in-range line still reads as the accent, because the zone says so.)
     */
    fun colorToken(
        value: Double,
        metric: MetricKind,
        profile: UserPhysiologyProfile,
        context: MetricContext = MetricContext(),
        baseline: BaselineStats? = null,
    ): VitalColorToken =
        interpret(value, metric, profile, context, baseline).primaryZone.colorToken

    /**
     * The resolved zones for a metric in render order. The chart uses this to split a line segment
     * at each zone boundary it crosses, so each piece is colored by the zone it actually falls in.
     */
    fun resolvedZones(
        metric: MetricKind,
        profile: UserPhysiologyProfile,
        context: MetricContext = MetricContext(),
        baseline: BaselineStats? = null,
    ): List<MetricZone> = zones(metric, profile, context, baseline)

    /**
     * The sorted interior thresholds (zone boundaries) for a metric — the y-values where the line
     * color can change. Excludes open ends. Used to split segments at crossings.
     */
    fun zoneThresholds(
        metric: MetricKind,
        profile: UserPhysiologyProfile,
        context: MetricContext = MetricContext(),
        baseline: BaselineStats? = null,
    ): List<Double> {
        val zones = resolvedZones(metric, profile, context, baseline)
        // Each zone's `upper` (when finite) is a boundary; dedupe + sort.
        return zones.mapNotNull { it.upper }.distinct().sorted()
    }

    // ──────────────────── Blood pressure (two inputs → worse-of category) ────────────────────

    /**
     * Interpret a systolic/diastolic pair. The card's category is the **worse** of the two axes
     * (AHA convention). BP from a ring is always estimated; suggests cuff calibration.
     */
    fun interpretBloodPressure(
        systolic: Double,
        diastolic: Double,
        profile: UserPhysiologyProfile,
        hasCuffReference: Boolean = false,
    ): MetricInterpretation {
        val sysZone = zoneContaining(systolic, systolicZones())
        val diaZone = zoneContaining(diastolic, diastolicZones())
        val worse = ZoneSeverity.worst(sysZone.severity, diaZone.severity)
        // Pick whichever axis produced the worse severity for the label/explanation.
        val primary = if (worse == sysZone.severity) sysZone else diaZone
        val confidence = if (hasCuffReference) "Estimated" else "Estimated · calibrate with a cuff"
        return MetricInterpretation(
            primaryZone = primary,
            allZones = systolicZones(),
            displayLabel = primary.label,
            explanation = primary.explanation,
            confidenceLabel = confidence,
            isEstimated = true,
        )
    }

    // ─────────────────────────── Heart rate ───────────────────────────

    private fun heartRateZones(profile: UserPhysiologyProfile): List<MetricZone> {
        // Athletes commonly rest below 60 (and even near 40) — that is optimal, not a concern.
        // Beta-blockers also lower resting HR; we relabel rather than alarm.
        val lowLabel: String
        val lowSeverity: ZoneSeverity
        val lowExplanation: String
        val lowColor: VitalColorToken
        when {
            profile.athleteMode -> {
                lowLabel = "Athletic"
                lowSeverity = ZoneSeverity.OPTIMAL
                lowColor = VitalColorToken.MetricAccent(MetricKind.HEART_RATE)   // a low athletic HR is good, not a caution
                lowExplanation = "A low resting heart rate is common with high fitness."
            }
            profile.usesBetaBlockers -> {
                lowLabel = "Low (medication)"
                lowSeverity = ZoneSeverity.NORMAL
                lowColor = VitalColorToken.Blue
                lowExplanation = "Beta-blockers lower heart rate; this is expected on that medication."
            }
            else -> {
                lowLabel = "Low"
                lowSeverity = ZoneSeverity.WATCH
                lowColor = VitalColorToken.Blue
                lowExplanation = "Below the typical resting range. Often fine, but worth noting if you feel faint."
            }
        }
        return listOf(
            MetricZone("hr.low", lowLabel, null, 60.0, lowSeverity, lowColor, lowExplanation),
            // 60–100 inclusive is normal, so the half-open upper bound is 101.
            MetricZone(
                "hr.normal", "Normal", 60.0, 101.0,
                ZoneSeverity.NORMAL, VitalColorToken.MetricAccent(MetricKind.HEART_RATE),
                "A typical resting heart rate for adults is 60–100 bpm.",
            ),
            MetricZone(
                "hr.elevated", "Elevated", 101.0, 120.0,
                ZoneSeverity.WATCH, VitalColorToken.Amber,
                "Above the typical resting range. Activity, caffeine, or stress can raise it.",
            ),
            MetricZone(
                "hr.high", "High", 120.0, null,
                ZoneSeverity.HIGH, VitalColorToken.BrightRed,
                "A high resting heart rate. Talk to a clinician if it persists at rest.",
            ),
        )
    }

    // ─────────────────────────── SpO₂ ───────────────────────────

    private fun spo2Zones(profile: UserPhysiologyProfile): List<MetricZone> {
        // Altitude and lung conditions lower expected SpO₂; nudge the watch boundary down a touch
        // and note it, rather than alarming on a normal-for-altitude reading.
        val highAltitude = (profile.altitudeMeters ?: 0.0) > 2000
        val watchUpper = if (highAltitude || profile.hasKnownLungCondition) 93.0 else 95.0
        val altitudeNote = if (highAltitude) " Expected values are lower at altitude." else ""
        return listOf(
            MetricZone(
                "spo2.critical", "Very low", null, 89.0,
                ZoneSeverity.CRITICAL, VitalColorToken.Red,
                "An urgently low oxygen reading. Seek care if you also feel unwell.$altitudeNote",
            ),
            MetricZone(
                "spo2.high", "Low", 89.0, 93.0,
                ZoneSeverity.HIGH, VitalColorToken.Orange,
                "Low blood oxygen. Re-measure when still; talk to a clinician if persistent.$altitudeNote",
            ),
            MetricZone(
                "spo2.watch", "Slightly low", 93.0, watchUpper,
                ZoneSeverity.WATCH, VitalColorToken.Amber,
                "Slightly below the typical range.$altitudeNote",
            ),
            MetricZone(
                "spo2.normal", "Normal", watchUpper, null,
                ZoneSeverity.NORMAL, VitalColorToken.Cyan,
                "A normal blood-oxygen level is 95–100%.$altitudeNote",
            ),
        )
    }

    // ─────────────────────────── HRV (baseline-relative) ───────────────────────────

    private fun hrvZones(baseline: BaselineStats?): List<MetricZone> {
        if (baseline == null || !baseline.isEstablished || baseline.standardDeviation <= 0) {
            // No baseline yet → show the metric's own purple accent (not a gray "unknown" color).
            return listOf(
                MetricZone(
                    "hrv.building", "Building baseline", null, null,
                    ZoneSeverity.UNKNOWN, VitalColorToken.MetricAccent(MetricKind.HRV),
                    "HRV is personal. Wear your ring for about a week to learn your baseline.",
                ),
            )
        }
        val mean = baseline.mean
        val sd = baseline.standardDeviation
        return listOf(
            MetricZone(
                "hrv.below", "Below baseline", null, mean - sd,
                ZoneSeverity.HIGH, VitalColorToken.Amber,
                "Notably below your typical HRV — often linked to stress, poor sleep, or strain.",
            ),
            MetricZone(
                "hrv.slightlyBelow", "Slightly below", mean - sd, mean - 0.5 * sd,
                ZoneSeverity.WATCH, VitalColorToken.SoftAmber,
                "A little below your usual range.",
            ),
            MetricZone(
                "hrv.near", "Near baseline", mean - 0.5 * sd, mean + 0.5 * sd,
                ZoneSeverity.NORMAL, VitalColorToken.MetricAccent(MetricKind.HRV),
                "Around your personal baseline.",
            ),
            MetricZone(
                "hrv.above", "Above baseline", mean + 0.5 * sd, null,
                ZoneSeverity.OPTIMAL, VitalColorToken.Mint,
                "Above your typical HRV — often a sign of good recovery.",
            ),
        )
    }

    private fun interpretHRV(value: Double, baseline: BaselineStats?): MetricInterpretation {
        val zones = hrvZones(baseline)
        // No established baseline → single "Building baseline" zone.
        if (baseline == null || !baseline.isEstablished || baseline.standardDeviation <= 0) {
            val zone = zones[0]
            return MetricInterpretation(
                primaryZone = zone, allZones = zones, displayLabel = zone.label,
                explanation = zone.explanation, confidenceLabel = "Building baseline", isEstimated = false,
            )
        }
        val zone = zoneContaining(value, zones)
        val deltaPct = if (baseline.mean > 0) (value - baseline.mean) / baseline.mean * 100 else 0.0
        val delta = abs(deltaPct.roundToInt())
        val direction = if (deltaPct >= 0) "above" else "below"
        return MetricInterpretation(
            primaryZone = zone, allZones = zones, displayLabel = zone.label,
            explanation = zone.explanation,
            confidenceLabel = if (delta == 0) "At baseline" else "$delta% $direction baseline",
            isEstimated = false,
        )
    }

    // ─────────────────────── Stress / Fatigue (device 0–100 scores) ───────────────────────

    private fun stressZones(): List<MetricZone> = listOf(
        MetricZone(
            "stress.calm", "Calm", null, 26.0,
            ZoneSeverity.OPTIMAL, VitalColorToken.Mint, "Low stress score — relaxed.",
        ),
        MetricZone(
            "stress.normal", "Normal", 26.0, 51.0,
            ZoneSeverity.NORMAL, VitalColorToken.Cyan, "A typical daytime stress score.",
        ),
        MetricZone(
            "stress.elevated", "Elevated", 51.0, 76.0,
            ZoneSeverity.WATCH, VitalColorToken.Amber, "Elevated stress — consider a short break.",
        ),
        MetricZone(
            "stress.high", "High", 76.0, null,
            ZoneSeverity.HIGH, VitalColorToken.Red, "High stress score. Wellness estimate, not a diagnosis.",
        ),
    )

    private fun fatigueZones(): List<MetricZone> = listOf(
        MetricZone(
            "fatigue.fresh", "Fresh", null, 25.0,
            ZoneSeverity.OPTIMAL, VitalColorToken.Mint, "Low fatigue — well recovered.",
        ),
        MetricZone(
            "fatigue.mild", "Mild", 25.0, 50.0,
            ZoneSeverity.NORMAL, VitalColorToken.Cyan, "Mild fatigue.",
        ),
        MetricZone(
            "fatigue.tired", "Tired", 50.0, 75.0,
            ZoneSeverity.WATCH, VitalColorToken.Amber, "Tired — consider lighter activity and good sleep.",
        ),
        MetricZone(
            "fatigue.high", "High fatigue", 75.0, null,
            ZoneSeverity.HIGH, VitalColorToken.Red, "High fatigue score. Wellness estimate from the ring.",
        ),
    )

    // ─────────────────────────── Blood pressure axes ───────────────────────────

    /**
     * Public accessor for the diastolic reference zones (the systolic zones are returned by
     * `zones(MetricKind.BLOOD_PRESSURE …)`). Used by the dual-ring gauge's inner track.
     */
    fun diastolicReferenceZones(): List<MetricZone> = diastolicZones()

    private fun systolicZones(): List<MetricZone> = listOf(
        MetricZone(
            "bp.sys.low", "Low", null, 90.0,
            ZoneSeverity.WATCH, VitalColorToken.Blue, "Low systolic pressure (below 90).",
        ),
        MetricZone(
            "bp.sys.normal", "Normal", 90.0, 120.0,
            ZoneSeverity.NORMAL, VitalColorToken.Mint, "Normal blood pressure is below 120/80.",
        ),
        MetricZone(
            "bp.sys.elevated", "Elevated", 120.0, 130.0,
            ZoneSeverity.WATCH, VitalColorToken.Amber, "Elevated systolic (120–129).",
        ),
        MetricZone(
            "bp.sys.stage1", "Stage 1", 130.0, 140.0,
            ZoneSeverity.HIGH, VitalColorToken.Orange, "Stage 1 hypertension range (systolic 130–139).",
        ),
        MetricZone(
            "bp.sys.stage2", "Stage 2", 140.0, 180.0,
            ZoneSeverity.HIGH, VitalColorToken.Red, "Stage 2 hypertension range (systolic ≥140).",
        ),
        MetricZone(
            "bp.sys.crisis", "Severe", 180.0, null,
            ZoneSeverity.CRITICAL, VitalColorToken.Red, "Severe range (systolic >180). Seek care if confirmed.",
        ),
    )

    private fun diastolicZones(): List<MetricZone> = listOf(
        MetricZone(
            "bp.dia.low", "Low", null, 60.0,
            ZoneSeverity.WATCH, VitalColorToken.Blue, "Low diastolic pressure (below 60).",
        ),
        MetricZone(
            "bp.dia.normal", "Normal", 60.0, 80.0,
            ZoneSeverity.NORMAL, VitalColorToken.Mint, "Normal diastolic is below 80.",
        ),
        // Note: there is no diastolic "Elevated" category — 80–89 is already Stage 1 by AHA.
        MetricZone(
            "bp.dia.stage1", "Stage 1", 80.0, 90.0,
            ZoneSeverity.HIGH, VitalColorToken.Orange, "Stage 1 hypertension range (diastolic 80–89).",
        ),
        MetricZone(
            "bp.dia.stage2", "Stage 2", 90.0, 120.0,
            ZoneSeverity.HIGH, VitalColorToken.Red, "Stage 2 hypertension range (diastolic ≥90).",
        ),
        MetricZone(
            "bp.dia.crisis", "Severe", 120.0, null,
            ZoneSeverity.CRITICAL, VitalColorToken.Red, "Severe range (diastolic >120). Seek care if confirmed.",
        ),
    )

    // ─────────────────────────── Glucose ───────────────────────────

    private fun glucoseZones(context: MeasurementContext): List<MetricZone> = when (context) {
        MeasurementContext.FASTING -> listOf(
            MetricZone(
                "glucose.low", "Low", null, 70.0,
                ZoneSeverity.HIGH, VitalColorToken.Red, "Below 70 mg/dL is low.",
            ),
            MetricZone(
                "glucose.normal", "Normal", 70.0, 100.0,
                ZoneSeverity.NORMAL, VitalColorToken.Mint, "Fasting normal is up to 99 mg/dL.",
            ),
            MetricZone(
                "glucose.elevated", "Elevated", 100.0, 126.0,
                ZoneSeverity.WATCH, VitalColorToken.Amber, "Fasting 100–125 is above the typical range.",
            ),
            MetricZone(
                "glucose.high", "High", 126.0, null,
                ZoneSeverity.HIGH, VitalColorToken.Red, "Fasting ≥126. Talk to a clinician if confirmed by a meter.",
            ),
        )
        MeasurementContext.POST_MEAL -> listOf(
            MetricZone(
                "glucose.low", "Low", null, 70.0,
                ZoneSeverity.HIGH, VitalColorToken.Red, "Below 70 mg/dL is low.",
            ),
            MetricZone(
                "glucose.normal", "Normal", 70.0, 140.0,
                ZoneSeverity.NORMAL, VitalColorToken.Mint, "Two-hour post-meal normal is up to 140 mg/dL.",
            ),
            MetricZone(
                "glucose.elevated", "Elevated", 140.0, 200.0,
                ZoneSeverity.WATCH, VitalColorToken.Amber, "Two-hour 140–199 is above the typical range.",
            ),
            MetricZone(
                "glucose.high", "High", 200.0, null,
                ZoneSeverity.HIGH, VitalColorToken.Red, "Two-hour ≥200. Confirm with a meter and a clinician.",
            ),
        )
        else ->
            // Unknown / random context: conservative labels, NO "prediabetes"/"diabetes" wording.
            listOf(
                MetricZone(
                    "glucose.low", "Low", null, 70.0,
                    ZoneSeverity.HIGH, VitalColorToken.Red, "Below 70 mg/dL is low.",
                ),
                MetricZone(
                    "glucose.typical", "Typical", 70.0, 140.0,
                    ZoneSeverity.NORMAL, VitalColorToken.Mint, "Within a typical range for a non-fasting reading.",
                ),
                MetricZone(
                    "glucose.elevated", "Elevated", 140.0, 200.0,
                    ZoneSeverity.WATCH, VitalColorToken.Amber, "Above the typical range. Context (meals) affects this.",
                ),
                MetricZone(
                    "glucose.veryHigh", "Very high", 200.0, null,
                    ZoneSeverity.HIGH, VitalColorToken.Red, "A high reading regardless of context. Confirm with a meter.",
                ),
            )
    }

    private fun interpretGlucose(value: Double, context: MetricContext): MetricInterpretation {
        val zones = glucoseZones(context.measurement)
        val zone = zoneContaining(value, zones)
        return MetricInterpretation(
            primaryZone = zone, allZones = zones, displayLabel = zone.label,
            explanation = zone.explanation,
            confidenceLabel = "Estimated — not for dosing or diagnosis",
            isEstimated = true,
        )
    }

    // ─────────────────────────── Temperature (skin) ───────────────────────────

    private fun temperatureZones(): List<MetricZone> =
        // Skin (not core) temperature; ring values run cooler than oral. Trend matters more than
        // absolute, so we keep a single soft "typical" band and flag extremes only.
        listOf(
            MetricZone(
                "temp.low", "Cool", null, 31.0,
                ZoneSeverity.WATCH, VitalColorToken.Blue, "Cooler than typical skin temperature.",
            ),
            MetricZone(
                "temp.normal", "Typical", 31.0, 36.0,
                ZoneSeverity.NORMAL, VitalColorToken.MetricAccent(MetricKind.TEMPERATURE),
                "A typical skin-temperature range from the ring.",
            ),
            MetricZone(
                "temp.high", "Warm", 36.0, null,
                ZoneSeverity.WATCH, VitalColorToken.Amber, "Warmer than typical. Trends matter more than a single reading.",
            ),
        )

    // ─────────────────────────── Helpers ───────────────────────────

    /** The zone a value falls into, falling back to the nearest end zone if outside all bands. */
    private fun zoneContaining(value: Double, zones: List<MetricZone>): MetricZone {
        zones.firstOrNull { it.contains(value) }?.let { return it }
        // Outside every band: clamp to the first/last zone.
        val first = zones.first()
        val lower = first.lower
        if (lower != null && value < lower) return first
        return zones.last()
    }
}

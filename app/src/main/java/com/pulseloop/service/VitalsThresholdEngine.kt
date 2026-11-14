package com.pulseloop.service

import com.pulseloop.R
import com.pulseloop.util.L10n
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
        val confidence = if (hasCuffReference) L10n.get(R.string.badge_estimated) else L10n.get(R.string.zc_estimated_calibrate)
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
        val mode = profile.hrZoneModeRaw ?: "auto"
        val athlete = profile.athleteMode
        val baseline = profile.hrRestingBaseline

        val lowUpper: Double
        val athleticUpper: Double?
        val elevatedStart: Double
        val highStart: Double

        when (mode) {
            "custom" -> {
                lowUpper = profile.hrCustomLowUpper ?: 50.0
                athleticUpper = if (athlete) profile.hrCustomAthleticUpper ?: 60.0 else null
                elevatedStart = profile.hrCustomElevatedStart ?: 90.0
                highStart = profile.hrCustomHighStart ?: 120.0
            }
            "auto" -> {
                if (baseline != null) {
                    lowUpper = clamp(baseline - 12, min = 35.0, max = if (athlete) 40.0 else 55.0)
                    elevatedStart = clamp(baseline + 40, min = 85.0, max = 105.0)
                    highStart = elevatedStart + 25
                } else {
                    lowUpper = if (athlete) 40.0 else 50.0
                    elevatedStart = 90.0
                    highStart = 120.0
                }
                athleticUpper = if (athlete) 60.0 else null
            }
            else -> { // "standard"
                lowUpper = if (athlete) 40.0 else 50.0
                athleticUpper = if (athlete) 60.0 else null
                elevatedStart = 90.0
                highStart = 120.0
            }
        }

        val lowLabel: String
        val lowSeverity: ZoneSeverity
        val lowExplanation: String
        val lowColor: VitalColorToken
        when {
            athlete -> {
                lowLabel = L10n.get(R.string.zl_athletic)
                lowSeverity = ZoneSeverity.OPTIMAL
                lowColor = VitalColorToken.MetricAccent(MetricKind.HEART_RATE)
                lowExplanation = L10n.get(R.string.ze_hr_athletic_low)
            }
            profile.usesBetaBlockers -> {
                lowLabel = L10n.get(R.string.zl_low_medication)
                lowSeverity = ZoneSeverity.NORMAL
                lowColor = VitalColorToken.Blue
                lowExplanation = L10n.get(R.string.ze_hr_betablockers)
            }
            else -> {
                lowLabel = L10n.get(R.string.zl_low)
                lowSeverity = ZoneSeverity.WATCH
                lowColor = VitalColorToken.Blue
                lowExplanation = L10n.get(R.string.ze_hr_low)
            }
        }

        val zones = mutableListOf<MetricZone>()
        zones.add(MetricZone("hr.low", lowLabel, null, lowUpper, lowSeverity, lowColor, lowExplanation))
        if (athlete && athleticUpper != null) {
            zones.add(MetricZone("hr.athletic", L10n.get(R.string.zl_athletic), lowUpper, athleticUpper, ZoneSeverity.OPTIMAL, VitalColorToken.Mint, L10n.get(R.string.ze_hr_athletic)))
        }
        val normalLow = if (athlete && athleticUpper != null) athleticUpper else lowUpper
        zones.add(MetricZone("hr.normal", L10n.get(R.string.zl_normal), normalLow, elevatedStart,
            ZoneSeverity.NORMAL, VitalColorToken.MetricAccent(MetricKind.HEART_RATE),
            L10n.get(R.string.ze_hr_normal)))
        zones.add(MetricZone("hr.elevated", L10n.get(R.string.zl_elevated), elevatedStart, highStart,
            ZoneSeverity.WATCH, VitalColorToken.Amber,
            L10n.get(R.string.ze_hr_elevated)))
        zones.add(MetricZone("hr.high", L10n.get(R.string.zl_high), highStart, null,
            ZoneSeverity.HIGH, VitalColorToken.BrightRed,
            L10n.get(R.string.ze_hr_high)))
        return zones
    }

    private fun clamp(value: Double, min: Double, max: Double): Double =
        value.coerceIn(min, max)

    // ─────────────────────────── SpO₂ ───────────────────────────

    private fun spo2Zones(profile: UserPhysiologyProfile): List<MetricZone> {
        // Altitude and lung conditions lower expected SpO₂; nudge the watch boundary down a touch
        // and note it, rather than alarming on a normal-for-altitude reading.
        val highAltitude = (profile.altitudeMeters ?: 0.0) > 2000
        val watchUpper = if (highAltitude || profile.hasKnownLungCondition) 93.0 else 95.0
        val altitudeNote = if (highAltitude) " " + L10n.get(R.string.ze_spo2_altitude) else ""
        return listOf(
            MetricZone(
                "spo2.critical", L10n.get(R.string.zl_very_low), null, 89.0,
                ZoneSeverity.CRITICAL, VitalColorToken.Red,
                L10n.get(R.string.ze_spo2_critical) + altitudeNote,
            ),
            MetricZone(
                "spo2.high", L10n.get(R.string.zl_low), 89.0, 93.0,
                ZoneSeverity.HIGH, VitalColorToken.Orange,
                L10n.get(R.string.ze_spo2_low) + altitudeNote,
            ),
            MetricZone(
                "spo2.watch", L10n.get(R.string.zl_slightly_low), 93.0, watchUpper,
                ZoneSeverity.WATCH, VitalColorToken.Amber,
                L10n.get(R.string.ze_spo2_watch) + altitudeNote,
            ),
            MetricZone(
                "spo2.normal", L10n.get(R.string.zl_normal), watchUpper, null,
                ZoneSeverity.NORMAL, VitalColorToken.Cyan,
                L10n.get(R.string.ze_spo2_normal) + altitudeNote,
            ),
        )
    }

    // ─────────────────────────── HRV (baseline-relative) ───────────────────────────

    private fun hrvZones(baseline: BaselineStats?): List<MetricZone> {
        if (baseline == null || !baseline.isEstablished || baseline.standardDeviation <= 0) {
            // No baseline yet → show the metric's own purple accent (not a gray "unknown" color).
            return listOf(
                MetricZone(
                    "hrv.building", L10n.get(R.string.zl_building_baseline), null, null,
                    ZoneSeverity.UNKNOWN, VitalColorToken.MetricAccent(MetricKind.HRV),
                    L10n.get(R.string.ze_hrv_building),
                ),
            )
        }
        val mean = baseline.mean
        val sd = baseline.standardDeviation
        return listOf(
            MetricZone(
                "hrv.below", L10n.get(R.string.zl_below_baseline), null, mean - sd,
                ZoneSeverity.HIGH, VitalColorToken.Amber,
                L10n.get(R.string.ze_hrv_below),
            ),
            MetricZone(
                "hrv.slightlyBelow", L10n.get(R.string.zl_slightly_below), mean - sd, mean - 0.5 * sd,
                ZoneSeverity.WATCH, VitalColorToken.SoftAmber,
                L10n.get(R.string.ze_hrv_slightly_below),
            ),
            MetricZone(
                "hrv.near", L10n.get(R.string.zl_near_baseline), mean - 0.5 * sd, mean + 0.5 * sd,
                ZoneSeverity.NORMAL, VitalColorToken.MetricAccent(MetricKind.HRV),
                L10n.get(R.string.ze_hrv_near),
            ),
            MetricZone(
                "hrv.above", L10n.get(R.string.zl_above_baseline), mean + 0.5 * sd, null,
                ZoneSeverity.OPTIMAL, VitalColorToken.Mint,
                L10n.get(R.string.ze_hrv_above),
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
                explanation = zone.explanation, confidenceLabel = L10n.get(R.string.zl_building_baseline), isEstimated = false,
            )
        }
        val zone = zoneContaining(value, zones)
        val deltaPct = if (baseline.mean > 0) (value - baseline.mean) / baseline.mean * 100 else 0.0
        val delta = abs(deltaPct.roundToInt())
        return MetricInterpretation(
            primaryZone = zone, allZones = zones, displayLabel = zone.label,
            explanation = zone.explanation,
            confidenceLabel = when {
                delta == 0 -> L10n.get(R.string.zc_at_baseline)
                deltaPct >= 0 -> L10n.get(R.string.zc_above_baseline_pct, delta)
                else -> L10n.get(R.string.zc_below_baseline_pct, delta)
            },
            isEstimated = false,
        )
    }

    // ─────────────────────── Stress / Fatigue (device 0–100 scores) ───────────────────────

    private fun stressZones(): List<MetricZone> = listOf(
        MetricZone(
            "stress.calm", L10n.get(R.string.zl_calm), null, 26.0,
            ZoneSeverity.OPTIMAL, VitalColorToken.Mint, L10n.get(R.string.ze_stress_calm),
        ),
        MetricZone(
            "stress.normal", L10n.get(R.string.zl_normal), 26.0, 51.0,
            ZoneSeverity.NORMAL, VitalColorToken.Cyan, L10n.get(R.string.ze_stress_normal),
        ),
        MetricZone(
            "stress.elevated", L10n.get(R.string.zl_elevated), 51.0, 76.0,
            ZoneSeverity.WATCH, VitalColorToken.Amber, L10n.get(R.string.ze_stress_elevated),
        ),
        MetricZone(
            "stress.high", L10n.get(R.string.zl_high), 76.0, null,
            ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_stress_high),
        ),
    )

    private fun fatigueZones(): List<MetricZone> = listOf(
        MetricZone(
            "fatigue.fresh", L10n.get(R.string.zl_fresh), null, 25.0,
            ZoneSeverity.OPTIMAL, VitalColorToken.Mint, L10n.get(R.string.ze_fatigue_fresh),
        ),
        MetricZone(
            "fatigue.mild", L10n.get(R.string.zl_mild), 25.0, 50.0,
            ZoneSeverity.NORMAL, VitalColorToken.Cyan, L10n.get(R.string.ze_fatigue_mild),
        ),
        MetricZone(
            "fatigue.tired", L10n.get(R.string.zl_tired), 50.0, 75.0,
            ZoneSeverity.WATCH, VitalColorToken.Amber, L10n.get(R.string.ze_fatigue_tired),
        ),
        MetricZone(
            "fatigue.high", L10n.get(R.string.zl_high_fatigue), 75.0, null,
            ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_fatigue_high),
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
            "bp.sys.low", L10n.get(R.string.zl_low), null, 90.0,
            ZoneSeverity.WATCH, VitalColorToken.Blue, L10n.get(R.string.ze_bp_sys_low),
        ),
        MetricZone(
            "bp.sys.normal", L10n.get(R.string.zl_normal), 90.0, 120.0,
            ZoneSeverity.NORMAL, VitalColorToken.Mint, L10n.get(R.string.ze_bp_sys_normal),
        ),
        MetricZone(
            "bp.sys.elevated", L10n.get(R.string.zl_elevated), 120.0, 130.0,
            ZoneSeverity.WATCH, VitalColorToken.Amber, L10n.get(R.string.ze_bp_sys_elevated),
        ),
        MetricZone(
            "bp.sys.stage1", L10n.get(R.string.zl_stage1), 130.0, 140.0,
            ZoneSeverity.HIGH, VitalColorToken.Orange, L10n.get(R.string.ze_bp_sys_stage1),
        ),
        MetricZone(
            "bp.sys.stage2", L10n.get(R.string.zl_stage2), 140.0, 180.0,
            ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_bp_sys_stage2),
        ),
        MetricZone(
            "bp.sys.crisis", L10n.get(R.string.zl_severe), 180.0, null,
            ZoneSeverity.CRITICAL, VitalColorToken.Red, L10n.get(R.string.ze_bp_sys_crisis),
        ),
    )

    private fun diastolicZones(): List<MetricZone> = listOf(
        MetricZone(
            "bp.dia.low", L10n.get(R.string.zl_low), null, 60.0,
            ZoneSeverity.WATCH, VitalColorToken.Blue, L10n.get(R.string.ze_bp_dia_low),
        ),
        MetricZone(
            "bp.dia.normal", L10n.get(R.string.zl_normal), 60.0, 80.0,
            ZoneSeverity.NORMAL, VitalColorToken.Mint, L10n.get(R.string.ze_bp_dia_normal),
        ),
        // Note: there is no diastolic "Elevated" category — 80–89 is already Stage 1 by AHA.
        MetricZone(
            "bp.dia.stage1", L10n.get(R.string.zl_stage1), 80.0, 90.0,
            ZoneSeverity.HIGH, VitalColorToken.Orange, L10n.get(R.string.ze_bp_dia_stage1),
        ),
        MetricZone(
            "bp.dia.stage2", L10n.get(R.string.zl_stage2), 90.0, 120.0,
            ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_bp_dia_stage2),
        ),
        MetricZone(
            "bp.dia.crisis", L10n.get(R.string.zl_severe), 120.0, null,
            ZoneSeverity.CRITICAL, VitalColorToken.Red, L10n.get(R.string.ze_bp_dia_crisis),
        ),
    )

    // ─────────────────────────── Glucose ───────────────────────────

    private fun glucoseZones(context: MeasurementContext): List<MetricZone> = when (context) {
        MeasurementContext.FASTING -> listOf(
            MetricZone(
                "glucose.low", L10n.get(R.string.zl_low), null, 70.0,
                ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_glu_low),
            ),
            MetricZone(
                "glucose.normal", L10n.get(R.string.zl_normal), 70.0, 100.0,
                ZoneSeverity.NORMAL, VitalColorToken.Mint, L10n.get(R.string.ze_glu_fasting_normal),
            ),
            MetricZone(
                "glucose.elevated", L10n.get(R.string.zl_elevated), 100.0, 126.0,
                ZoneSeverity.WATCH, VitalColorToken.Amber, L10n.get(R.string.ze_glu_fasting_elevated),
            ),
            MetricZone(
                "glucose.high", L10n.get(R.string.zl_high), 126.0, null,
                ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_glu_fasting_high),
            ),
        )
        MeasurementContext.POST_MEAL -> listOf(
            MetricZone(
                "glucose.low", L10n.get(R.string.zl_low), null, 70.0,
                ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_glu_low),
            ),
            MetricZone(
                "glucose.normal", L10n.get(R.string.zl_normal), 70.0, 140.0,
                ZoneSeverity.NORMAL, VitalColorToken.Mint, L10n.get(R.string.ze_glu_post_normal),
            ),
            MetricZone(
                "glucose.elevated", L10n.get(R.string.zl_elevated), 140.0, 200.0,
                ZoneSeverity.WATCH, VitalColorToken.Amber, L10n.get(R.string.ze_glu_post_elevated),
            ),
            MetricZone(
                "glucose.high", L10n.get(R.string.zl_high), 200.0, null,
                ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_glu_post_high),
            ),
        )
        else ->
            // Unknown / random context: conservative labels, NO "prediabetes"/"diabetes" wording.
            listOf(
                MetricZone(
                    "glucose.low", L10n.get(R.string.zl_low), null, 70.0,
                    ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_glu_low),
                ),
                MetricZone(
                    "glucose.typical", L10n.get(R.string.zl_typical), 70.0, 140.0,
                    ZoneSeverity.NORMAL, VitalColorToken.Mint, L10n.get(R.string.ze_glu_typical),
                ),
                MetricZone(
                    "glucose.elevated", L10n.get(R.string.zl_elevated), 140.0, 200.0,
                    ZoneSeverity.WATCH, VitalColorToken.Amber, L10n.get(R.string.ze_glu_elevated),
                ),
                MetricZone(
                    "glucose.veryHigh", L10n.get(R.string.zl_very_high), 200.0, null,
                    ZoneSeverity.HIGH, VitalColorToken.Red, L10n.get(R.string.ze_glu_very_high),
                ),
            )
    }

    private fun interpretGlucose(value: Double, context: MetricContext): MetricInterpretation {
        val zones = glucoseZones(context.measurement)
        val zone = zoneContaining(value, zones)
        return MetricInterpretation(
            primaryZone = zone, allZones = zones, displayLabel = zone.label,
            explanation = zone.explanation,
            confidenceLabel = L10n.get(R.string.zc_estimated_no_dosing),
            isEstimated = true,
        )
    }

    // ─────────────────────────── Temperature (skin) ───────────────────────────

    private fun temperatureZones(): List<MetricZone> =
        // Skin (not core) temperature; ring values run cooler than oral. Trend matters more than
        // absolute, so we keep a single soft "typical" band and flag extremes only.
        listOf(
            MetricZone(
                "temp.low", L10n.get(R.string.zl_cool), null, 31.0,
                ZoneSeverity.WATCH, VitalColorToken.Blue, L10n.get(R.string.ze_temp_low),
            ),
            MetricZone(
                "temp.normal", L10n.get(R.string.zl_typical), 31.0, 36.0,
                ZoneSeverity.NORMAL, VitalColorToken.MetricAccent(MetricKind.TEMPERATURE),
                L10n.get(R.string.ze_temp_normal),
            ),
            MetricZone(
                "temp.high", L10n.get(R.string.zl_warm), 36.0, null,
                ZoneSeverity.WATCH, VitalColorToken.Amber, L10n.get(R.string.ze_temp_high),
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

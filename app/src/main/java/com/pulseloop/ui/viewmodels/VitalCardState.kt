package com.pulseloop.ui.viewmodels

import com.pulseloop.service.BaselineStats
import com.pulseloop.service.MeasurementContext
import com.pulseloop.service.MetricContext
import com.pulseloop.service.MetricKind
import com.pulseloop.service.MetricZone
import com.pulseloop.service.SourceQuality
import com.pulseloop.service.UserPhysiologyProfile
import com.pulseloop.service.VitalColorToken
import com.pulseloop.service.VitalSample
import com.pulseloop.service.VitalsThresholdEngine
import com.pulseloop.settings.UnitSystem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Fully-prepared state for a single vitals card. Ported from ViewModels/VitalCardViewModel.swift +
// ViewModels/VitalsCardFactory.swift. Computed once per state rebuild (off the composition path) so
// composables never run threshold/baseline/trend math during layout. Pure data — colors stay as
// [VitalColorToken]s and resolve in the UI via `toColor()` (ZonePalette.kt).

// ─────────────────────────── Trend ───────────────────────────

enum class TrendDirection {
    RISING,
    FALLING,
    STABLE,
    INSUFFICIENT_DATA,
}

data class TrendSummary(
    val direction: TrendDirection,
    val deltaText: String?,
    /** Arrow glyph for the trend (iOS uses SF Symbols; here a text glyph the card renders inline). */
    val symbol: String,
) {
    companion object {
        val INSUFFICIENT = TrendSummary(TrendDirection.INSUFFICIENT_DATA, null, "–")

        /**
         * Compute a trend from samples. Dense series (many points across the window) compare the
         * recent half-window mean to the prior half-window mean; sparse series compare the latest
         * two readings. HRV compares the latest value to its baseline mean. Requires ≥3 meaningful
         * samples.
         */
        fun compute(
            samples: List<VitalSample>,
            metric: MetricKind,
            baseline: BaselineStats? = null,
            unitLabel: String = "",
        ): TrendSummary {
            val values = samples.map { it.value }.filter { it > 0 }
            if (values.size < 3) return INSUFFICIENT

            // HRV: latest vs personal baseline.
            if (metric == MetricKind.HRV && baseline != null && baseline.isEstablished) {
                val latest = values.lastOrNull() ?: baseline.mean
                return summarize(latest - baseline.mean, unitLabel, "vs baseline")
            }

            // Dense (≥8 points over the window): split at the time midpoint, compare half means.
            if (samples.size >= 8) {
                val first = samples.first().timestampMs
                val last = samples.last().timestampMs
                val mid = first + (last - first) / 2
                val recent = samples.filter { it.timestampMs >= mid }.map { it.value }
                val prior = samples.filter { it.timestampMs < mid }.map { it.value }
                if (recent.isNotEmpty() && prior.isNotEmpty()) {
                    return summarize(recent.average() - prior.average(), unitLabel, "vs earlier")
                }
            }

            // Sparse: latest minus previous.
            val latest = values[values.size - 1]
            val previous = values[values.size - 2]
            return summarize(latest - previous, unitLabel, "vs previous")
        }

        /**
         * Build a summary from a signed delta. A small delta reads as "stable"; otherwise
         * rising/falling with a `+N unit suffix` label.
         */
        private fun summarize(delta: Double, unitLabel: String, suffix: String): TrendSummary {
            // Sub-unit deltas keep one decimal (truncated, matching iOS Int-cast); larger deltas round.
            val rounded = if (abs(delta) < 1 && delta != 0.0) (delta * 10).toInt() / 10.0 else roundAwayFromZero(delta)
            if (abs(rounded) < 0.5) {
                return TrendSummary(TrendDirection.STABLE, "Stable $suffix", "→")
            }
            val unit = if (unitLabel.isEmpty()) "" else " $unitLabel"
            val sign = if (rounded > 0) "+" else "−"
            val magnitude = if (abs(rounded) % 1.0 == 0.0) "${abs(rounded).toInt()}" else "${abs(rounded)}"
            val text = "$sign$magnitude$unit $suffix"
            return TrendSummary(
                direction = if (rounded > 0) TrendDirection.RISING else TrendDirection.FALLING,
                deltaText = text,
                symbol = if (rounded > 0) "↗" else "↘",
            )
        }

        /** Swift `Double.rounded()`: nearest integer, ties away from zero. */
        private fun roundAwayFromZero(x: Double): Double =
            if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)
    }
}

// ─────────────────────────── Reference bands ───────────────────────────

/**
 * A shaded horizontal band behind the line marking a useful range (e.g. SpO₂ 95–100). Resolves its
 * color through a [VitalColorToken], so it stays consistent with zone coloring everywhere.
 */
data class ReferenceBand(
    val lower: Double,
    val upper: Double,
    val colorToken: VitalColorToken,
    val opacity: Float = 0.08f,
)

// ─────────────────────────── Card state ───────────────────────────

/** The complete, view-ready model for one metric card. */
data class VitalCardState(
    val metric: MetricKind,
    val title: String,
    val valueText: String,
    /** The raw reading behind [valueText], in the card's display unit (needle position for
     *  gauges). Never parse [valueText] back — it's locale-formatted display text. Null when
     *  there's no reading (or no single number, e.g. BP's composite label). */
    val latestValue: Double? = null,
    val unitText: String?,
    val statusText: String,
    val statusToken: VitalColorToken,
    val subtitleText: String?,
    val samples: List<VitalSample>,
    val zones: List<MetricZone>,
    val yDomain: ClosedFloatingPointRange<Double>,
    val referenceBands: List<ReferenceBand>,
    val dashedRules: List<Double>,
    val trend: TrendSummary,
    val sourceQuality: SourceQuality,
    val isEstimated: Boolean,
    val confidenceLabel: String?,
    val lastUpdatedText: String?,
    /** True when there's nothing to chart yet (drives the empty-state card while preserving height). */
    val isEmpty: Boolean,
) {
    val accentToken: VitalColorToken get() = metric.accentToken
}

// ─────────────────────────── Factory ───────────────────────────

/**
 * Builds [VitalCardState]s from raw sample arrays + the user's physiology profile. All
 * threshold/baseline/trend/label math lives here (called once per rebuild), so composables stay
 * declarative. BP is special: it consumes two series and produces a single card. Pure functions —
 * no Room reads; screens fetch and pass samples in.
 */
object VitalsCardFactory {

    /** Input bundle so the call site (the view model) stays terse and the factory is unit-testable. */
    data class Inputs(
        val hr: List<VitalSample> = emptyList(),
        val spo2: List<VitalSample> = emptyList(),
        val hrv: List<VitalSample> = emptyList(),
        val stress: List<VitalSample> = emptyList(),
        val fatigue: List<VitalSample> = emptyList(),
        val temperature: List<VitalSample> = emptyList(),
        val systolic: List<VitalSample> = emptyList(),
        val diastolic: List<VitalSample> = emptyList(),
        val glucose: List<VitalSample> = emptyList(),
        /** Fallback latest values when the 24h window is empty but a reading exists (iOS TodaySummary). */
        val latestHr: Double? = null,
        val latestSpo2: Double? = null,
        /** Resting/peak HR estimates for the HR subtitle (iOS TodaySummary fields). */
        val restingHr: Double? = null,
        val peakHr: Double? = null,
        val nowMs: Long = System.currentTimeMillis(),
        val unitSystem: UnitSystem = UnitSystem.METRIC,
        /** Calibration state (iOS `Calibration`): drives BP/glucose "needs calibration" quality. */
        val hasBPReference: Boolean = false,
        val isGlucoseCalibrated: Boolean = false,
    )

    fun card(metric: MetricKind, inputs: Inputs, profile: UserPhysiologyProfile): VitalCardState =
        when (metric) {
            MetricKind.HEART_RATE -> heartRate(inputs, profile)
            MetricKind.SPO2 -> spo2(inputs, profile)
            MetricKind.HRV -> hrv(inputs, profile)
            MetricKind.STRESS -> scoreCard(MetricKind.STRESS, inputs.stress, inputs, profile)
            MetricKind.FATIGUE -> scoreCard(MetricKind.FATIGUE, inputs.fatigue, inputs, profile)
            MetricKind.TEMPERATURE -> temperature(inputs, profile)
            MetricKind.BLOOD_PRESSURE -> bloodPressure(inputs, profile)
            MetricKind.GLUCOSE -> glucose(inputs, profile)
        }

    // ─────────────────────────── Heart rate ───────────────────────────

    private fun heartRate(inputs: Inputs, profile: UserPhysiologyProfile): VitalCardState {
        val samples = inputs.hr
        val latest = samples.lastOrNull()?.value ?: inputs.latestHr
        val quality = quality(MetricKind.HEART_RATE, samples.lastOrNull()?.timestampMs, inputs)
        val interp = latest?.let { VitalsThresholdEngine.interpret(it, MetricKind.HEART_RATE, profile) }

        val valueText = rangeLabel(samples, latest)
        val resting = inputs.restingHr?.let { "Resting ${it.toInt()}" } ?: "Resting calibrating"
        val peak = inputs.peakHr?.let { "Peak ${it.toInt()}" } ?: "Peak —"
        val domain = clampedDomain(samples, 40.0..140.0, pad = 8.0, hardLower = 40.0, hardUpper = 220.0)
        return VitalCardState(
            metric = MetricKind.HEART_RATE, title = MetricKind.HEART_RATE.title,
            valueText = valueText, latestValue = latest,
            unitText = if (latest != null) "bpm range" else null,
            statusText = interp?.displayLabel ?: "No reading",
            statusToken = interp?.statusColorToken ?: VitalColorToken.Neutral,
            subtitleText = "$resting · $peak",
            samples = samples, zones = VitalsThresholdEngine.zones(MetricKind.HEART_RATE, profile),
            yDomain = domain, referenceBands = normalBand(MetricKind.HEART_RATE, profile, domain),
            dashedRules = emptyList(),
            trend = TrendSummary.compute(samples, MetricKind.HEART_RATE, unitLabel = "bpm"),
            sourceQuality = quality, isEstimated = false, confidenceLabel = null,
            lastUpdatedText = lastUpdated(samples.lastOrNull()?.timestampMs, inputs.nowMs),
            isEmpty = samples.size < 2,
        )
    }

    // ─────────────────────────── SpO₂ ───────────────────────────

    private fun spo2(inputs: Inputs, profile: UserPhysiologyProfile): VitalCardState {
        val samples = inputs.spo2
        val latest = samples.lastOrNull()?.value ?: inputs.latestSpo2
        val quality = quality(MetricKind.SPO2, samples.lastOrNull()?.timestampMs, inputs)
        val interp = latest?.let { VitalsThresholdEngine.interpret(it, MetricKind.SPO2, profile) }
        val lowest = samples.map { it.value }.filter { it > 0 }.minOrNull()
        val subtitle = lowest?.let { "Lowest ${it.toInt()} · ${samples.size} readings" }
        return VitalCardState(
            metric = MetricKind.SPO2, title = MetricKind.SPO2.title,
            valueText = averageLabel(samples, latest), latestValue = latest,
            unitText = if (latest != null) "% avg" else null,
            statusText = interp?.displayLabel ?: "No reading",
            statusToken = interp?.statusColorToken ?: VitalColorToken.Neutral,
            subtitleText = subtitle,
            samples = samples, zones = VitalsThresholdEngine.zones(MetricKind.SPO2, profile),
            yDomain = 88.0..100.0,
            referenceBands = listOf(ReferenceBand(95.0, 100.0, VitalColorToken.Cyan)),
            dashedRules = listOf(92.0),
            trend = TrendSummary.compute(samples, MetricKind.SPO2, unitLabel = "%"),
            sourceQuality = quality, isEstimated = false, confidenceLabel = null,
            lastUpdatedText = lastUpdated(samples.lastOrNull()?.timestampMs, inputs.nowMs),
            isEmpty = samples.size < 2,
        )
    }

    // ─────────────────────────── HRV (baseline-relative) ───────────────────────────

    private fun hrv(inputs: Inputs, profile: UserPhysiologyProfile): VitalCardState {
        val samples = inputs.hrv
        val baseline = BaselineStats.compute(samples)
        val latest = samples.lastOrNull()?.value
        val quality = quality(MetricKind.HRV, samples.lastOrNull()?.timestampMs, inputs)
        val interp = latest?.let { VitalsThresholdEngine.interpret(it, MetricKind.HRV, profile, baseline = baseline) }
        val domain = clampedDomain(samples, 0.0..120.0, pad = 10.0, hardLower = 0.0, hardUpper = 250.0)
        var bands = emptyList<ReferenceBand>()
        if (baseline != null && baseline.isEstablished) {
            val half = max(6.0, baseline.mean * 0.12)
            bands = listOf(
                ReferenceBand(baseline.mean - half, baseline.mean + half, MetricKind.HRV.accentToken, opacity = 0.12f),
            )
        }
        val subtitle = if (baseline != null && baseline.isEstablished) {
            "Baseline ${baseline.mean.toInt()} ms"
        } else {
            "Building baseline"
        }
        return VitalCardState(
            metric = MetricKind.HRV, title = MetricKind.HRV.title,
            valueText = latest?.let { "${it.toInt()}" } ?: "--", latestValue = latest,
            unitText = if (latest != null) "ms" else null,
            statusText = interp?.displayLabel ?: "Building baseline",
            statusToken = interp?.statusColorToken ?: VitalColorToken.Neutral,
            subtitleText = subtitle,
            samples = samples,
            zones = VitalsThresholdEngine.zones(MetricKind.HRV, profile, baseline = baseline),
            yDomain = domain, referenceBands = bands,
            dashedRules = baseline?.let { listOf(it.mean) } ?: emptyList(),
            trend = TrendSummary.compute(samples, MetricKind.HRV, baseline = baseline, unitLabel = "ms"),
            sourceQuality = quality, isEstimated = false, confidenceLabel = interp?.confidenceLabel,
            lastUpdatedText = lastUpdated(samples.lastOrNull()?.timestampMs, inputs.nowMs),
            isEmpty = samples.size < 2,
        )
    }

    // ─────────────────────── Stress / Fatigue (device 0–100 scores) ───────────────────────

    private fun scoreCard(
        metric: MetricKind,
        samples: List<VitalSample>,
        inputs: Inputs,
        profile: UserPhysiologyProfile,
    ): VitalCardState {
        val latest = samples.lastOrNull()?.value
        val quality = quality(metric, samples.lastOrNull()?.timestampMs, inputs)
        val interp = latest?.let { VitalsThresholdEngine.interpret(it, metric, profile) }
        val subtitle = if (metric == MetricKind.STRESS) "Lower is calmer" else "Ring model estimate"
        return VitalCardState(
            metric = metric, title = metric.title,
            valueText = latest?.let { "${it.toInt()}" } ?: "--", latestValue = latest, unitText = null,
            statusText = interp?.displayLabel ?: "No data",
            statusToken = interp?.statusColorToken ?: VitalColorToken.Neutral,
            subtitleText = subtitle,
            samples = samples, zones = VitalsThresholdEngine.zones(metric, profile),
            yDomain = 0.0..100.0, referenceBands = emptyList(), dashedRules = emptyList(),
            trend = TrendSummary.compute(samples, metric),
            sourceQuality = quality, isEstimated = false, confidenceLabel = null,
            lastUpdatedText = lastUpdated(samples.lastOrNull()?.timestampMs, inputs.nowMs),
            isEmpty = latest == null,
        )
    }

    // ─────────────────────────── Temperature ───────────────────────────

    private fun temperature(inputs: Inputs, profile: UserPhysiologyProfile): VitalCardState {
        val units = inputs.unitSystem
        val rawSamples = inputs.temperature
        val latest = rawSamples.lastOrNull()?.value
        val quality = quality(MetricKind.TEMPERATURE, rawSamples.lastOrNull()?.timestampMs, inputs)
        // Status/zone interpretation stays in canonical °C; only the display converts.
        val interp = latest?.let { VitalsThresholdEngine.interpret(it, MetricKind.TEMPERATURE, profile) }
        // Convert samples, y-domain, and zone bounds to the display unit so the whole
        // card (line, axis, bands, trend) is consistent — °C for metric, °F for imperial.
        val dispSamples = rawSamples.map { VitalSample(it.timestampMs, tempValue(it.value, units)) }
        val domain = clampedDomain(
            dispSamples,
            fallback = tempValue(30.0, units)..tempValue(38.0, units),
            pad = tempDelta(0.5, units),
            hardLower = tempValue(20.0, units),
            hardUpper = tempValue(45.0, units),
        )
        val zones = VitalsThresholdEngine.zones(MetricKind.TEMPERATURE, profile).map { z ->
            z.copy(
                lower = z.lower?.let { tempValue(it, units) },
                upper = z.upper?.let { tempValue(it, units) },
            )
        }
        return VitalCardState(
            metric = MetricKind.TEMPERATURE, title = MetricKind.TEMPERATURE.title,
            valueText = latest?.let { "%.1f".format(tempValue(it, units)) } ?: "--",
            latestValue = latest?.let { tempValue(it, units) },
            unitText = if (latest != null) tempUnit(units) else null,
            statusText = interp?.displayLabel ?: "No data",
            statusToken = interp?.statusColorToken ?: VitalColorToken.Neutral,
            subtitleText = null,
            samples = dispSamples, zones = zones,
            yDomain = domain, referenceBands = emptyList(), dashedRules = emptyList(),
            trend = TrendSummary.compute(dispSamples, MetricKind.TEMPERATURE, unitLabel = tempUnit(units)),
            sourceQuality = quality, isEstimated = false, confidenceLabel = null,
            lastUpdatedText = lastUpdated(rawSamples.lastOrNull()?.timestampMs, inputs.nowMs),
            isEmpty = rawSamples.size < 2,
        )
    }

    // ─────────────────── Blood pressure (two series → one card) ───────────────────

    private fun bloodPressure(inputs: Inputs, profile: UserPhysiologyProfile): VitalCardState {
        val sys = inputs.systolic.lastOrNull()?.value
        val dia = inputs.diastolic.lastOrNull()?.value
        val quality = quality(MetricKind.BLOOD_PRESSURE, inputs.systolic.lastOrNull()?.timestampMs, inputs)
        val interp = if (sys != null && dia != null) {
            VitalsThresholdEngine.interpretBloodPressure(sys, dia, profile, hasCuffReference = inputs.hasBPReference)
        } else null
        val valueText = if (sys != null && dia != null) "${sys.toInt()}/${dia.toInt()}" else "--/--"
        // Chart uses the systolic series for the trend line; the gauge shows both.
        return VitalCardState(
            metric = MetricKind.BLOOD_PRESSURE, title = MetricKind.BLOOD_PRESSURE.title,
            // No latestValue: the "sys/dia" label has no single number; BP gauges read the two
            // series directly.
            valueText = valueText, unitText = if (sys != null) "mmHg" else null,
            statusText = interp?.displayLabel ?: "No reading",
            statusToken = interp?.statusColorToken ?: VitalColorToken.Neutral,
            // Systolic/diastolic values are shown under each of the two gauges on the card, so the
            // subtitle would just repeat them.
            subtitleText = null,
            samples = inputs.systolic,
            zones = VitalsThresholdEngine.zones(MetricKind.BLOOD_PRESSURE, profile),
            yDomain = 80.0..190.0, referenceBands = emptyList(), dashedRules = emptyList(),
            trend = TrendSummary.compute(inputs.systolic, MetricKind.BLOOD_PRESSURE, unitLabel = "mmHg"),
            sourceQuality = quality, isEstimated = true,
            confidenceLabel = if (inputs.hasBPReference) "Estimated" else "Estimated · calibrate with a cuff",
            lastUpdatedText = lastUpdated(inputs.systolic.lastOrNull()?.timestampMs, inputs.nowMs),
            isEmpty = sys == null || dia == null,
        )
    }

    // ─────────────────────────── Glucose ───────────────────────────

    private fun glucose(inputs: Inputs, profile: UserPhysiologyProfile): VitalCardState {
        val samples = inputs.glucose
        val latest = samples.lastOrNull()?.value
        val quality = quality(MetricKind.GLUCOSE, samples.lastOrNull()?.timestampMs, inputs)
        // Context tagging is deferred → always interpret as unknown context (conservative labels).
        val unknownContext = MetricContext(measurement = MeasurementContext.UNKNOWN)
        val interp = latest?.let {
            VitalsThresholdEngine.interpret(it, MetricKind.GLUCOSE, profile, context = unknownContext)
        }
        // Convert samples, domain, zones, and reference band to the user's glucose unit
        // (mg/dL or mmol/L) so the whole card stays consistent. Interpretation stays in mg/dL.
        val gUnit = profile.preferredGlucoseUnit
        fun gv(mgdl: Double): Double = glucoseValue(mgdl, gUnit)
        val dispSamples = samples.map { VitalSample(it.timestampMs, gv(it.value)) }
        val domain = clampedDomain(dispSamples, gv(60.0)..gv(200.0), pad = gv(15.0), hardLower = gv(40.0), hardUpper = gv(400.0))
        val zones = VitalsThresholdEngine.zones(MetricKind.GLUCOSE, profile, context = unknownContext).map { z ->
            z.copy(lower = z.lower?.let(::gv), upper = z.upper?.let(::gv))
        }
        val valueText = latest?.let {
            when (gUnit) {
                com.pulseloop.service.GlucoseUnit.MGDL -> "${roundToLong(it)}"
                com.pulseloop.service.GlucoseUnit.MMOL -> "%.1f".format(gv(it))
            }
        }
        return VitalCardState(
            metric = MetricKind.GLUCOSE, title = MetricKind.GLUCOSE.title,
            valueText = valueText ?: "--", latestValue = latest?.let(::gv),
            unitText = if (latest != null) gUnit.label else null,
            statusText = interp?.displayLabel ?: "No estimate",
            statusToken = interp?.statusColorToken ?: VitalColorToken.Neutral,
            subtitleText = "Estimated wellness metric",
            samples = dispSamples, zones = zones,
            yDomain = domain,
            referenceBands = listOf(ReferenceBand(gv(70.0), gv(140.0), VitalColorToken.Mint)),
            dashedRules = emptyList(),
            trend = TrendSummary.compute(dispSamples, MetricKind.GLUCOSE, unitLabel = gUnit.label),
            sourceQuality = quality, isEstimated = true, confidenceLabel = interp?.confidenceLabel,
            lastUpdatedText = lastUpdated(samples.lastOrNull()?.timestampMs, inputs.nowMs),
            isEmpty = samples.size < 2,
        )
    }

    // ─────────────────────────── Helpers ───────────────────────────

    /** Stale once the latest reading is older than this for a dashboard (24h) metric. */
    private const val STALE_AFTER_MS = 90 * 60 * 1000L

    /**
     * The quality treatment for a metric given its newest sample (iOS SourceQualityResolver).
     * - Glucose is always estimated (the ring has no real glucose sensor); needs-calibration if no
     *   reference has been entered.
     * - Blood pressure is needs-calibration until a cuff reference exists, otherwise estimated.
     * - Everything else is stale past the freshness window, else good (or unknown with no data).
     */
    private fun quality(metric: MetricKind, latestMs: Long?, inputs: Inputs): SourceQuality = when (metric) {
        MetricKind.GLUCOSE ->
            if (inputs.isGlucoseCalibrated) SourceQuality.ESTIMATED else SourceQuality.NEEDS_CALIBRATION
        MetricKind.BLOOD_PRESSURE ->
            if (inputs.hasBPReference) SourceQuality.ESTIMATED else SourceQuality.NEEDS_CALIBRATION
        else -> when {
            latestMs == null -> SourceQuality.UNKNOWN
            inputs.nowMs - latestMs > STALE_AFTER_MS -> SourceQuality.STALE
            else -> SourceQuality.GOOD
        }
    }

    /** "62-98" range label for HR (iOS TodayInsights.hrRangeLabel). */
    private fun rangeLabel(samples: List<VitalSample>, fallback: Double?): String {
        val values = samples.map { it.value }.filter { it > 0 }
        if (values.isEmpty()) return fallback?.let { "${roundToLong(it)}" } ?: "—"
        val lo = roundToLong(values.min())
        val hi = roundToLong(values.max())
        return if (lo == hi) "$lo" else "$lo-$hi"
    }

    /** Window-average label for SpO₂ (iOS TodayInsights.averageLabel). */
    private fun averageLabel(samples: List<VitalSample>, fallback: Double?): String {
        val values = samples.map { it.value }.filter { it > 0 }
        if (values.isEmpty()) return fallback?.let { "${roundToLong(it)}" } ?: "—"
        return "${roundToLong(values.average())}"
    }

    /** A y-domain padded around the data and clamped to sane hard limits, falling back when empty. */
    private fun clampedDomain(
        samples: List<VitalSample>,
        fallback: ClosedFloatingPointRange<Double>,
        pad: Double,
        hardLower: Double,
        hardUpper: Double,
    ): ClosedFloatingPointRange<Double> {
        val values = samples.map { it.value }.filter { it > 0 }
        val lo = values.minOrNull() ?: return fallback
        val hi = values.max()
        val lower = max(hardLower, min(fallback.start, lo - pad))
        val upper = min(hardUpper, max(fallback.endInclusive, hi + pad))
        return if (lower < upper) lower..upper else fallback
    }

    /** Reference bands derived from the engine's "normal" zone, for charts that want a soft band. */
    private fun normalBand(
        metric: MetricKind,
        profile: UserPhysiologyProfile,
        domain: ClosedFloatingPointRange<Double>,
    ): List<ReferenceBand> {
        val zones = VitalsThresholdEngine.zones(metric, profile)
        val normal = zones.firstOrNull { it.severity == com.pulseloop.service.ZoneSeverity.NORMAL } ?: return emptyList()
        val lower = normal.lower ?: domain.start
        val upper = normal.upper ?: domain.endInclusive
        // Band reads from the normal zone's own token so it matches the line/legend exactly.
        return listOf(ReferenceBand(lower, upper, normal.colorToken))
    }

    private fun lastUpdated(dateMs: Long?, nowMs: Long): String? {
        if (dateMs == null) return null
        val minutes = ((nowMs - dateMs) / 60_000L).toInt()
        if (minutes < 1) return "Updated just now"
        if (minutes < 60) return "Updated ${minutes}m ago"
        val hours = minutes / 60
        if (hours < 24) return "Updated ${hours}h ago"
        return "Updated ${hours / 24}d ago"
    }

    // Unit conversions (iOS UnitsFormatter). °C is canonical for temperature; mg/dL for glucose.

    private fun tempValue(celsius: Double, units: UnitSystem): Double =
        if (units == UnitSystem.IMPERIAL) celsius * 9 / 5 + 32 else celsius

    /** Convert a temperature *delta* (e.g. chart padding) — scale only, no +32 offset. */
    private fun tempDelta(celsius: Double, units: UnitSystem): Double =
        if (units == UnitSystem.IMPERIAL) celsius * 9 / 5 else celsius

    private fun tempUnit(units: UnitSystem): String =
        if (units == UnitSystem.IMPERIAL) "°F" else "°C"

    /** mmol/L = mg/dL ÷ 18.0182 (purely multiplicative, so deltas scale the same way). */
    private const val MGDL_PER_MMOL = 18.0182

    private fun glucoseValue(mgdl: Double, unit: com.pulseloop.service.GlucoseUnit): Double =
        if (unit == com.pulseloop.service.GlucoseUnit.MMOL) mgdl / MGDL_PER_MMOL else mgdl

    /** Swift-style rounding (ties away from zero) for display integers. */
    private fun roundToLong(x: Double): Long =
        (if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)).toLong()
}

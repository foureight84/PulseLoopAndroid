package com.pulseloop.service

import com.pulseloop.ring.MeasurementKind
import kotlin.math.sqrt

// Value types backing the centralized vitals reference-range / threshold engine.
// Ported from Services/VitalsZoneModel.swift.
//
// These mirror the medical reference ranges encoded in [VitalsThresholdEngine]. Nothing here is a
// diagnosis — labels are deliberately conservative ("Typical", "Elevated", "Talk to a clinician if
// persistent") and ring-estimated metrics (BP, glucose, fatigue) are always flagged `isEstimated`.
//
// Design rule: colors resolve in exactly one place (`VitalColorToken.toColor()` in
// ui/components/ZonePalette.kt). This file stays UI-free so the engine is unit-testable — zones
// carry a *token*, not a Compose Color.

// ─────────────────────────── Metric identity ───────────────────────────

/**
 * The physiological metrics the threshold engine interprets. Distinct from [MeasurementKind] (the
 * storage key) because blood pressure is a single *card* driven by two stored series
 * (systolic + diastolic). Ported from `MetricKind` in VitalsZoneModel.swift.
 */
enum class MetricKind {
    HEART_RATE,
    SPO2,
    HRV,
    BLOOD_PRESSURE,
    STRESS,
    FATIGUE,
    GLUCOSE,
    TEMPERATURE;

    /** The storage kind this card reads. BP reads systolic for visibility gating; the card itself
     *  pulls both systolic and diastolic series. */
    val measurementKind: MeasurementKind
        get() = when (this) {
            HEART_RATE -> MeasurementKind.HEART_RATE
            SPO2 -> MeasurementKind.SPO2
            HRV -> MeasurementKind.HRV
            BLOOD_PRESSURE -> MeasurementKind.BLOOD_PRESSURE_SYSTOLIC
            STRESS -> MeasurementKind.STRESS
            FATIGUE -> MeasurementKind.FATIGUE
            GLUCOSE -> MeasurementKind.BLOOD_SUGAR
            TEMPERATURE -> MeasurementKind.TEMPERATURE
        }

    val title: String
        get() = when (this) {
            HEART_RATE -> "Heart rate"
            SPO2 -> "Blood oxygen"
            HRV -> "HRV"
            BLOOD_PRESSURE -> "Blood pressure"
            STRESS -> "Stress"
            FATIGUE -> "Fatigue"
            GLUCOSE -> "Blood sugar"
            TEMPERATURE -> "Skin temperature"
        }

    /** Display unit shown after the big value. Empty for unit-less device scores. */
    val unit: String
        get() = when (this) {
            HEART_RATE -> "bpm"
            SPO2 -> "%"
            HRV -> "ms"
            BLOOD_PRESSURE -> "mmHg"
            STRESS, FATIGUE -> ""
            GLUCOSE -> "mg/dL"
            TEMPERATURE -> "°C"
        }

    val accentToken: VitalColorToken get() = VitalColorToken.MetricAccent(this)
}

// ─────────────────────────── Severity ───────────────────────────

/**
 * Ordered worst-to-best for "worse of" comparisons (e.g. blood pressure category = worse of
 * systolic and diastolic). [UNKNOWN] sorts last and is handled separately so it never wins a max.
 */
enum class ZoneSeverity(val rank: Int) {
    OPTIMAL(0),
    NORMAL(1),
    WATCH(2),
    HIGH(3),
    CRITICAL(4),
    UNKNOWN(5);

    companion object {
        /** The worse (more severe) of two severities, treating [UNKNOWN] as "no information" so a
         *  real category always wins over it. */
        fun worst(a: ZoneSeverity, b: ZoneSeverity): ZoneSeverity {
            if (a == UNKNOWN) return b
            if (b == UNKNOWN) return a
            return if (a.rank >= b.rank) a else b
        }
    }

    /**
     * A neutral fallback token. Color is NOT derived from severity — each [MetricZone] carries its
     * own explicit `colorToken` (the per-metric palette). Severity only orders zones and drives the
     * BP "worse-of" rule.
     */
    val fallbackColorToken: VitalColorToken
        get() = when (this) {
            OPTIMAL -> VitalColorToken.Mint
            NORMAL -> VitalColorToken.Cyan
            WATCH -> VitalColorToken.Amber
            HIGH -> VitalColorToken.Orange
            CRITICAL -> VitalColorToken.Red
            UNKNOWN -> VitalColorToken.Neutral
        }
}

// ─────────────────────────── Color tokens ───────────────────────────

/**
 * Explicit vitals color token. Resolved to a Compose Color in exactly one place
 * (`VitalColorToken.toColor()` in ZonePalette.kt) — never duplicate hex elsewhere. Each
 * [MetricZone] picks its token from this palette so the chart line, reference band, gauge arc,
 * stat dot, and status label are always the same color for the same zone. [MetricAccent] resolves
 * to the metric's brand color (heart pink, HRV purple…) — used where a metric's "normal" zone
 * should read as its own accent.
 */
sealed class VitalColorToken {
    object Blue : VitalColorToken()        // low / cool
    object Mint : VitalColorToken()        // optimal / typical
    object Cyan : VitalColorToken()        // normal (where the accent isn't the normal color)
    object Amber : VitalColorToken()       // caution
    object SoftAmber : VitalColorToken()   // slight caution
    object Orange : VitalColorToken()      // elevated / low-oxygen / stage 1
    object Red : VitalColorToken()         // high / critical
    object BrightRed : VitalColorToken()   // a deeper/brighter red where the plain accent is already reddish (HR high)
    object Neutral : VitalColorToken()     // no information (building baseline)
    data class MetricAccent(val metric: MetricKind) : VitalColorToken()
}

// ─────────────────────────── Zones ───────────────────────────

/**
 * One reference band of a metric (e.g. "Normal 60–100 bpm"). [lower]/[upper] are null for
 * open-ended ends. [contains] uses a half-open interval `[lower, upper)` so adjacent zones don't
 * both claim a boundary value.
 */
data class MetricZone(
    val id: String,
    val label: String,
    val lower: Double?,
    val upper: Double?,
    val severity: ZoneSeverity,
    val colorToken: VitalColorToken,
    val explanation: String,
) {
    fun contains(value: Double): Boolean {
        val aboveLower = lower?.let { value >= it } ?: true
        val belowUpper = upper?.let { value < it } ?: true
        return aboveLower && belowUpper
    }
}

// ─────────────────────────── User physiology inputs ───────────────────────────

enum class BiologicalSex {
    FEMALE,
    MALE,
    UNSPECIFIED;

    companion object {
        fun fromProfileSex(profileSex: String?): BiologicalSex = when (profileSex?.lowercase()) {
            "female" -> FEMALE
            "male" -> MALE
            else -> UNSPECIFIED
        }
    }
}

enum class GlucoseUnit(val label: String) {
    MGDL("mg/dL"),
    MMOL("mmol/L");

    /** Convert a canonical mg/dL value into this display unit (mmol/L = mg/dL ÷ 18.0182). */
    fun fromMgdl(mgdl: Double): Double = if (this == MMOL) mgdl / MGDL_PER_MMOL else mgdl

    private companion object {
        const val MGDL_PER_MMOL = 18.0182
    }
}

/**
 * The physiology inputs that shift reference ranges. Built from the stored user profile
 * ([com.pulseloop.data.entity.UserProfileEntity] carries `age`/`sex`; the remaining flags default
 * until profile settings grow them). Every field is optional/defaulted so the engine degrades
 * gracefully when the profile is incomplete — pass plain values, never Room reads.
 */
data class UserPhysiologyProfile(
    val age: Int? = null,
    val sex: BiologicalSex = BiologicalSex.UNSPECIFIED,
    val athleteMode: Boolean = false,
    val altitudeMeters: Double? = null,
    val usesBetaBlockers: Boolean = false,
    val hasKnownLungCondition: Boolean = false,
    val preferredGlucoseUnit: GlucoseUnit = GlucoseUnit.MGDL,
) {
    /** Age-predicted maximum heart rate (`220 − age`), used for effort-zone overlays. Falls back to
     *  190 when age is unknown. */
    val maxHeartRate: Double
        get() {
            val a = age ?: return 190.0
            if (a <= 0) return 190.0
            return (220 - a).toDouble()
        }

    companion object {
        /** A neutral default used when no profile exists yet (onboarding not done). */
        val UNKNOWN = UserPhysiologyProfile()

        /** Build from the stored profile's plain fields (age + sex string) plus the optional
         *  physiology refinements from Settings (iOS #35). All extras default to "no adjustment"
         *  so existing callers are unaffected — pass plain values, never Room reads. */
        fun fromProfile(
            age: Int?,
            sex: String?,
            athleteMode: Boolean = false,
            altitudeMeters: Double? = null,
            usesBetaBlockers: Boolean = false,
            hasKnownLungCondition: Boolean = false,
            preferredGlucoseUnit: GlucoseUnit = GlucoseUnit.MGDL,
        ): UserPhysiologyProfile =
            UserPhysiologyProfile(
                age = age,
                sex = BiologicalSex.fromProfileSex(sex),
                athleteMode = athleteMode,
                altitudeMeters = altitudeMeters,
                usesBetaBlockers = usesBetaBlockers,
                hasKnownLungCondition = hasKnownLungCondition,
                preferredGlucoseUnit = preferredGlucoseUnit,
            )
    }
}

// ─────────────────────── Measurement context & source quality ───────────────────────

/**
 * The circumstance a reading was taken under. Glucose interpretation needs this to choose fasting
 * vs post-meal thresholds; today it is almost always [UNKNOWN] (per-reading tagging is deferred),
 * which forces the engine onto conservative, non-diagnostic labels.
 */
enum class MeasurementContext {
    RESTING,
    SLEEPING,
    ACTIVE,
    FASTING,
    POST_MEAL,
    RANDOM,
    UNKNOWN,
}

/**
 * How much to trust a reading. Derived from timestamps + calibration state at display time — never
 * persisted. Drives chip styling and line opacity, not the medical category.
 */
enum class SourceQuality {
    GOOD,
    MOTION_ARTIFACT,
    LOOSE_FIT,
    STALE,
    ESTIMATED,
    NEEDS_CALIBRATION,
    UNKNOWN;

    /** Whether to surface an "Estimated" treatment (chip / disclaimer). */
    val isEstimated: Boolean get() = this == ESTIMATED || this == NEEDS_CALIBRATION
}

data class MetricContext(
    val measurement: MeasurementContext = MeasurementContext.UNKNOWN,
    val sourceQuality: SourceQuality = SourceQuality.GOOD,
)

// ─────────────────────────── Samples ───────────────────────────

/** A timestamped metric reading — the Android analog of iOS `MetricSample`. */
data class VitalSample(
    val timestampMs: Long,
    val value: Double,
)

// ─────────────────────────── Baseline statistics ───────────────────────────

/**
 * Rolling baseline computed from already-fetched samples. HRV interpretation is baseline-driven
 * (deviation from the user's own typical range), not absolute. [isEstablished] gates the
 * "Building baseline" state — under the dashboard's 24h fetch this is usually false; the detail
 * screen (30-day fetch) produces a real baseline.
 */
data class BaselineStats(
    val mean: Double,
    val median: Double,
    val standardDeviation: Double,
    val p25: Double,
    val p75: Double,
    val sampleCount: Int,
    val spanDays: Double,
) {
    /** A baseline is meaningful once it spans roughly a week of wear with enough samples. */
    val isEstablished: Boolean get() = spanDays >= 7 && sampleCount >= 20

    companion object {
        fun compute(samples: List<VitalSample>): BaselineStats? {
            val values = samples.map { it.value }.filter { it > 0 }
            if (values.size < 2) return null
            val sorted = values.sorted()
            val count = values.size.toDouble()
            val mean = values.sum() / count
            val variance = values.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / count
            val sd = sqrt(variance)
            val first = samples.minOfOrNull { it.timestampMs }
            val last = samples.maxOfOrNull { it.timestampMs }
            val spanDays = if (first != null && last != null) (last - first) / 86_400_000.0 else 0.0
            return BaselineStats(
                mean = mean,
                median = percentile(sorted, 0.50),
                standardDeviation = sd,
                p25 = percentile(sorted, 0.25),
                p75 = percentile(sorted, 0.75),
                sampleCount = values.size,
                spanDays = spanDays,
            )
        }

        private fun percentile(sorted: List<Double>, fraction: Double): Double {
            if (sorted.isEmpty()) return 0.0
            if (sorted.size == 1) return sorted[0]
            val rank = fraction * (sorted.size - 1)
            val lower = kotlin.math.floor(rank).toInt()
            val upper = kotlin.math.ceil(rank).toInt()
            val weight = rank - lower
            return sorted[lower] * (1 - weight) + sorted[upper] * weight
        }
    }
}

// ─────────────────────────── Interpretation result ───────────────────────────

/**
 * The engine's verdict for a value: which zone it lands in, the full zone set (for legends/bands),
 * a user-facing label/explanation, and an optional confidence caveat.
 */
data class MetricInterpretation(
    val primaryZone: MetricZone,
    val allZones: List<MetricZone>,
    val displayLabel: String,
    val explanation: String,
    val confidenceLabel: String?,
    val isEstimated: Boolean,
) {
    val statusColorToken: VitalColorToken get() = primaryZone.colorToken
}

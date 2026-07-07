package com.pulseloop.widgets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pulseloop.service.MetricKind
import com.pulseloop.service.MetricZone
import com.pulseloop.service.VitalColorToken
import com.pulseloop.service.VitalSample
import com.pulseloop.service.ZoneSeverity
import com.pulseloop.ui.components.toColor
import com.pulseloop.util.TimeUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId

// The app ↔ home-screen-widget data contract, ported from PulseLoop/Shared/WidgetSnapshot.swift
// (iOS #44). The app projects the already-interpreted Today tile state (VitalsCardFactory cards +
// activity/sleep tile derivations) into these plain serializable payloads, writes one small JSON
// file into the app's files dir, and updates the Glance widgets. The widget decodes in milliseconds
// and renders with the same drawing math the app's tiles use — no Room, no threshold engine.
//
// Colors cross the boundary two ways, both lossless (like iOS):
// - Zone/band colors as [VitalColorToken] strings (round-trip via the token bridge below, so a
//   reconstructed [MetricZone] renders exactly like the in-app one).
// - Already-resolved colors (status color, chart line interval colors, sleep stage colors) as hex.
//
// Everything in this file must stay bitmap-free / android.graphics-free so the payload + logic
// classes run on the plain JVM for unit tests. Compose's `Color` is a pure value class, so the
// token↔hex bridge is JVM-safe. Dates are epoch SECONDS (iOS `secondsSince1970` parity).

// ─────────────────────────── Snapshot root ───────────────────────────

@Serializable
data class WidgetSnapshot(
    /** When the app built this snapshot (epoch seconds) — drives the "as of" staleness header. */
    val generatedAt: Long,
    /** Local start-of-day (epoch seconds) the activity/sleep payloads belong to. Widgets rendered
     *  after midnight compare against this so yesterday's steps are never presented as today's. */
    val dayStart: Long,
    val activity: WidgetActivityPayload? = null,
    val sleep: WidgetSleepPayload? = null,
    /** Keyed by the iOS `MetricKind.rawValue` strings ([MetricKind.widgetKey]). */
    val metrics: Map<String, WidgetMetricPayload> = emptyMap(),
) {
    /** Header timestamp: the snapshot's generatedAt (epoch seconds), shown only once the snapshot
     *  is older than 45 minutes at render time; null while fresh (mirrors iOS `stalenessDate`). */
    fun stalenessEpochSeconds(nowMs: Long): Long? =
        if (nowMs - generatedAt * 1000L > STALE_AFTER_MS) generatedAt else null

    /** True once the render date's local day has moved past the snapshot's — activity/sleep then
     *  show the "new day, nothing synced yet" state instead of yesterday's numbers. */
    fun isRolledOver(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        TimeUtil.startOfDayLocal(nowMs, zone) > dayStart * 1000L

    companion object {
        const val STALE_AFTER_MS = 45 * 60_000L
    }
}

// ─────────────────────────── Activity (concentric rings tile) ───────────────────────────

@Serializable
data class WidgetActivityPayload(
    /** Ring inputs (null value = metric unavailable → track only), matching the Today activity tile. */
    val steps: Double? = null,
    val stepsGoal: Double = 1.0,
    /** Distance already converted to the user's display unit (km or mi), like the in-app tile. */
    val distanceDisplay: Double? = null,
    val distanceGoalDisplay: Double = 1.0,
    /** "KM" or "MI". */
    val distanceUnitLabel: String = "KM",
    val calories: Double? = null,
    val caloriesGoal: Double = 1.0,
    /** Preformatted label-stack texts (steps grouping, 1-decimal distance, whole calories —
     *  identical to the Android Today tile's formatting). Missing metrics are null and skipped. */
    val stepsText: String? = null,
    val distanceText: String? = null,
    val caloriesText: String? = null,
)

// ─────────────────────────── Sleep (duration + stage bar + score tile) ───────────────────────────

@Serializable
data class WidgetSleepPayload(
    val durationText: String,
    val score: Int,
    val segments: List<Segment> = emptyList(),
) {
    @Serializable
    data class Segment(
        val minutes: Double,
        val colorHex: String,
        val label: String,
    )
}

// ─────────────────────────── Vitals metric (chart / gauge / BP tiles) ───────────────────────────

@Serializable
data class WidgetSamplePayload(
    /** Epoch seconds. */
    val t: Long,
    val v: Double,
)

@Serializable
data class WidgetZonePayload(
    val id: String,
    val label: String,
    val lower: Double? = null,
    val upper: Double? = null,
    val severityRaw: Int,
    val colorToken: String,
) {
    /** Rebuild the real render input. `explanation` is detail-screen-only, so it doesn't cross over. */
    fun toMetricZone(): MetricZone = MetricZone(
        id = id, label = label, lower = lower, upper = upper,
        severity = ZoneSeverity.entries.firstOrNull { it.rank == severityRaw } ?: ZoneSeverity.UNKNOWN,
        colorToken = vitalColorTokenFrom(colorToken),
        explanation = "",
    )

    companion object {
        fun from(zone: MetricZone): WidgetZonePayload = WidgetZonePayload(
            id = zone.id, label = zone.label, lower = zone.lower, upper = zone.upper,
            severityRaw = zone.severity.rank, colorToken = zone.colorToken.tokenString,
        )
    }
}

@Serializable
data class WidgetBandPayload(
    val lower: Double,
    val upper: Double,
    val colorToken: String,
    val opacity: Double = 0.08,
)

/**
 * A serializable projection of [com.pulseloop.ui.viewmodels.VitalCardState] — everything a Today
 * chart/gauge/BP tile needs, with all interpretation (thresholds, baselines, calibration) already
 * baked in by the app.
 */
@Serializable
data class WidgetMetricPayload(
    /** iOS `MetricKind.rawValue` ([MetricKind.widgetKey]); maps back for accents/titles. */
    val kind: String,
    val title: String,
    val valueText: String,
    /** The raw reading behind [valueText] in display units (gauge needle position) — never parse
     *  the locale-formatted [valueText] back. Defaulted so pre-field snapshots still decode. */
    val latestValue: Double? = null,
    val unitText: String? = null,
    val statusText: String,
    /** The resolved status color (gauge value arc / status label), as hex. */
    val statusColorHex: String,
    val isEmpty: Boolean = false,

    // Chart tile inputs (downsampled ≤ 48 points).
    val samples: List<WidgetSamplePayload> = emptyList(),
    val yLower: Double = 0.0,
    val yUpper: Double = 1.0,
    val referenceBands: List<WidgetBandPayload> = emptyList(),
    val dashedRules: List<Double> = emptyList(),
    /** Sorted interior zone boundaries where the line color changes… */
    val thresholds: List<Double> = emptyList(),
    /** …and the resolved line color for each of the `thresholds.size + 1` intervals (hex). Encodes
     *  the threshold engine's value→color mapping (including HRV's baseline-relative case) as a
     *  step function, so the widget reproduces the Today chart coloring without the engine. */
    val intervalColorHexes: List<String> = emptyList(),

    // Gauge tile inputs.
    val zones: List<WidgetZonePayload> = emptyList(),

    // Blood-pressure extras (dual gauge).
    val systolic: Double? = null,
    val diastolic: Double? = null,
    val systolicZones: List<WidgetZonePayload> = emptyList(),
    val diastolicZones: List<WidgetZonePayload> = emptyList(),
) {
    /**
     * The step-function lookup for chart line coloring. Falls back to the status color when the
     * interval list is missing/mismatched (defensive against old snapshots). Zones are half-open
     * `[lo, hi)`, so a boundary value belongs to the interval above it.
     */
    fun lineColorHex(value: Double): String {
        if (intervalColorHexes.size != thresholds.size + 1) return statusColorHex
        val index = thresholds.indexOfFirst { value < it }.let { if (it == -1) thresholds.size else it }
        return intervalColorHexes[index]
    }
}

// ─────────────────────────── Token string bridge ───────────────────────────

private const val ACCENT_PREFIX = "accent:"

/** iOS `VitalColorToken.tokenString` parity — stable strings persisted in snapshots. */
val VitalColorToken.tokenString: String
    get() = when (this) {
        VitalColorToken.Blue -> "blue"
        VitalColorToken.Mint -> "mint"
        VitalColorToken.Cyan -> "cyan"
        VitalColorToken.Amber -> "amber"
        VitalColorToken.SoftAmber -> "softAmber"
        VitalColorToken.Orange -> "orange"
        VitalColorToken.Red -> "red"
        VitalColorToken.BrightRed -> "brightRed"
        VitalColorToken.Neutral -> "neutral"
        is VitalColorToken.MetricAccent -> ACCENT_PREFIX + metric.widgetKey
    }

/** Unknown strings degrade to [VitalColorToken.Neutral] instead of crashing on old snapshots. */
fun vitalColorTokenFrom(tokenString: String): VitalColorToken = when (tokenString) {
    "blue" -> VitalColorToken.Blue
    "mint" -> VitalColorToken.Mint
    "cyan" -> VitalColorToken.Cyan
    "amber" -> VitalColorToken.Amber
    "softAmber" -> VitalColorToken.SoftAmber
    "orange" -> VitalColorToken.Orange
    "red" -> VitalColorToken.Red
    "brightRed" -> VitalColorToken.BrightRed
    "neutral" -> VitalColorToken.Neutral
    else -> {
        if (tokenString.startsWith(ACCENT_PREFIX)) {
            metricKindFromWidgetKey(tokenString.removePrefix(ACCENT_PREFIX))
                ?.let { VitalColorToken.MetricAccent(it) } ?: VitalColorToken.Neutral
        } else {
            VitalColorToken.Neutral
        }
    }
}

/** The iOS `MetricKind.rawValue` string — the cross-platform snapshot key. Don't rename. */
val MetricKind.widgetKey: String
    get() = when (this) {
        MetricKind.HEART_RATE -> "heartRate"
        MetricKind.SPO2 -> "spo2"
        MetricKind.HRV -> "hrv"
        MetricKind.BLOOD_PRESSURE -> "bloodPressure"
        MetricKind.STRESS -> "stress"
        MetricKind.FATIGUE -> "fatigue"
        MetricKind.GLUCOSE -> "glucose"
        MetricKind.TEMPERATURE -> "temperature"
    }

fun metricKindFromWidgetKey(key: String): MetricKind? =
    MetricKind.entries.firstOrNull { it.widgetKey == key }

// ─────────────────────────── Color ↔ hex ───────────────────────────

/** "#RRGGBB", or "#RRGGBBAA" when translucent (iOS `hexString` parity). */
fun colorToHex(color: Color): String {
    val argb = color.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val a = (argb shr 24) and 0xFF
    return if (a < 255) {
        String.format("#%02X%02X%02X%02X", r, g, b, a)
    } else {
        String.format("#%02X%02X%02X", r, g, b)
    }
}

/** Parses "#RRGGBB" / "#RRGGBBAA" (the formats [colorToHex] emits). Malformed → white. */
fun colorFromHex(hex: String): Color {
    val s = hex.removePrefix("#")
    return try {
        when (s.length) {
            6 -> Color(
                red = s.substring(0, 2).toInt(16) / 255f,
                green = s.substring(2, 4).toInt(16) / 255f,
                blue = s.substring(4, 6).toInt(16) / 255f,
            )
            8 -> Color(
                red = s.substring(0, 2).toInt(16) / 255f,
                green = s.substring(2, 4).toInt(16) / 255f,
                blue = s.substring(4, 6).toInt(16) / 255f,
                alpha = s.substring(6, 8).toInt(16) / 255f,
            )
            else -> Color.White
        }
    } catch (_: Exception) {
        Color.White
    }
}

// ─────────────────────────── Line-color step baking ───────────────────────────

/**
 * Bakes the zone engine's value→color mapping into a step function (sorted interior thresholds +
 * one resolved color per interval), mirroring the iOS publisher's `metricPayload`. Works off the
 * card's already-resolved zone list (which is exactly `VitalsThresholdEngine.zones(...)`, including
 * the HRV baseline-relative case and any display-unit conversion the card applied) so the widget's
 * coloring is identical to the Today chart without re-running the engine.
 */
object WidgetColorSteps {

    /** The sorted interior zone boundaries — same set as `VitalsThresholdEngine.zoneThresholds`. */
    fun thresholds(zones: List<MetricZone>): List<Double> =
        zones.mapNotNull { it.upper }.distinct().sorted()

    /**
     * One resolved hex per interval between (and beyond) [thresholds], evaluated at an interior
     * representative point (the engine's color is constant within an interval). [fallbackValue] is
     * used when there are no thresholds at all (single unbounded zone).
     */
    fun intervalColorHexes(
        zones: List<MetricZone>,
        thresholds: List<Double>,
        fallback: Color,
        fallbackValue: Double,
    ): List<String> {
        val hexes = mutableListOf<String>()
        for (index in 0..thresholds.size) {
            val representative = when {
                thresholds.isEmpty() -> fallbackValue
                index == 0 -> thresholds[0] - 1
                index == thresholds.size -> thresholds[index - 1] + 1
                else -> (thresholds[index - 1] + thresholds[index]) / 2
            }
            hexes.add(colorToHex(zoneColor(representative, zones, fallback)))
        }
        return hexes
    }

    /** The zone color at [value], clamping outside values to the nearest end zone (engine parity). */
    fun zoneColor(value: Double, zones: List<MetricZone>, fallback: Color): Color {
        zones.firstOrNull { it.contains(value) }?.let { return it.colorToken.toColor() }
        val first = zones.firstOrNull() ?: return fallback
        val lower = first.lower
        if (lower != null && value < lower) return first.colorToken.toColor()
        return zones.lastOrNull()?.colorToken?.toColor() ?: fallback
    }
}

// ─────────────────────────── Downsampling ───────────────────────────

/** Port of the iOS `MetricDownsampler.bucketAverage`: time-bucketed mean, keeping ≤ target points. */
object MetricDownsampler {
    fun bucketAverage(samples: List<VitalSample>, targetBuckets: Int): List<VitalSample> {
        if (targetBuckets <= 0 || samples.size <= targetBuckets) return samples
        val minTs = samples.minOf { it.timestampMs }
        val maxTs = samples.maxOf { it.timestampMs }
        val span = (maxTs - minTs).coerceAtLeast(1)
        val buckets = Array(targetBuckets) { mutableListOf<VitalSample>() }
        for (s in samples) {
            val index = (((s.timestampMs - minTs).toDouble() / span) * targetBuckets).toInt()
                .coerceIn(0, targetBuckets - 1)
            buckets[index].add(s)
        }
        return buckets.filter { it.isNotEmpty() }.map { bucket ->
            VitalSample(
                timestampMs = bucket.map { it.timestampMs }.average().toLong(),
                value = bucket.map { it.value }.average(),
            )
        }
    }
}

// ─────────────────────────── JSON codec ───────────────────────────

/** One shared, tolerant JSON configuration for the snapshot file (JVM-safe for tests). */
object WidgetSnapshotCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: WidgetSnapshot): String = json.encodeToString(WidgetSnapshot.serializer(), snapshot)

    fun decode(text: String): WidgetSnapshot? = try {
        json.decodeFromString(WidgetSnapshot.serializer(), text)
    } catch (_: Exception) {
        null
    }
}

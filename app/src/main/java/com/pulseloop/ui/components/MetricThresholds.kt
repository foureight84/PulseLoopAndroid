package com.pulseloop.ui.components

import androidx.compose.ui.graphics.Color
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ui.theme.MetricColors

/**
 * One zone segment on the threshold bar.
 */
data class ThresholdZone(
    val label: String,        // "Normal", "Elevated", …
    val start: Double,        // inclusive lower bound on the display scale
    val end: Double,          // exclusive upper bound
    val color: Color,
)

/**
 * Threshold table for a single metric — defines display range, zones, and direction.
 */
data class MetricThresholds(
    val displayMin: Double,
    val displayMax: Double,
    val zones: List<ThresholdZone>,
    val higherIsBetter: Boolean? = null,   // null = neutral / personal / relative
    val unitLabel: String = "",
) {
    fun zoneFor(value: Double): ThresholdZone? =
        zones.firstOrNull { value >= it.start && value < it.end } ?: zones.lastOrNull()
}

/**
 * Lookup table that maps every [MeasurementKind] to its [MetricThresholds].
 * All ranges are **general wellness reference ranges, not diagnostic thresholds**.
 */
object MetricThresholdTable {

    fun forKind(kind: MeasurementKind): MetricThresholds? = when (kind) {
        MeasurementKind.STEPS -> null
        MeasurementKind.HEART_RATE -> heartRate
        MeasurementKind.SPO2 -> spo2
        MeasurementKind.STRESS -> stress
        MeasurementKind.FATIGUE -> fatigue
        MeasurementKind.HRV -> hrv
        MeasurementKind.TEMPERATURE -> skinTemp
        MeasurementKind.BLOOD_PRESSURE_SYSTOLIC -> bloodPressure
        MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> bloodPressure
        MeasurementKind.BLOOD_SUGAR -> bloodSugar
    }

    /** Returns a synthetic thresholds entry for the combined "bp" detail screen. */
    fun forKey(key: String): MetricThresholds? = when (key) {
        "hr"       -> heartRate
        "spo2"     -> spo2
        "stress"   -> stress
        "fatigue"  -> fatigue
        "hrv"      -> hrv
        "temp"     -> skinTemp
        "bp"       -> bloodPressure
        "glucose"  -> bloodSugar
        else       -> null
    }

    // ── Zone tables ─────────────────────────────────────────────────────

    private val heartRate = MetricThresholds(
        displayMin = 40.0, displayMax = 140.0,
        higherIsBetter = false,
        unitLabel = "bpm",
        zones = listOf(
            ThresholdZone("Low",      40.0,  50.0, MetricColors.ZoneLow),
            ThresholdZone("Good",     50.0,  90.0, MetricColors.ZoneGood),
            ThresholdZone("Elevated", 90.0, 120.0, MetricColors.ZoneBorderline),
            ThresholdZone("High",    120.0, 140.0, MetricColors.ZoneConcern),
        ),
    )

    private val spo2 = MetricThresholds(
        displayMin = 85.0, displayMax = 100.0,
        higherIsBetter = true,
        unitLabel = "%",
        zones = listOf(
            ThresholdZone("Concerning", 85.0, 90.0, MetricColors.ZoneConcern),
            ThresholdZone("Low",        90.0, 95.0, MetricColors.ZoneBorderline),
            ThresholdZone("Good",       95.0, 100.0, MetricColors.ZoneGood),
        ),
    )

    private val stress = MetricThresholds(
        displayMin = 0.0, displayMax = 100.0,
        higherIsBetter = false,
        unitLabel = "",
        zones = listOf(
            ThresholdZone("Relaxed",   0.0, 30.0, MetricColors.ZoneGood),
            ThresholdZone("Normal",   30.0, 60.0, MetricColors.ZoneNormal),
            ThresholdZone("Moderate", 60.0, 80.0, MetricColors.ZoneBorderline),
            ThresholdZone("High",     80.0, 100.0, MetricColors.ZoneConcern),
        ),
    )

    private val fatigue = MetricThresholds(
        displayMin = 0.0, displayMax = 100.0,
        higherIsBetter = false,
        unitLabel = "",
        zones = listOf(
            ThresholdZone("Relaxed",   0.0, 30.0, MetricColors.ZoneGood),
            ThresholdZone("Normal",   30.0, 60.0, MetricColors.ZoneNormal),
            ThresholdZone("Moderate", 60.0, 80.0, MetricColors.ZoneBorderline),
            ThresholdZone("High",     80.0, 100.0, MetricColors.ZoneConcern),
        ),
    )

    private val hrv = MetricThresholds(
        displayMin = 0.0, displayMax = 120.0,
        higherIsBetter = true,
        unitLabel = "ms",
        zones = listOf(
            ThresholdZone("Low",         0.0, 20.0, MetricColors.ZoneConcern),
            ThresholdZone("Below avg",  20.0, 50.0, MetricColors.ZoneBorderline),
            ThresholdZone("Good",       50.0, 120.0, MetricColors.ZoneGood),
        ),
    )

    private val skinTemp = MetricThresholds(
        displayMin = 28.0, displayMax = 38.0,
        higherIsBetter = null,  // personal / relative
        unitLabel = "°C",
        zones = listOf(
            ThresholdZone("Low",      28.0, 31.0, MetricColors.ZoneLow),
            ThresholdZone("Normal",   31.0, 36.0, MetricColors.ZoneGood),
            ThresholdZone("Elevated", 36.0, 38.0, MetricColors.ZoneBorderline),
        ),
    )

    private val bloodPressure = MetricThresholds(
        displayMin = 80.0, displayMax = 180.0,
        higherIsBetter = false,
        unitLabel = "mmHg",
        zones = listOf(
            ThresholdZone("Normal",     80.0, 120.0, MetricColors.ZoneGood),
            ThresholdZone("Elevated",  120.0, 130.0, MetricColors.ZoneBorderline),
            ThresholdZone("Stage 1",   130.0, 140.0, MetricColors.ZoneElevated),
            ThresholdZone("High",      140.0, 180.0, MetricColors.ZoneConcern),
        ),
    )

    private val bloodSugar = MetricThresholds(
        displayMin = 50.0, displayMax = 200.0,
        higherIsBetter = false,
        unitLabel = "mg/dL",
        zones = listOf(
            ThresholdZone("Low",       50.0,  70.0, MetricColors.ZoneLow),
            ThresholdZone("Normal",    70.0, 100.0, MetricColors.ZoneGood),
            ThresholdZone("Elevated", 100.0, 126.0, MetricColors.ZoneBorderline),
            ThresholdZone("High",     126.0, 200.0, MetricColors.ZoneConcern),
        ),
    )
}

/**
 * BP helper — returns the worse zone category of systolic vs diastolic.
 * The threshold bar is scaled on systolic, but zone selection uses the
 * worse of the two readings so the user isn't misled by a normal systolic
 * when diastolic is high (or vice versa).
 */
fun bpZone(sys: Double?, dia: Double?): ThresholdZone? {
    if (sys == null && dia == null) return null

    // Diastolic category
    val diaZone = when {
        dia == null -> null
        dia < 80.0  -> "Normal"
        dia < 90.0  -> "Stage1"
        else        -> "High"
    }

    // Systolic category
    val sysZone = when {
        sys == null -> null
        sys < 120.0  -> "Normal"
        sys < 130.0  -> "Elevated"
        sys < 140.0  -> "Stage1"
        else         -> "High"
    }

    // Pick the worse category (ordered Normal < Elevated < Stage1 < High)
    val worst = when {
        sysZone == "High"   || diaZone == "High"   -> "High"
        sysZone == "Stage1" || diaZone == "Stage1" -> "Stage1"
        sysZone == "Elevated"                      -> "Elevated"
        sysZone == "Normal"  && diaZone == "Normal"-> "Normal"
        diaZone == "Normal"  && sysZone == null    -> "Normal"
        sysZone == "Normal"  && diaZone == null    -> "Normal"
        else                                        -> null
    }

    return MetricThresholdTable.forKind(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC)
        ?.zones?.firstOrNull { it.label.equals(worst, ignoreCase = true) }
}

package com.pulseloop.ui.components

import androidx.compose.ui.graphics.Color
import com.pulseloop.service.MetricKind
import com.pulseloop.service.VitalColorToken
import com.pulseloop.ui.theme.PulseColors

/**
 * Vitals reference-zone palette — ported from the `zone*` colors in AppTheme.swift (PulseColors).
 * These are the ONLY colors a [com.pulseloop.service.MetricZone] may use, so the chart line,
 * reference band, gauge arc, stat dot, and status label are always identical for the same zone.
 *
 * Complements [com.pulseloop.ui.theme.MetricColors] (the coarse semantic palette used by
 * MetricThresholds); this is the finer iOS-parity palette the zone engine's tokens resolve to.
 */
object ZonePalette {
    val ZoneBlue      = PulseColors.zoneBlue        // low / cool
    val ZoneMint      = PulseColors.zoneMint        // optimal / typical
    val ZoneCyan      = PulseColors.zoneCyan        // normal (SpO₂/stress)
    val ZoneAmber     = PulseColors.zoneAmber       // caution
    val ZoneSoftAmber = PulseColors.zoneSoftAmber   // slight caution (distinct from amber)
    val ZoneOrange    = PulseColors.zoneOrange      // elevated / low-oxygen / stage 1
    val ZoneRed       = PulseColors.zoneRed         // high / critical
    val ZoneCritical  = PulseColors.zoneCritical    // brighter/deeper red for HR high vs the HR accent

    /** Neutral "no information" color (iOS PulseColors.textMuted). */
    val Neutral = PulseColors.textMuted

    /** The brand accent color for a metric — iOS PulseColors metric accents. */
    fun accent(metric: MetricKind): Color = when (metric) {
        MetricKind.HEART_RATE -> PulseColors.heartRate
        MetricKind.SPO2 -> PulseColors.spo2
        MetricKind.HRV -> PulseColors.hrv
        MetricKind.BLOOD_PRESSURE -> PulseColors.bloodPressure
        MetricKind.STRESS -> PulseColors.stress
        MetricKind.FATIGUE -> PulseColors.fatigue
        MetricKind.GLUCOSE -> PulseColors.bloodSugar
        MetricKind.TEMPERATURE -> PulseColors.temperature
    }
}

/**
 * The single token→color resolution point (iOS `VitalColorToken.color`). Views, charts, gauges,
 * and legends all go through this — no zone hex anywhere else.
 */
fun VitalColorToken.toColor(): Color = when (this) {
    VitalColorToken.Blue -> ZonePalette.ZoneBlue
    VitalColorToken.Mint -> ZonePalette.ZoneMint
    VitalColorToken.Cyan -> ZonePalette.ZoneCyan
    VitalColorToken.Amber -> ZonePalette.ZoneAmber
    VitalColorToken.SoftAmber -> ZonePalette.ZoneSoftAmber
    VitalColorToken.Orange -> ZonePalette.ZoneOrange
    VitalColorToken.Red -> ZonePalette.ZoneRed
    VitalColorToken.BrightRed -> ZonePalette.ZoneCritical
    VitalColorToken.Neutral -> ZonePalette.Neutral
    is VitalColorToken.MetricAccent -> ZonePalette.accent(metric)
}

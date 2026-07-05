package com.pulseloop.ui.components

import androidx.compose.ui.graphics.Color
import com.pulseloop.service.MetricKind
import com.pulseloop.service.VitalColorToken

/**
 * Vitals reference-zone palette — ported from the `zone*` colors in AppTheme.swift (PulseColors).
 * These are the ONLY colors a [com.pulseloop.service.MetricZone] may use, so the chart line,
 * reference band, gauge arc, stat dot, and status label are always identical for the same zone.
 *
 * Complements [com.pulseloop.ui.theme.MetricColors] (the coarse semantic palette used by
 * MetricThresholds); this is the finer iOS-parity palette the zone engine's tokens resolve to.
 */
object ZonePalette {
    val ZoneBlue      = Color(0xFF4DA3FF)   // low / cool
    val ZoneMint      = Color(0xFF35E0A1)   // optimal / typical
    val ZoneCyan      = Color(0xFF4DDCFF)   // normal (SpO₂/stress)
    val ZoneAmber     = Color(0xFFFFB86B)   // caution
    val ZoneSoftAmber = Color(0xFFFFD9A0)   // slight caution (distinct from amber)
    val ZoneOrange    = Color(0xFFFF8A4C)   // elevated / low-oxygen / stage 1
    val ZoneRed       = Color(0xFFFF4D6D)   // high / critical
    val ZoneCritical  = Color(0xFFFF1744)   // brighter/deeper red for HR high vs the HR accent

    /** Neutral "no information" color (iOS PulseColors.textMuted). */
    val Neutral = Color(0xFF6F7A8C)

    /** The brand accent color for a metric — iOS PulseColors metric accents. */
    fun accent(metric: MetricKind): Color = when (metric) {
        MetricKind.HEART_RATE -> Color(0xFFFF4D6D)       // heartRate
        MetricKind.SPO2 -> Color(0xFF4DDCFF)             // spo2
        MetricKind.HRV -> Color(0xFF9D7CFF)              // hrv
        MetricKind.BLOOD_PRESSURE -> Color(0xFFFF6B9D)   // bloodPressure
        MetricKind.STRESS -> Color(0xFFFF8A4C)           // stress
        MetricKind.FATIGUE -> Color(0xFFC77DFF)          // fatigue
        MetricKind.GLUCOSE -> Color(0xFFFFB84D)          // bloodSugar
        MetricKind.TEMPERATURE -> Color(0xFF2DD4D8)      // temperature
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

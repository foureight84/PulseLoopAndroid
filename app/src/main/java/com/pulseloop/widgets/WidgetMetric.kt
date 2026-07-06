package com.pulseloop.widgets

import androidx.compose.ui.graphics.Color
import com.pulseloop.service.MetricKind
import com.pulseloop.ui.components.ZonePalette
import com.pulseloop.ui.theme.PulseColors

/**
 * The metrics a user can put on a configurable home-screen widget — every Today-page tile.
 * Ported from PulseLoopWidgets/WidgetMetric.swift. [key] values are stable identifiers persisted
 * in the user's widget configuration (Glance preferences); don't rename them.
 */
enum class WidgetMetric(val key: String, val displayName: String) {
    ACTIVITY("activity", "Activity"),
    SLEEP("sleep", "Sleep"),
    HEART_RATE("heartRate", "Heart Rate"),
    SPO2("spo2", "Blood Oxygen"),
    HRV("hrv", "HRV"),
    TEMPERATURE("temperature", "Skin Temperature"),
    STRESS("stress", "Stress"),
    FATIGUE("fatigue", "Fatigue"),
    BLOOD_PRESSURE("bloodPressure", "Blood Pressure"),
    GLUCOSE("glucose", "Blood Sugar");

    /** The vitals kind whose payload backs this tile; null for the two non-vitals tiles. */
    val metricKind: MetricKind?
        get() = when (this) {
            ACTIVITY, SLEEP -> null
            HEART_RATE -> MetricKind.HEART_RATE
            SPO2 -> MetricKind.SPO2
            HRV -> MetricKind.HRV
            TEMPERATURE -> MetricKind.TEMPERATURE
            STRESS -> MetricKind.STRESS
            FATIGUE -> MetricKind.FATIGUE
            BLOOD_PRESSURE -> MetricKind.BLOOD_PRESSURE
            GLUCOSE -> MetricKind.GLUCOSE
        }

    /** Which Today tile visual this metric renders as (mirrors the Today grid). */
    enum class TileStyle { RINGS, SLEEP, CHART, GAUGE, BLOOD_PRESSURE }

    val tileStyle: TileStyle
        get() = when (this) {
            ACTIVITY -> TileStyle.RINGS
            SLEEP -> TileStyle.SLEEP
            HEART_RATE, SPO2, HRV, TEMPERATURE -> TileStyle.CHART
            STRESS, FATIGUE, GLUCOSE -> TileStyle.GAUGE
            BLOOD_PRESSURE -> TileStyle.BLOOD_PRESSURE
        }

    /** Header eyebrow label, matching the in-app tile chrome. */
    val headerLabel: String
        get() = when (this) {
            ACTIVITY -> "Activity"
            SLEEP -> "Sleep"
            else -> metricKind?.title ?: key
        }

    /** Header dot color, matching the in-app tile chrome. */
    val accentColor: Color
        get() = when (this) {
            ACTIVITY -> PulseColors.steps
            SLEEP -> PulseColors.sleep
            else -> metricKind?.let { ZonePalette.accent(it) } ?: PulseColors.accent
        }

    companion object {
        fun fromKey(key: String?): WidgetMetric? = entries.firstOrNull { it.key == key }
    }
}

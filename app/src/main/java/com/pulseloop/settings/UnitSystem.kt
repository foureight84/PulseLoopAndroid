package com.pulseloop.settings

import com.pulseloop.R
import com.pulseloop.util.L10n
import java.util.Locale

/**
 * Unit system conversion utilities.
 * Auto-detects from device locale, supports manual toggle.
 */
enum class UnitSystem(val label: String) {
    METRIC("Metric (km, kg, °C)"),
    IMPERIAL("Imperial (mi, lbs, °F)");

    companion object {
        fun fromLocale(locale: Locale = Locale.getDefault()): UnitSystem {
            // US, Liberia, Myanmar use imperial. Everything else → metric.
            return if (locale.country == "US" || locale.country == "LR" || locale.country == "MM") {
                IMPERIAL
            } else {
                METRIC
            }
        }
    }
}

object UnitConverter {
    fun distance(meters: Double, system: UnitSystem): Double = when (system) {
        UnitSystem.METRIC -> meters / 1000.0  // km
        UnitSystem.IMPERIAL -> meters / 1609.344  // miles
    }

    fun distanceUnit(system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> L10n.get(R.string.unit_km)
        UnitSystem.IMPERIAL -> L10n.get(R.string.unit_mi)
    }

    fun weight(kg: Double, system: UnitSystem): Double = when (system) {
        UnitSystem.METRIC -> kg
        UnitSystem.IMPERIAL -> kg * 2.20462  // lbs
    }

    fun weightUnit(system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> L10n.get(R.string.unit_kg)
        UnitSystem.IMPERIAL -> L10n.get(R.string.unit_lbs)
    }

    fun height(cm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> L10n.get(R.string.value_cm_s, "%.0f".format(cm))
        UnitSystem.IMPERIAL -> {
            val totalInches = cm / 2.54
            val feet = (totalInches / 12).toInt()
            val inches = (totalInches % 12).toInt()
            "$feet'$inches\""
        }
    }

    fun temperature(celsius: Double, system: UnitSystem): Double = when (system) {
        UnitSystem.METRIC -> celsius
        UnitSystem.IMPERIAL -> celsius * 9.0 / 5.0 + 32.0
    }

    fun temperatureUnit(system: UnitSystem): String = when (system) {
        UnitSystem.METRIC -> "°C"
        UnitSystem.IMPERIAL -> "°F"
    }
}

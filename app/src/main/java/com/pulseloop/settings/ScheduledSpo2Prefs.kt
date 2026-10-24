package com.pulseloop.settings

import android.content.Context
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability

/**
 * The opt-in behind issue #103: have the app start a real spot SpO₂ on the SpO₂ interval.
 *
 * On a YCBT ring the hourly SpO₂ is computed from heart rate (one HR always maps to the same
 * value), so the history line is an estimate. The ring's manual measurement is real — red LED,
 * logged on its own — so the app runs that instead, between the ring's own readings
 * ([com.pulseloop.service.ScheduledSpo2Slots]).
 *
 * Off by default: each run holds the connection and the optical sensor for about a minute.
 * In SharedPreferences rather than `device_measurement_configs` to stay clear of a schema bump.
 */
class ScheduledSpo2Prefs internal constructor(private val prefs: android.content.SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /**
     * How often the app starts its own spot SpO₂, in minutes — independent of the interval the
     * ring reads SpO₂ on (issue #103: each app run holds the red LED and the link for ~50 s, so
     * at 2–3 h the battery and the night-time flashes are both spared). The ring's own SpO₂
     * monitor keeps its own setting; the slots stay between its readings either way.
     */
    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        set(value) { prefs.edit().putInt(KEY_INTERVAL_MINUTES, sanitizeIntervalMinutes(value)).apply() }

    /**
     * Whether to hide estimated hourly SpO₂ (computed by the ring from heart rate) and only
     * display real spot measurements. Defaults to true.
     */
    var hideEstimated: Boolean
        get() = prefs.getBoolean(KEY_HIDE_ESTIMATED, true)
        set(value) { prefs.edit().putBoolean(KEY_HIDE_ESTIMATED, value).apply() }

    companion object {
        private const val PREFS_NAME = "scheduled_spo2"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_HIDE_ESTIMATED = "hide_estimated"

        /** Options are 60/120/180; 60 is what the app ran at before a choice existed. */
        const val DEFAULT_INTERVAL_MINUTES = 60
        const val MIN_INTERVAL_MINUTES = 60
        const val MAX_INTERVAL_MINUTES = 180

        fun sanitizeIntervalMinutes(minutes: Int): Int {
            val snapped = ((minutes + 30) / 60) * 60
            return snapped.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        }

        /**
         * Rings this is offered for: a manual SpO₂ that ends with the ring's own `04 0e` verdict,
         * so the run has a known end and the connection can be released right after it. The YCBT
         * protocol families signal that today (issue #59) — `COLMI_SMART_HEALTH` included, which
         * rides the same [com.pulseloop.ring.YCBTDriver] but is its own `RingDeviceType`, so the
         * original `== YCBT` check hid both #103 controls from those rings (issue #103,
         * Albabit's `colmi-smarthealth` unit, 2026-10-23).
         */
        fun isSupported(device: DeviceEntity?): Boolean =
            device != null && device.deviceType in setOf(
                RingDeviceType.YCBT,
                RingDeviceType.COLMI_SMART_HEALTH,
            ) && WearableCapability.MANUAL_SPO2 in device.capabilities
    }
}

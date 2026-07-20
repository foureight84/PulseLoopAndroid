package com.pulseloop.ring

import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Setting-group (`0x01`) keys — `Constants.DATATYPE` low bytes. */
object YCBTSettingKey {
    const val SET_TIME: UByte = 0x00u              // SettingTime          256
    const val USER_INFO: UByte = 0x03u             // SettingUserInfo      259
    const val UNITS: UByte = 0x04u                 // SettingUnit          260
    const val HEART_MONITOR: UByte = 0x0cu         // SettingHeartMonitor  268
    const val LANGUAGE: UByte = 0x12u              // SettingLanguage      274
    const val BLOOD_PRESSURE_MONITOR: UByte = 0x1cu // SettingBloodPressureMonitor 284
    const val TEMPERATURE_MONITOR: UByte = 0x20u   // SettingTemperatureMonitor   288
    const val BLOOD_OXYGEN_MONITOR: UByte = 0x26u  // SettingBloodOxygenModeMonitor 294
    const val HRV_MONITOR: UByte = 0x45u           // SettingHRVMonitor    325
}

/**
 * Ported from YCBTSettingsEncoder.swift (iOS #82).
 * Byte builders for the Setting group, shared by every YCBT family. Each returns a *logical*
 * command (`[type, cmd, payload...]`); the driver's `frame(_)` adds the length field and CRC.
 *
 * Every one of these is idempotent and individually ACKed by the ring with a 1-byte status.
 */
object YCBTSettingsEncoder {
    /**
     * The ring's all-day sampler refuses intervals under 30 minutes (SmartHealth clamps the same
     * way for rings). The vendor default is 60.
     */
    const val MINIMUM_INTERVAL_MINUTES = 30
    const val DEFAULT_INTERVAL_MINUTES = 60

    /**
     * Clamp a user-chosen cadence into what the firmware will actually accept. PulseLoop's shared
     * [MeasurementSettings] default is 5 minutes (a Colmi cadence), which this floors to 30 rather
     * than silently letting the ring reject the write.
     */
    fun clampInterval(minutes: Int): UByte {
        if (minutes <= 0) return DEFAULT_INTERVAL_MINUTES.toUByte()
        return minutes.coerceIn(MINIMUM_INTERVAL_MINUTES, 255).toUByte()
    }

    // MARK: - Clock

    /**
     * `01 00` + `[year:u16 LE][month][day][hour][min][sec][weekday]`.
     * The weekday byte is **Mon=0 ... Sun=6**, against `Calendar.DAY_OF_WEEK` where Sunday == 1.
     */
    fun setTime(instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<UByte> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(zone))
        cal.timeInMillis = instant.toEpochMilli()
        val year = cal.get(Calendar.YEAR)
        val gregorianWeekday = cal.get(Calendar.DAY_OF_WEEK)   // Sunday = 1 ... Saturday = 7
        val weekday = if (gregorianWeekday == 1) 6 else gregorianWeekday - 2
        return listOf(
            YCBTGroup.SETTING, YCBTSettingKey.SET_TIME,
            (year and 0xff).toUByte(), ((year shr 8) and 0xff).toUByte(),
            (cal.get(Calendar.MONTH) + 1).toUByte(), cal.get(Calendar.DAY_OF_MONTH).toUByte(),
            cal.get(Calendar.HOUR_OF_DAY).toUByte(), cal.get(Calendar.MINUTE).toUByte(),
            cal.get(Calendar.SECOND).toUByte(), weekday.toUByte(),
        )
    }

    // MARK: - Profile / locale

    /**
     * `01 03` + `[heightCm][weightKg][sex][age]`. The ring feeds these into its step, calorie and
     * BP algorithms, so a wrong profile is a wrong reading.
     *
     * UNVERIFIED: the sex byte's polarity. The SDK never asserts the mapping; we send 1 for male,
     * 0 otherwise, which is the vendor convention. A wrong value skews calorie estimates slightly
     * and nothing else.
     */
    fun userInfo(profile: UserProfileValues): List<UByte> = listOf(
        YCBTGroup.SETTING, YCBTSettingKey.USER_INFO,
        profile.heightCm, profile.weightKg,
        if (profile.gender == 0x01u.toUByte()) 1u.toUByte() else 0u.toUByte(),
        profile.age,
    )

    /**
     * `01 04` + `[distance][weight][temp][timeFormat][bloodSugar][uricAcid]` — 0 = metric
     * everywhere; `timeFormat` is 1 for 12-hour, 0 for 24-hour.
     */
    fun units(metric: Boolean, is24Hour: Boolean = true): List<UByte> {
        val imperial: UByte = if (metric) 0u else 1u
        return listOf(
            YCBTGroup.SETTING, YCBTSettingKey.UNITS,
            imperial, imperial, imperial, if (is24Hour) 0u else 1u, 0u, 0u,
        )
    }

    /** `01 12` + `[languageCode]` (the vendor's own enum; 0 = English). */
    fun language(code: UByte = 0u): List<UByte> = listOf(YCBTGroup.SETTING, YCBTSettingKey.LANGUAGE, code)

    // MARK: - All-day monitors

    /**
     * The five background samplers, each `{enable, intervalMinutes}`. **These — not the `05 4x`
     * burst — are what make the ring record anything between syncs.**
     *
     * [MeasurementSettings] has no blood-pressure flag (no YCBT family has an all-day BP sampler);
     * BP rides the HR toggle. `stressEnabled` has no YCBT monitor command — the ring stores stress
     * in the body-data history record (`05 33`), it doesn't sample it on its own schedule.
     */
    fun monitorCommands(settings: MeasurementSettings): List<List<UByte>> {
        val interval = clampInterval(settings.hrIntervalMinutes)
        return listOf(
            heartMonitor(settings.hrEnabled, interval),
            bloodPressureMonitor(settings.hrEnabled, interval),
            temperatureMonitor(settings.temperatureEnabled, interval),
            bloodOxygenMonitor(settings.spo2Enabled, interval),
            hrvMonitor(settings.hrvEnabled, interval),
        )
    }

    fun heartMonitor(enabled: Boolean, intervalMinutes: UByte): List<UByte> =
        listOf(YCBTGroup.SETTING, YCBTSettingKey.HEART_MONITOR, if (enabled) 1u else 0u, intervalMinutes)

    fun bloodPressureMonitor(enabled: Boolean, intervalMinutes: UByte): List<UByte> =
        listOf(YCBTGroup.SETTING, YCBTSettingKey.BLOOD_PRESSURE_MONITOR, if (enabled) 1u else 0u, intervalMinutes)

    fun temperatureMonitor(enabled: Boolean, intervalMinutes: UByte): List<UByte> =
        listOf(YCBTGroup.SETTING, YCBTSettingKey.TEMPERATURE_MONITOR, if (enabled) 1u else 0u, intervalMinutes)

    fun bloodOxygenMonitor(enabled: Boolean, intervalMinutes: UByte): List<UByte> =
        listOf(YCBTGroup.SETTING, YCBTSettingKey.BLOOD_OXYGEN_MONITOR, if (enabled) 1u else 0u, intervalMinutes)

    /**
     * HRV takes a 5-byte payload. Only the first two args are named in the SDK's own call sites;
     * UNVERIFIED: the trailing three (window / weekday mask / reserved). Zero-filled — a wrong
     * non-zero guess could arm a schedule we didn't intend.
     */
    fun hrvMonitor(enabled: Boolean, intervalMinutes: UByte): List<UByte> =
        listOf(YCBTGroup.SETTING, YCBTSettingKey.HRV_MONITOR, if (enabled) 1u else 0u, intervalMinutes, 0u, 0u, 0u)
}

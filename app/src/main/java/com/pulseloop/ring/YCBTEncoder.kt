package com.pulseloop.ring

import java.time.Instant
import java.util.TimeZone

/**
 * Ported from YCBTEncoder.swift and YCBTSettingsEncoder.swift.
 * Builds *logical* YCBT commands — [type, cmd, payload…] without the length field or CRC,
 * which YCBTDriver.frame appends.
 */

object YCBTSettingKey {
    const val SET_TIME: Int = 0x00
    const val USER_INFO: Int = 0x03
    const val UNITS: Int = 0x04
    const val HEART_MONITOR: Int = 0x0c
    const val LANGUAGE: Int = 0x12
    const val BLOOD_PRESSURE_MONITOR: Int = 0x1c
    const val TEMPERATURE_MONITOR: Int = 0x20
    const val BLOOD_OXYGEN_MONITOR: Int = 0x26
    const val HRV_MONITOR: Int = 0x45
}

class YCBTSettingsEncoder {
    companion object {
        const val MINIMUM_INTERVAL_MINUTES = 30
        const val DEFAULT_INTERVAL_MINUTES = 60

        fun clampInterval(minutes: Int): Int {
            if (minutes <= 0) return DEFAULT_INTERVAL_MINUTES
            return minOf(255, maxOf(MINIMUM_INTERVAL_MINUTES, minutes))
        }
    }

    /** 01 00 + [year:u16 LE][month][day][hour][min][sec][weekday]. Weekday is Mon=0 … Sun=6. */
    fun setTime(date: Instant = Instant.now(), timeZone: TimeZone = TimeZone.getDefault()): ByteArray {
        val calendar = java.util.Calendar.getInstance(timeZone).apply { time = java.util.Date(date.toEpochMilli()) }
        val year = calendar.get(java.util.Calendar.YEAR)
        val gregorianWeekday = calendar.get(java.util.Calendar.DAY_OF_WEEK) // 1=Sun, 2=Mon...
        val weekday = if (gregorianWeekday == 1) 6 else gregorianWeekday - 2
        return byteArrayOf(
            YCBTGroup.SETTING.toByte(), YCBTSettingKey.SET_TIME.toByte(),
            (year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(),
            (calendar.get(java.util.Calendar.MONTH) + 1).toByte(),
            calendar.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            calendar.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(java.util.Calendar.MINUTE).toByte(),
            calendar.get(java.util.Calendar.SECOND).toByte(),
            weekday.toByte(),
        )
    }

    /** 01 03 + [heightCm][weightKg][sex][age]. */
    fun userInfo(profile: UserProfileValues): ByteArray {
        return byteArrayOf(
            YCBTGroup.SETTING.toByte(), YCBTSettingKey.USER_INFO.toByte(),
            profile.heightCm.toByte(), profile.weightKg.toByte(),
            (if (profile.gender == 0x01u.toUByte()) 1 else 0).toByte(),
            profile.age.toByte(),
        )
    }

    /** 01 04 + [distance][weight][temp][timeFormat][bloodSugar][uricAcid]. 0 = metric everywhere. */
    fun units(metric: Boolean, is24Hour: Boolean = true): ByteArray {
        val imperial = if (metric) 0 else 1
        return byteArrayOf(
            YCBTGroup.SETTING.toByte(), YCBTSettingKey.UNITS.toByte(),
            imperial.toByte(), imperial.toByte(), imperial.toByte(),
            (if (is24Hour) 0 else 1).toByte(),
            0, 0,
        )
    }

    /** 01 12 + [languageCode]. */
    fun language(code: Int = 0): ByteArray {
        return byteArrayOf(YCBTGroup.SETTING.toByte(), YCBTSettingKey.LANGUAGE.toByte(), code.toByte())
    }

    /** The five background samplers, each {enable, intervalMinutes}. */
    fun monitorCommands(settings: MeasurementSettings): List<ByteArray> {
        val interval = clampInterval(settings.hrIntervalMinutes).toByte()
        return listOf(
            heartMonitor(enabled = settings.hrEnabled, intervalMinutes = interval),
            bloodPressureMonitor(enabled = settings.hrEnabled, intervalMinutes = interval),
            temperatureMonitor(enabled = settings.temperatureEnabled, intervalMinutes = interval),
            bloodOxygenMonitor(enabled = settings.spo2Enabled, intervalMinutes = interval),
            hrvMonitor(enabled = settings.hrvEnabled, intervalMinutes = interval),
        )
    }

    private fun heartMonitor(enabled: Boolean, intervalMinutes: Byte): ByteArray {
        return byteArrayOf(YCBTGroup.SETTING.toByte(), YCBTSettingKey.HEART_MONITOR.toByte(), if (enabled) 1 else 0, intervalMinutes)
    }

    private fun bloodPressureMonitor(enabled: Boolean, intervalMinutes: Byte): ByteArray {
        return byteArrayOf(YCBTGroup.SETTING.toByte(), YCBTSettingKey.BLOOD_PRESSURE_MONITOR.toByte(), if (enabled) 1 else 0, intervalMinutes)
    }

    private fun temperatureMonitor(enabled: Boolean, intervalMinutes: Byte): ByteArray {
        return byteArrayOf(YCBTGroup.SETTING.toByte(), YCBTSettingKey.TEMPERATURE_MONITOR.toByte(), if (enabled) 1 else 0, intervalMinutes)
    }

    private fun bloodOxygenMonitor(enabled: Boolean, intervalMinutes: Byte): ByteArray {
        return byteArrayOf(YCBTGroup.SETTING.toByte(), YCBTSettingKey.BLOOD_OXYGEN_MONITOR.toByte(), if (enabled) 1 else 0, intervalMinutes)
    }

    private fun hrvMonitor(enabled: Boolean, intervalMinutes: Byte): ByteArray {
        return byteArrayOf(YCBTGroup.SETTING.toByte(), YCBTSettingKey.HRV_MONITOR.toByte(), if (enabled) 1 else 0, intervalMinutes, 0, 0, 0)
    }
}

class YCBTEncoder {
    private val settings = YCBTSettingsEncoder()

    fun setTime(date: Instant = Instant.now()): ByteArray = settings.setTime(date)

    fun startupSequence(
        date: Instant = Instant.now(),
        measurement: MeasurementSettings = MeasurementSettings.ALL_ON_DEFAULT,
        profile: UserProfileValues = UserProfileValues(metric = true, gender = 0x02u, age = 25u, heightCm = 175u, weightKg = 70u),
        languageCode: Int = 0,
        is24Hour: Boolean = true,
    ): List<ByteArray> {
        val seq = mutableListOf<ByteArray>()
        seq.add(setTime(date))
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO, byteArrayOf(0x47, 0x43)))
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_SUPPORT_FUNCTION, byteArrayOf(0x47, 0x46)))
        // Do not query GetChipScheme (02 1B) during startup. The TK5 accepts it, but the
        // R10M closes an otherwise healthy connection with HCI 0x13 immediately on receipt.
        // Chip-scheme metadata is informational and no Android feature depends on it.
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_DEVICE_NAME, byteArrayOf(0x47, 0x50)))
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_USER_CONFIG, byteArrayOf(0x43, 0x46)))
        seq.add(settings.language(languageCode))
        seq.add(settings.units(metric = profile.metric, is24Hour = is24Hour))
        seq.addAll(settings.monitorCommands(measurement))
        seq.add(settings.userInfo(profile))
        seq.add(enableLiveStatus())
        return seq
    }

    fun monitorCommands(measurement: MeasurementSettings): List<ByteArray> =
        settings.monitorCommands(measurement)

    fun userInfo(profile: UserProfileValues): ByteArray = settings.userInfo(profile)

    fun deviceInfoRequest(): ByteArray =
        logical(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO, byteArrayOf(0x47, 0x43))

    fun deviceNameRequest(): ByteArray =
        logical(YCBTGroup.GET, YCBTCommand.GET_DEVICE_NAME, byteArrayOf(0x47, 0x50))

    fun enableLiveStatus(): ByteArray =
        logical(YCBTGroup.APP_CONTROL, YCBTCommand.LIVE_STATUS_PUSH, byteArrayOf(0x01, 0x00, 0x02))

    fun healthHistoryRequest(type: YCBTHistoryType): ByteArray =
        YCBTHealthCommand.historyRequest(type)

    fun historyBlockAck(status: Int): ByteArray =
        YCBTHealthCommand.historyBlockAck(status)

    fun heartRateStart(): ByteArray = liveMeasurement(enable = true, mode = YCBTMeasurementMode.HEART_RATE)
    fun heartRateStop(): ByteArray = liveMeasurement(enable = false, mode = YCBTMeasurementMode.HEART_RATE)
    fun spo2Start(): ByteArray = liveMeasurement(enable = true, mode = YCBTMeasurementMode.SPO2)
    fun spo2Stop(): ByteArray = liveMeasurement(enable = false, mode = YCBTMeasurementMode.SPO2)
    fun hrvStart(): ByteArray = liveMeasurement(enable = true, mode = YCBTMeasurementMode.HRV)
    fun hrvStop(): ByteArray = liveMeasurement(enable = false, mode = YCBTMeasurementMode.HRV)
    fun bloodPressureStart(): ByteArray = liveMeasurement(enable = true, mode = YCBTMeasurementMode.BLOOD_PRESSURE)
    fun bloodPressureStop(): ByteArray = liveMeasurement(enable = false, mode = YCBTMeasurementMode.BLOOD_PRESSURE)

    fun findDevice(): ByteArray =
        logical(YCBTGroup.APP_CONTROL, YCBTCommand.FIND_DEVICE, byteArrayOf(0x01, 0x05, 0x02))

    private fun liveMeasurement(enable: Boolean, mode: Int): ByteArray {
        return logical(YCBTGroup.APP_CONTROL, YCBTCommand.LIVE_MEASUREMENT, byteArrayOf(if (enable) 1 else 0, mode.toByte()))
    }

    private fun logical(group: Int, cmd: Int, payload: ByteArray): ByteArray {
        return byteArrayOf(group.toByte(), cmd.toByte()) + payload
    }
}

package com.pulseloop.ring

import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/**
 * Ported from [ColmiEncoder] in ColmiEncoder.swift.
 * Builds Colmi command payloads. Normal commands returned as logical content
 * (16-byte framing applied by ColmiDriver.frame). Big-data requests returned raw.
 */
object ColmiEncoder {
    fun phoneName(): ByteArray = byteArrayOf(
        ColmiCommandID.PHONE_NAME.toByte(), 0x02, 0x0A, 'P'.code.toByte(), 'L'.code.toByte()
    )

    fun setDateTime(instant: Instant = Instant.now()): ByteArray {
        val zdt = instant.atZone(ZoneId.systemDefault())
        fun bcd(value: Int): Int = ((value % 100 / 10) shl 4) or (value % 10)
        return byteArrayOf(
            ColmiCommandID.SET_DATE_TIME.toByte(),
            bcd(zdt.year % 2000).toByte(),
            bcd(zdt.monthValue).toByte(),
            bcd(zdt.dayOfMonth).toByte(),
            bcd(zdt.hour).toByte(),
            bcd(zdt.minute).toByte(),
            bcd(zdt.second).toByte(),
        )
    }

    fun userPreferences(
        metric: Boolean = true, gender: UByte = 0x02u, age: UByte = 25u,
        heightCm: UByte = 175u, weightKg: UByte = 70u
    ): ByteArray = byteArrayOf(
        ColmiCommandID.PREFERENCES.toByte(),
        ColmiCommandID.PREF_WRITE.toByte(),
        0x00,
        if (metric) 0x00 else 0x01,
        gender.toByte(), age.toByte(), heightCm.toByte(), weightKg.toByte(),
        0x00, 0x00, 0x00,
    )

    fun battery(): ByteArray = byteArrayOf(ColmiCommandID.BATTERY.toByte())

    fun readPref(command: UByte): ByteArray = byteArrayOf(command.toByte(), ColmiCommandID.PREF_READ.toByte())

    fun writePref(command: UByte, enabled: Boolean): ByteArray =
        byteArrayOf(command.toByte(), ColmiCommandID.PREF_WRITE.toByte(), if (enabled) 0x01 else 0x00)

    fun readTempPref(): ByteArray = byteArrayOf(
        ColmiCommandID.AUTO_TEMP_PREF.toByte(), 0x03, ColmiCommandID.PREF_READ.toByte()
    )

    fun readGoals(): ByteArray = byteArrayOf(ColmiCommandID.GOALS.toByte(), ColmiCommandID.PREF_READ.toByte())

    fun manualHeartRate(enable: Boolean = true): ByteArray = if (enable) {
        byteArrayOf(ColmiCommandID.MANUAL_HEART_RATE.toByte(), ColmiCommandID.RT_HEART_RATE.toByte())
    } else {
        // Stop MUST use CMD_STOP_REAL_TIME (0x6A). The old [0x69, 0x02] was another
        // CMD_START_REAL_TIME (reading type 2), so the optical sensor never switched off
        // and the ring kept pulsing until it timed out. Mirror the SpO₂ stop frame.
        byteArrayOf(
            ColmiCommandID.REALTIME_STOP.toByte(),
            ColmiCommandID.RT_HEART_RATE.toByte(),
            0x00, 0x00,
        )
    }

    fun realtimeHeartRate(enable: Boolean): ByteArray =
        byteArrayOf(ColmiCommandID.REALTIME_HEART_RATE.toByte(), if (enable) 0x01 else 0x02)

    fun realtimeHeartRateContinue(): ByteArray =
        byteArrayOf(ColmiCommandID.REALTIME_HEART_RATE.toByte(), 0x03)

    /**
     * On-demand SpO₂ spot measurement via the real-time command family (0x69/0x6A).
     * Start: [0x69, reading_type=SPO2(3), action=START(1)]; the ring streams
     * [0x69, 3, error, value, …] frames until stopped. Stop: [0x6A, 3, 0, 0].
     * From colmi_r02_client real_time.py (CMD_START_REAL_TIME=105 / CMD_STOP_REAL_TIME=106).
     */
    fun manualSpO2(enable: Boolean): ByteArray = if (enable) {
        byteArrayOf(
            ColmiCommandID.MANUAL_HEART_RATE.toByte(),
            ColmiCommandID.RT_SPO2.toByte(),
            ColmiCommandID.RT_ACTION_START.toByte(),
        )
    } else {
        byteArrayOf(
            ColmiCommandID.REALTIME_STOP.toByte(),
            ColmiCommandID.RT_SPO2.toByte(),
            0x00, 0x00,
        )
    }

    fun findDevice(): ByteArray = byteArrayOf(ColmiCommandID.FIND_DEVICE.toByte(), 0x55, 0xAA.toByte())
    fun powerOff(): ByteArray = byteArrayOf(ColmiCommandID.POWER_OFF.toByte(), 0x01)
    fun factoryReset(): ByteArray = byteArrayOf(ColmiCommandID.FACTORY_RESET.toByte(), 0x66, 0x66)

    // History requests
    fun syncActivity(daysAgo: Int): ByteArray = byteArrayOf(
        ColmiCommandID.SYNC_ACTIVITY.toByte(),
        daysAgo.coerceIn(0, 255).toByte(),
        0x0F, 0x00, 0x5F, 0x01,
    )

    fun syncHeartRate(fromUnix: Int): ByteArray {
        val ts = fromUnix.toUInt()
        return byteArrayOf(
            ColmiCommandID.SYNC_HEART_RATE.toByte(),
            (ts and 0xFFu).toByte(),
            ((ts shr 8) and 0xFFu).toByte(),
            ((ts shr 16) and 0xFFu).toByte(),
            ((ts shr 24) and 0xFFu).toByte(),
        )
    }

    fun syncStress(): ByteArray = byteArrayOf(ColmiCommandID.SYNC_STRESS.toByte())

    fun syncHRV(daysAgo: Int): ByteArray {
        val d = daysAgo.coerceIn(0, 255).toUInt()
        return byteArrayOf(
            ColmiCommandID.SYNC_HRV.toByte(),
            (d and 0xFFu).toByte(),
            ((d shr 8) and 0xFFu).toByte(),
            ((d shr 16) and 0xFFu).toByte(),
            ((d shr 24) and 0xFFu).toByte(),
        )
    }

    // Big-data requests
    fun bigDataSpo2(): ByteArray = byteArrayOf(
        ColmiCommandID.BIG_DATA_V2.toByte(), ColmiCommandID.BIG_DATA_SPO2.toByte(),
        0x01, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
    )

    fun bigDataSleep(): ByteArray = byteArrayOf(
        ColmiCommandID.BIG_DATA_V2.toByte(), ColmiCommandID.BIG_DATA_SLEEP.toByte(),
        0x01, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
    )

    fun bigDataTemperature(): ByteArray = byteArrayOf(
        ColmiCommandID.BIG_DATA_V2.toByte(), ColmiCommandID.BIG_DATA_TEMPERATURE.toByte(),
        0x01, 0x00, 0x3E, 0x81.toByte(), 0x02,
    )

    fun bigDataBloodSugar(): ByteArray = byteArrayOf(
        ColmiCommandID.BIG_DATA_V2.toByte(), ColmiCommandID.BIG_DATA_BLOOD_SUGAR.toByte(),
        0x01, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
    )

    /** Request BP history from the ring. [fromUnix] = starting epoch (0 = all available). */
    fun syncBp(fromUnix: Int = 0): ByteArray {
        val ts = fromUnix.toUInt()
        return byteArrayOf(
            ColmiCommandID.BP_READ.toByte(),
            (ts and 0xFFu).toByte(),
            ((ts shr 8) and 0xFFu).toByte(),
            ((ts shr 16) and 0xFFu).toByte(),
            ((ts shr 24) and 0xFFu).toByte(),
            0x00, 0x32,  // count = 50
        )
    }

    fun confirmBp(success: Boolean = true): ByteArray =
        byteArrayOf(ColmiCommandID.BP_CONFIRM.toByte(), if (success) 0x00 else 0xFF.toByte())
}

/**
 * Ported from [ColmiCoordinator] in ColmiCoordinator.swift.
 * Coordinator for the Colmi R02 and the wider Yawell ring family.
 */
object ColmiCoordinator : WearableCoordinator {
    override val deviceType = RingDeviceType.COLMI_R02

    private val namePatterns = listOf(
        Regex("^R02_.*"), Regex("^R03_.*"), Regex("^R06_.*"),
        Regex("^COLMI R07_.*"), Regex("^R09_.*"), Regex("^COLMI R10_.*"),
        Regex("^COLMI R12_.*"), Regex("^R05_[0-9A-F]{4}$"),
        Regex("^R10_[0-9A-F]{4}$"), Regex("^R11C?_[0-9A-F]{4}$"),
        Regex("^H59_.*"),
    )

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        if (name != null && namePatterns.any { it.matches(name) }) return true
        return advertisement.serviceUUIDs.contains(ColmiUUIDs.SERVICE_V1) ||
            advertisement.serviceUUIDs.contains(ColmiUUIDs.SERVICE_V2)
    }

    override val capabilities = setOf(
        WearableCapability.HEART_RATE, WearableCapability.SPO2, WearableCapability.STEPS,
        WearableCapability.SLEEP, WearableCapability.BATTERY,
        WearableCapability.REM_SLEEP, WearableCapability.STRESS, WearableCapability.HRV,
        WearableCapability.TEMPERATURE,
        WearableCapability.MANUAL_HEART_RATE, WearableCapability.MANUAL_SPO2,
        WearableCapability.REALTIME_HEART_RATE,
        WearableCapability.REALTIME_STEPS,
        WearableCapability.FIND_DEVICE, WearableCapability.POWER_OFF, WearableCapability.FACTORY_RESET,
        // NOTE: Colmi rings do NOT support blood pressure or blood sugar.
        // See docs/ring-hardware-reference.md §3.
    )

    override val iconSystemName = "circle.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = ColmiDriver(writer)
}

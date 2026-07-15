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

    /**
     * Enable/disable all-day **heart-rate** monitoring. Auto-HR (`0x16`) has a different shape from
     * the other prefs: the on/off flag is `0x01`(on)/`0x02`(off) — *not* `0x01`/`0x00` — and it
     * carries the sampling interval in minutes (rounded to a 5-minute multiple, 5…60). Without this
     * the ring records no background HR, so the HR-history sync (`0x15`) comes back empty.
     *
     * The payload is the **full 7-field record** the official QRing app sends
     * (`HeartRateSettingReq.getWriteInstance`, Oudmon SDK): after the interval come
     * `hrStart, hrTooLow, hrTooHigh, hrTooSwitch`. Older R02 firmware accepts the short
     * 4-byte form, but newer RT-series firmware (e.g. R09 / RT09) ACKs the short frame yet
     * never arms background sampling — HR then only measures on a physical button press.
     * Sending the full record (alarm fields zeroed = "no HR alerts configured", exactly what
     * QRing sends when the user hasn't set alarms) fixes auto-HR on R09 with no R02 regression.
     * See docs/qring-ble-adoption.md §Auto-HR.
     */
    fun autoHeartRate(enabled: Boolean, intervalMinutes: Int = 5): ByteArray {
        val interval = ((intervalMinutes / 5) * 5).coerceIn(5, 60)
        return byteArrayOf(
            ColmiCommandID.AUTO_HR_PREF.toByte(),
            ColmiCommandID.PREF_WRITE.toByte(),
            if (enabled) 0x01 else 0x02,
            interval.toByte(),
            // hrStart, hrTooLow, hrTooHigh, hrTooSwitch — 0 = no HR alarms (hrStart 0 → the
            // firmware's own 5-min default). The four trailing bytes are what newer firmware
            // requires to treat this as a complete HR-setting write.
            0x00, 0x00, 0x00, 0x00,
        )
    }

    /**
     * Read the ring's device-support/capability bitfield (QRing `DeviceSupportReq`, opcode
     * `0x3C`, no sub-data). The reply is parsed by [ColmiDecoder.decodeDeviceSupport]; its
     * `supportBlePair` bit tells us whether to create an OS-level bond, mirroring the official
     * app. See docs/qring-ble-adoption.md §Pairing.
     */
    fun deviceSupport(): ByteArray = byteArrayOf(ColmiCommandID.DEVICE_SUPPORT.toByte())

    fun readTempPref(): ByteArray = byteArrayOf(
        ColmiCommandID.AUTO_TEMP_PREF.toByte(), 0x03, ColmiCommandID.PREF_READ.toByte()
    )

    /**
     * Enable/disable all-day temperature monitoring. Mirrors [readTempPref]'s extra `0x03`
     * framing byte before the write flag. Verified against hardware (`3a 03 02 01` was acked
     * by a Colmi ring).
     */
    fun writeTempPref(enabled: Boolean): ByteArray = byteArrayOf(
        ColmiCommandID.AUTO_TEMP_PREF.toByte(), 0x03, ColmiCommandID.PREF_WRITE.toByte(),
        if (enabled) 0x01 else 0x00,
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
        // New_Sleep_Protocol (big-data action 39) requires a 2-byte payload [0xFF, 0x01] —
        // 0xFF = "all history", 0x01 = protocol version — framed with its real CRC16/MODBUS.
        // The old 1-byte [0xFF] payload (bc 27 01 00 ff 00 ff) is silently ignored by the ring,
        // so sleep never synced. Frame: len=2 (LE), CRC16([0xFF,0x01])=0x8081 (LE 81 80), FF 01.
        // Verified against QRing's LargeDataHandler.addHeader(39, {0xFF, 0x01}) + CRC16.
        0x02, 0x00, 0x81.toByte(), 0x80.toByte(), 0xFF.toByte(), 0x01,
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

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        // The whole Colmi/Yawell ring family advertises under many names but shares one protocol;
        // the per-model catalog owns the name patterns (iOS #49 ColmiCoordinator.matches).
        if (com.pulseloop.wearables.WearableModel.modelForAdvertisedName(name)?.family == RingDeviceType.COLMI_R02) {
            return true
        }
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
        WearableCapability.MEASUREMENT_INTERVAL,  // 0x16 interval + per-vital prefs (iOS #19)
        // NOTE: Colmi rings do NOT support blood pressure or blood sugar.
        // See docs/ring-hardware-reference.md §3.
    )

    override val iconSystemName = "circle.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = ColmiDriver(writer)
}

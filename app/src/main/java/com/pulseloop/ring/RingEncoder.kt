package com.pulseloop.ring

import java.time.Instant
import java.util.TimeZone

/**
 * Ported from [RingEncoder] in RingProtocol.swift.
 * Builds 20-byte command packets for the 56ff ring protocol.
 */
object RingEncoder {

    fun makeStatusCommand(): ByteArray = hexToBytes("0c00000000000000000000000000000000000000")
    fun makeLocaleCommand(locale: String = "en-US"): ByteArray {
        val localeBytes = locale.toByteArray(Charsets.US_ASCII)
        val cmd = ByteArray(20)
        cmd[0] = 0x21
        repeat(minOf(localeBytes.size, 19)) { cmd[it + 1] = localeBytes[it] }
        return cmd
    }
    fun makeActivityQueryCommand(): ByteArray = hexToBytes("0299b85a00000000000000000000000000000000")
    /**
     * Request activity + sleep history for the last N days (0x10).
     * byte[1] = number of days (0-27). The ring sends back 0x10 (steps)
     * and 0x11 (sleep) notifications as multi-packet streams.
     * Matches Gadgetbridge's triggerActivityReportByDays().
     */
    fun makeHistoryQueryCommand(days: Int = 1): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x10
        cmd[1] = days.coerceIn(0, 27).toByte()
        return cmd
    }
    fun makeHistoryMeasurementQueryCommand(): ByteArray = hexToBytes("1600000000000000000000000000000000000000")
    fun makeHeartRateStartCommand(): ByteArray = hexToBytes("14b4000000000000000000000000000000000000")
    fun makeHeartRateStopCommand(): ByteArray = hexToBytes("1500000000000000000000000000000000000000")
    /**
     * Start combined measurement (0x23): triggers HR + systolic + diastolic + SpO₂ + stress.
     * byte[1] = 1 to start, 0 to stop.
     * Response arrives as 0x24 notification with 5 metrics.
     */
    fun makeCombinedMeasurementStart(): ByteArray = hexToBytes("2301000000000000000000000000000000000000")
    fun makeCombinedMeasurementStop(): ByteArray = hexToBytes("2300000000000000000000000000000000000000")

    /**
     * SpO₂-only toggle (0x3E). byte[1] = 1 to start, 0 to stop.
     * Response arrives as 0x3F notification.
     */
    fun makeSpO2StartCommand(): ByteArray = hexToBytes("3e01000000000000000000000000000000000000")
    fun makeSpO2StopCommand(): ByteArray = hexToBytes("3e00000000000000000000000000000000000000")

    fun makeFindRingCommand(): ByteArray = hexToBytes("040a000000000000000000000000000000000000")

    /**
     * App identity (0x48). The ring binds to the appId of the connecting phone/app and
     * streams data to it; without this the ring can stay mute (e.g. after another app —
     * the official one — claimed it). Mirrors the official SDK's setAppId
     * (BluetoothLeService.l): 0x48 followed by up to 18 ASCII bytes of the id.
     */
    fun makeAppIdCommand(appId: String): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x48
        val bytes = appId.toByteArray(Charsets.US_ASCII)
        for (i in bytes.indices) {
            if (i >= 18) break
            cmd[i + 1] = bytes[i]
        }
        return cmd
    }

    /**
     * User info / personal data (0x02). Feeds the ring's BP, blood-sugar and
     * calorie algorithms. Mirrors the official SDK's setUserInfo (BluetoothLeService.a0):
     *   byte[0] = 0x02
     *   byte[1] = age (low 7 bits) | 0x80 if male
     *   byte[2] = height (cm)
     *   byte[3] = weight (kg)
     *   byte[4] = unit flag (0 = metric, 1 = imperial)
     * We always transmit metric values with unit=0 so the ring interprets them
     * unambiguously, regardless of the app's display preference.
     */
    fun makeUserInfoCommand(
        ageYears: Int,
        isMale: Boolean,
        heightCm: Int,
        weightKg: Int,
    ): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x02
        val age = ageYears.coerceIn(0, 127)
        cmd[1] = (age or if (isMale) 0x80 else 0x00).toByte()
        cmd[2] = heightCm.coerceIn(0, 255).toByte()
        cmd[3] = weightKg.coerceIn(0, 255).toByte()
        cmd[4] = 0x00  // metric
        return cmd
    }

    /**
     * Ring-side bind / unbind (0x4B). Mirrors the official SDK's setBindedInfo
     * (BluetoothLeService.a(action, state, type)):
     *   byte[0] = 0x4B
     *   byte[1] = action (0=INIT, 1=APP_START, 2=ACK, 3=ACK_CANCEL, 4=SUCCESS,
     *             5=UNBOND, 6=UNBOND_ACK)
     *   byte[2] = state  (0=BOND_STATE_NO, 1=BOND_STATE_YES)
     *   byte[3] = type   (1, per the official app)
     * Binding the ring to this app keeps it streaming to us; unbinding on "Forget"
     * releases it so the ring re-advertises and other apps can find it again.
     */
    fun makeBindCommand(action: Int, state: Int = 0, type: Int = 1): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x4B
        cmd[1] = action.toByte()
        cmd[2] = state.toByte()
        cmd[3] = type.toByte()
        return cmd
    }

    /** Respond to the ring's INIT bind notification — begins binding (official: setBindedInfo(1,0,1)). */
    fun makeBindAppStartCommand(): ByteArray = makeBindCommand(action = 1)

    /** Confirm binding after the ring's ACK (official: setBindedInfo(4,0,1)). */
    fun makeBindSuccessCommand(): ByteArray = makeBindCommand(action = 4)

    /** Tell the ring to unbind on "Forget" (official: setBindedInfo(5,0,1)). */
    fun makeUnbindCommand(): ByteArray = makeBindCommand(action = 5)

    /**
     * Blood-pressure calibration (0x33). Sends a reference systolic/diastolic
     * (e.g. from a cuff) so the ring offsets its readings to match. Mirrors the
     * official SDK's setBPAdjust (BluetoothLeService.c): each value is a
     * little-endian u16.
     *   byte[0] = 0x33
     *   byte[1..2] = systolic (LE u16)
     *   byte[3..4] = diastolic (LE u16)
     */
    fun makeBPAdjustCommand(systolic: Int, diastolic: Int): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x33
        cmd[1] = (systolic and 0xFF).toByte()
        cmd[2] = ((systolic shr 8) and 0xFF).toByte()
        cmd[3] = (diastolic and 0xFF).toByte()
        cmd[4] = ((diastolic shr 8) and 0xFF).toByte()
        return cmd
    }

    /**
     * Daily step goal command (0x1a).
     * Protocol.md: `1a 10 27 00 00 …` sets a 10000-step goal.
     */
    fun makeGoalCommand(steps: Int): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x1a
        val value = maxOf(0, steps).toUInt()
        cmd[1] = (value and 0xFFu).toByte()
        cmd[2] = ((value shr 8) and 0xFFu).toByte()
        cmd[3] = ((value shr 16) and 0xFFu).toByte()
        cmd[4] = ((value shr 24) and 0xFFu).toByte()
        return cmd
    }

    /**
     * Automatic background heart-rate schedule (0x19).
     * Window 00:00–23:59, enable flag, cadence in minutes, mode 0x02.
     */
    fun makeAutomaticHeartRateCommand(
        enabled: Boolean,
        cadenceMinutes: Int = 30
    ): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x19
        cmd[1] = 0x00   // start HH
        cmd[2] = 0x00   // start MM
        cmd[3] = 0x17   // end HH (23)
        cmd[4] = 0x3B   // end MM (59)
        cmd[5] = if (enabled) 0x01 else 0x00
        cmd[6] = maxOf(1, cadenceMinutes).toByte()
        cmd[7] = 0x02
        return cmd
    }

    /**
     * Time sync command (0x01).
     * Payload: u32le epoch seconds + i8 timezone offset hours.
     */
    fun makeTimeSyncCommand(instant: Instant = Instant.now()): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x01
        val ts = instant.epochSecond.toUInt()
        cmd[1] = (ts and 0xFFu).toByte()
        cmd[2] = ((ts shr 8) and 0xFFu).toByte()
        cmd[3] = ((ts shr 16) and 0xFFu).toByte()
        cmd[4] = ((ts shr 24) and 0xFFu).toByte()
        val offsetHours = TimeZone.getDefault().getOffset(instant.toEpochMilli()).toInt() / 3_600_000
        cmd[5] = offsetHours.toByte()
        return cmd
    }

    // MARK: - Hex utilities

    /**
     * Convert a hex string (spaces ignored) to a [ByteArray].
     */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        require(clean.length % 2 == 0) { "Invalid hex string: $hex" }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Convert a [ByteArray] to a lowercase hex string.
     */
    fun ByteArray.toHexString(): String =
        joinToString("") { String.format("%02x", it) }
}

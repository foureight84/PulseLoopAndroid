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
    /**
     * Default user info (0x02 CMD_SET_USER_INFO). Hardcoded fallback values
     * (age 25, male, 184 cm, 90 kg, metric) sent on connect so the ring always
     * has a baseline profile to compute blood sugar and calories from. These are
     * overridden as soon as the user's real profile syncs from the database.
     *
     * NOT an activity query — the ring sends activity data (0x03) automatically
     * on connect without an explicit query. The 0x02 opcode was misinterpreted
     * during early reverse-engineering. See docs/protocol-discoveries.md.
     */
    fun makeDefaultUserInfoCommand(): ByteArray = hexToBytes("0299b85a00000000000000000000000000000000")
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
     * One-shot combined-sensor measurement (0x23). byte[1] is a *mode* selector, not a generic
     * start flag (vendor `setBloodPressureMode`/`setSpoMode` write 0x23 with a different byte[1]):
     * mode 1 = blood pressure, mode 2 = SpO₂. Confirmed on hardware that mode 2 already returns a
     * full 0x24 reply carrying HR + systolic + diastolic + SpO₂ + fatigue + stress + blood sugar
     * together — the mode byte selects the ring's primary algorithm, not which sensor runs, on
     * firmware without a "separate BP/SpO₂ mode" capability bit. Combined measurement therefore
     * rides the same command as [makeSpO2StartCommand].
     */
    fun makeCombinedMeasurementStart(): ByteArray = makeSpO2StartCommand()
    fun makeCombinedMeasurementStop(): ByteArray = makeSpO2StopCommand()

    /**
     * Spot SpO₂ (0x23 mode 2, matching the vendor's `setSpoMode`). Mode 1 is *blood pressure* —
     * sending byte[1] = 1 here silently ran a BP measurement instead, which is what this used to
     * do via the unrelated 0x3E toggle. The vendor app never sends 0x3E for SpO₂; every reading
     * goes through 0x23, and the result arrives in the 0x24 combined packet, not a 0x3F reply.
     */
    fun makeSpO2StartCommand(): ByteArray = hexToBytes("2302000000000000000000000000000000000000")
    fun makeSpO2StopCommand(): ByteArray = hexToBytes("2300000000000000000000000000000000000000")

    fun makeFindRingCommand(): ByteArray = hexToBytes("040a000000000000000000000000000000000000")

    /**
     * Capability bitmask query (0x20, vendor `getBandFunction`). The ring replies with a bit
     * array (see [JringBandCapabilities]) describing which sensors and offline history streams it
     * supports. Nothing branches on the reply yet — queried on connect so it's available once the
     * bit ordering is confirmed against real hardware.
     */
    fun makeBandFunctionCommand(): ByteArray = hexToBytes("2000000000000000000000000000000000000000")

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
     * User info / personal data (0x02 CMD_SET_USER_INFO). Feeds the ring's
     * blood-sugar (profile-derived estimate) and calorie algorithms. Blood
     * pressure is a direct PPG sensor reading and does NOT use user info.
     * Mirrors the official SDK's setUserInfo (BluetoothLeService.a0):
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
     * Automatic background heart-rate schedule (0x19) — the command that arms the ring's
     * continuous background sensor logging; without it the ring records almost nothing on
     * connect. Window 00:00–23:59, enable flag, cadence in minutes.
     *
     * byte[7] is a constant 0x01: the vendor SDK hardcodes it and ignores the caller's 7th
     * argument (the app's own `snooze` value, 2 — the source of an earlier 0x02 here).
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
        cmd[7] = 0x01
        return cmd
    }

    /**
     * Set the ring's clock (0x01). Payload: u32le epoch seconds holding **local wall-clock**
     * seconds (`utcEpoch + utcOffset`, matching the vendor SDK) + i8 offset in whole hours. The
     * ring's RTC therefore runs on local time, which its own wall-clock-keyed firmware logic
     * (day-indexed history queries, sleep/night-window detection) expects. Every ring-stamped
     * history timestamp must have the same offset subtracted back off on the way in — see
     * [JringClock.date], the matched half of this contract. Changing this without updating the
     * decoder shifts all history by the offset.
     */
    fun makeTimeSyncCommand(instant: Instant = Instant.now(), timeZone: TimeZone = TimeZone.getDefault()): ByteArray {
        val cmd = ByteArray(20)
        cmd[0] = 0x01
        val offsetSeconds = timeZone.getOffset(instant.toEpochMilli()) / 1000
        val ts = (instant.epochSecond + offsetSeconds).toUInt()
        cmd[1] = (ts and 0xFFu).toByte()
        cmd[2] = ((ts shr 8) and 0xFFu).toByte()
        cmd[3] = ((ts shr 16) and 0xFFu).toByte()
        cmd[4] = ((ts shr 24) and 0xFFu).toByte()
        cmd[5] = (offsetSeconds / 3600).toByte()
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

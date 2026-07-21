package com.pulseloop.ring

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * CRP ("crrepa" / CRPsmart) ring protocol — the family behind the Moyoung "Da Rings" app
 * (`com.moyoung.ring`), which is the OFFICIAL app for the CRP-firmware Colmi R11 and its siblings.
 * See `decompiled-moyoung-official/` at the iOS repo root; this file is a faithful port of that
 * app's on-the-wire behaviour (per AGENTS.md "match the vendor app").
 *
 * Why this family exists separately from [ColmiCoordinator]: the "R11 / SMART_RING" name is sold
 * under (at least) two different firmware stacks. One exposes the Colmi/QRing Nordic-UART profile
 * (`6e40fff0`/`de5bf728`) that [ColmiDriver] speaks; the other — this one — exposes a proprietary
 * `fdda` profile and speaks the CRP framing below. A CRP ring classified as JRING/Colmi finds none
 * of its characteristics and hangs the connect forever (issue #29, zaggash's ring).
 *
 * ## GATT topology (decompiled `k1/a.java`, `BleWriteCharacteristicProxy.getWriteCharacteristic`)
 * Service `fdda` with characteristics `fdd1`..`fdd6`:
 *   - **write**   → `fdd2` (default for all normal commands; `fdd5`/`fdd6` are OTA/recording only)
 *   - **notify**  → `fdd1` (current-steps push), `fdd3` (framed command replies), `fdd6` (recording)
 * Plus the standard services: `180f`/`2a19` battery, `180d`/`2a37` heart-rate, `180a` device info.
 *
 * ## Frame format (decompiled `b1/q.java`)
 * `FD DA 10 <len> <group> <cmd> <payload…>` where `len = payload.size + 6` (header included).
 * Responses use the identical header; the group is byte[4], the command byte[5], payload byte[6+].
 * A logical frame may span several notifications and is reassembled by total length — the top two
 * bits live in byte[2] (`0x10`), so length = `((byte[2] & 1) << 8) | byte[3]` (supports >255).
 */
object CRPUUIDs {
    // Proprietary CRP service + characteristics.
    const val SERVICE = "0000fdda-0000-1000-8000-00805f9b34fb"
    const val CHAR_STEPS_NOTIFY = "0000fdd1-0000-1000-8000-00805f9b34fb"   // current-steps push
    const val CHAR_WRITE = "0000fdd2-0000-1000-8000-00805f9b34fb"          // command write target
    const val CHAR_CMD_NOTIFY = "0000fdd3-0000-1000-8000-00805f9b34fb"     // framed command replies
    const val CHAR_RECORDING_NOTIFY = "0000fdd6-0000-1000-8000-00805f9b34fb" // OTA/recording (ignored in v1)

    // Standard GATT services reused by the ring.
    const val SERVICE_HEART_RATE = "0000180d-0000-1000-8000-00805f9b34fb"
    const val CHAR_HEART_RATE_MEASURE = "00002a37-0000-1000-8000-00805f9b34fb"
    const val SERVICE_BATTERY = "0000180f-0000-1000-8000-00805f9b34fb"
    const val CHAR_BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
}

/**
 * CRP command groups + subcommands (verified from the decompiled `b1` package builders).
 * Only the v1 subset is enumerated; the vendor SDK spans groups 1–10 with dozens of subcommands.
 */
object CRPCommands {
    // Group 1 — device config / measurement control.
    const val GROUP_DEVICE = 1
    const val CMD_SET_USER_INFO = 0     // b1/k.a: [height, weight, age, gender, strideLen]
    const val CMD_SET_TIME = 1          // b1/e.b: [epochSecondsLE(4), tzByte]
    const val CMD_MEASURE_HR = 9        // b1/t.d: [enable] — start(1)/stop(0) continuous HR
    const val CMD_MEASURE_SPO2 = 11     // b1/h.d: [enable] — start(1)/stop(0) SpO2

    // Group 1 — timing/enable controls (decompiled b1 package).
    const val CMD_ENABLE_TIMING_HR = 7        // b1/t.a: group1/cmd7
    const val CMD_DISABLE_TIMING_HR = 8       // b1/t.b: group1/cmd8
    const val CMD_ENABLE_TIMING_HRV = 9       // b1/u.a: group1/cmd9
    const val CMD_DISABLE_TIMING_HRV = 10     // b1/u.b: group1/cmd10
    const val CMD_ENABLE_TIMING_SPO2 = 11     // b1/h.a: group1/cmd11
    const val CMD_DISABLE_TIMING_SPO2 = 12    // b1/h.b: group1/cmd12
    const val CMD_ENABLE_TIMING_STRESS = 13   // b1/h0.a: group1/cmd13
    const val CMD_DISABLE_TIMING_STRESS = 14  // b1/h0.b: group1/cmd14
    const val CMD_ENABLE_TIMING_TEMP = 15     // b1/i0.a: group1/cmd15
    const val CMD_DISABLE_TIMING_TEMP = 16    // b1/i0.b: group1/cmd16

    // Group 2 — history queries (decompiled b1/e0).
    const val GROUP_HISTORY = 2
    const val CMD_QUERY_HISTORY_HR = 4        // b1/e0.a: group2/cmd4
    const val CMD_QUERY_HISTORY_STRESS = 5    // b1/e0.b: group2/cmd5
    const val CMD_QUERY_HISTORY_SLEEP = 14    // b1/e0.c: group2/cmd14 (CRPHistoryDay)
    const val CMD_QUERY_HISTORY_TEMP = 48     // b1/e0.d: group2/cmd48
    const val CMD_QUERY_HISTORY_HRV = 6       // b1/e0.e: group2/cmd6
    const val CMD_QUERY_HISTORY_SPO2 = 7      // b1/e0.f: group2/cmd7

    // Group 7 — device info / support.
    const val GROUP_DEVICE_INFO = 7
    const val CMD_QUERY_DEVICE_INFO = 0       // b1/r.a: group7/cmd0
    const val CMD_QUERY_FIRMWARE_VERSION = 1  // b1/r.b: group7/cmd1
    const val CMD_QUERY_DEVICE_SN = 13        // b1/r.c: group7/cmd13

    // Group 3 — power control.
    const val GROUP_POWER = 3
    const val CMD_FACTORY_RESET = 0     // b1/l.v: q.b(3,0)
    const val CMD_RESTART = 1           // b1/l.w: q.b(3,1)

    // Group 9 — device actions.
    const val GROUP_ACTION = 9
    const val CMD_FIND_DEVICE = 2       // b1/c0.c: [enable]
}

/**
 * Builds and parses CRP wire frames. Pure and side-effect free so the framing is unit-testable
 * without a BLE stack (see `CRPProtocolTest`).
 */
object CRPProtocol {
    private const val HEADER_0 = 0xFD.toByte()
    private const val HEADER_1 = 0xDA.toByte()
    private const val HEADER_2 = 0x10.toByte()
    const val HEADER_SIZE = 6

    /** Build a fully-framed CRP packet: `FD DA 10 <len> <group> <cmd> <payload>`. */
    fun frame(group: Int, cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = payload.size + HEADER_SIZE
        val out = ByteArray(total)
        out[0] = HEADER_0
        out[1] = HEADER_1
        out[2] = HEADER_2
        out[3] = total.toByte()
        out[4] = group.toByte()
        out[5] = cmd.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** True when [data] begins a CRP frame (`FD DA …`). */
    fun isFrameStart(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == HEADER_0 && data[1] == HEADER_1

    /**
     * Total declared length of a frame whose header is [data]. Mirrors the vendor's
     * `H(byte[2], byte[3])`: the length's 9th bit rides bit0 of byte[2] (`0x10`), so long
     * history frames (>255 bytes) decode correctly. Returns 0 if [data] is too short.
     */
    fun frameLength(data: ByteArray): Int {
        if (data.size < 4) return 0
        return ((data[2].toInt() and 0x01) shl 8) or (data[3].toInt() and 0xFF)
    }

    // ---- Command builders (v1 subset) ----

    /** Set the device clock. Vendor quirk (`b1/e.b`): the wall-clock components are encoded as if
     *  the zone were GMT+8, with a fixed tz byte of 8 — the ring then displays the correct local
     *  wall clock regardless of the phone's real timezone. Replicated verbatim so history stamps
     *  agree with what the vendor app would have written. */
    fun setTime(now: LocalDateTime = LocalDateTime.now()): ByteArray {
        val epoch = now.toEpochSecond(ZoneOffset.ofHours(8))
        val payload = byteArrayOf(
            (epoch and 0xFF).toByte(),
            ((epoch shr 8) and 0xFF).toByte(),
            ((epoch shr 16) and 0xFF).toByte(),
            ((epoch shr 24) and 0xFF).toByte(),
            8, // timezone byte (GMT+8), matching the vendor
        )
        return frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_SET_TIME, payload)
    }

    /** Push user anthropometrics so on-device step/calorie algorithms have real inputs.
     *  Layout from `b1/k.a`: [height(cm), weight(kg), age(yr), gender, strideLen(cm)]. */
    fun setUserInfo(heightCm: Int, weightKg: Int, ageYears: Int, gender: Int, strideCm: Int): ByteArray {
        val payload = byteArrayOf(
            heightCm.toByte(), weightKg.toByte(), ageYears.toByte(),
            gender.toByte(), strideCm.toByte(),
        )
        return frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_SET_USER_INFO, payload)
    }

    fun measureHeartRate(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_MEASURE_HR, byteArrayOf(if (enable) 1 else 0))

    fun measureSpO2(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_MEASURE_SPO2, byteArrayOf(if (enable) 1 else 0))

    fun findDevice(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_ACTION, CRPCommands.CMD_FIND_DEVICE, byteArrayOf(if (enable) 1 else 0))

    fun factoryReset(): ByteArray = frame(CRPCommands.GROUP_POWER, CRPCommands.CMD_FACTORY_RESET)

    // ---- Timing/enable commands (group 1) ----
    // The vendor app always sends an interval byte when enabling vital timing. We default to 5
    // minutes (common for Colmi/Moyoung rings) — verify against hardware and adjust if needed.

    fun enableTimingHeartRate(intervalMinutes: Int = 5): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_HR, byteArrayOf(intervalMinutes.toByte()))

    fun disableTimingHeartRate(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_DISABLE_TIMING_HR)

    fun enableTimingHRV(intervalMinutes: Int = 5): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_HRV, byteArrayOf(intervalMinutes.toByte()))

    fun disableTimingHRV(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_DISABLE_TIMING_HRV)

    fun enableTimingSpO2(intervalMinutes: Int = 5): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_SPO2, byteArrayOf(intervalMinutes.toByte()))

    fun disableTimingSpO2(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_DISABLE_TIMING_SPO2)

    fun enableTimingStress(intervalMinutes: Int = 5): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_STRESS, byteArrayOf(intervalMinutes.toByte()))

    fun disableTimingStress(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_DISABLE_TIMING_STRESS)

    fun enableTimingTemp(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_TEMP)

    fun disableTimingTemp(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_DISABLE_TIMING_TEMP)

    // ---- History query commands (group 2) ----

    fun queryHistoryHeartRate(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_HR)

    fun queryHistoryStress(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_STRESS)

    fun queryHistorySleep(daysAgo: Int = 0): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, byteArrayOf(daysAgo.toByte()))

    fun queryHistoryTemp(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_TEMP)

    fun queryHistoryHRV(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_HRV)

    fun queryHistorySpO2(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SPO2)

    // ---- Device info queries (group 7) ----

    fun queryDeviceInfo(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE_INFO, CRPCommands.CMD_QUERY_DEVICE_INFO)

    fun queryFirmwareVersion(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE_INFO, CRPCommands.CMD_QUERY_FIRMWARE_VERSION)

    fun queryDeviceSN(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE_INFO, CRPCommands.CMD_QUERY_DEVICE_SN)
}

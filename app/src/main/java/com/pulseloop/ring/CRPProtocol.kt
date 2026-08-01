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
    // Group 1 — spot (manual) measurement toggles (OUTBOUND). Each is `[enable]` — start(1)/stop(0)
    // a one-shot on-demand measurement. Source: the vendor SDK's `startMeasureX`/`stopMeasureX`
    // (`d1/b.java`) → the `b1` builders. The ring reports the reading back on the SAME cmd byte
    // (see CMD_RESULT_* below), which is why these opcodes double as the result opcodes.
    const val CMD_MEASURE_HR = 9        // b1/t.d:  q.c(1,9,  [enable])
    const val CMD_MEASURE_HRV = 10      // b1/u.d:  q.c(1,10, [enable])
    const val CMD_MEASURE_SPO2 = 11     // b1/h.d:  q.c(1,11, [enable])
    const val CMD_MEASURE_STRESS = 14   // b1/h0.d: q.c(1,14, [enable])
    const val CMD_MEASURE_TEMP = 32     // b1/i0.d: q.c(1,32, [enable])

    // Group 1 — real-time result reply opcodes (INBOUND). The cmd byte the ring puts on the framed
    // `fdd3` channel when it reports a live reading — identical to the measure opcode above (the
    // measurement echoes back on its own cmd). Source: vendor dispatcher `g1/a.java` lines 664–712
    // (case group==1): 9→onHeartRate, 10→onHrv, 11→onBloodOxygen, 14→onStressChange, 32→onMeasureComplete(temp).
    const val CMD_RESULT_HR = CMD_MEASURE_HR         // g1/a: onHeartRate(e1/f.b → payload[0])
    const val CMD_RESULT_HRV = CMD_MEASURE_HRV       // g1/a: onHrv(byte2int(payload[0]))
    const val CMD_RESULT_SPO2 = CMD_MEASURE_SPO2     // g1/a: onBloodOxygen(e1/d.b → payload[0])
    const val CMD_RESULT_STRESS = CMD_MEASURE_STRESS // g1/a: onStressChange(byte2int(payload[0]))
    const val CMD_RESULT_TEMP = CMD_MEASURE_TEMP     // g1/a: onMeasureComplete(e1/m.a → (payload[1]<<8|payload[0])/10)

    // Group 1 — timing/enable controls (decompiled b1 package).
    // Disable: HR/HRV/SpO2/Stress use enable with interval=0. Temp uses a separate cmd.
    const val CMD_ENABLE_TIMING_HR = 6        // b1/t.c: q.c(1,6, [interval])
    const val CMD_ENABLE_TIMING_HRV = 7       // b1/u.c: q.c(1,7, [interval])
    const val CMD_ENABLE_TIMING_SPO2 = 8      // b1/h.c: q.c(1,8, [interval])
    const val CMD_ENABLE_TIMING_STRESS = 39   // b1/h0.c: q.c(1,39, [interval])
    const val CMD_ENABLE_TIMING_TEMP = 13     // b1/i0.c: q.c(1,13, [enable]) — all-day temp timing on/off
    // NOTE: temp's spot-measure toggle is a DIFFERENT opcode (cmd 32, b1/i0.d) — see CMD_MEASURE_TEMP.

    // Group 7 is the vendor's **Gomore** group (the licensed activity-analytics module), NOT device
    // info — every builder in `b1/r` resolves to a Gomore call in `d1/b.java`:
    //   q.b(7,0)=querySupportGomore  q.b(7,1)=querySavedGomoreKey  q.b(7,2)=queryGomoreEUID
    //   q.c(7,3,str)=sendGomoreKey   q.b(7,13)=queryGomoreVersion
    // The earlier constants here paired `b1/r`'s methods with opcodes positionally (a→0, b→1, c→13)
    // and mislabelled all three as device info; `queryFirmwareVersion` was really
    // `querySavedGomoreKey`, which is why the R11 answered none of the 23 sends (issue #29).
    // Real device queries live on group 3 — see GROUP_POWER below. Kept only so a capture
    // containing these frames is still identifiable; nothing sends them.
    const val GROUP_GOMORE = 7
    const val CMD_QUERY_SUPPORT_GOMORE = 0    // b1/r.e: q.b(7,0)  → d1/b.querySupportGomore
    const val CMD_QUERY_SAVED_GOMORE_KEY = 1  // b1/r.d: q.b(7,1)  → d1/b.querySavedGomoreKey
    const val CMD_QUERY_GOMORE_VERSION = 13   // b1/r.c: q.b(7,13) → d1/b.queryGomoreVersion

    // Group 2 — the day's stored vital timelines. The all-day "timing" histories the vendor's sync
    // pass actually pulls (`u3/g1.java`) live here with a [day, 0] payload, NOT on group 7: the ring
    // returned empty for the old group-7 HR/SpO2/HRV/stress queries (issue #29). Replies are
    // multi-frame (`e1/f.i` reassembles by index into a CRPHeartRateInfo: startTime + list + interval).
    const val GROUP_HISTORY = 2
    const val CMD_QUERY_HISTORY_SLEEP = 14    // b1/e0.c: q.c(2,14, [CRPHistoryDay])
    const val CMD_QUERY_TIMING_HR = 15        // b1/t.b:  q.c(2,15, [day, 0])
    const val CMD_QUERY_TIMING_HRV = 16       // b1/u.b:  q.c(2,16, [day, 0])
    const val CMD_QUERY_TIMING_SPO2 = 17      // b1/h.b:  q.c(2,17, [day, 0])
    const val CMD_QUERY_TIMING_STRESS = 47    // b1/h0.b: q.c(2,47, [day, 0])
    /** Temperature history. **Not 48** — `q.b(2,48)` is the vendor's `querySleepState` (`d1/b.java`
     *  line 650); the real temperature history is `i0.b(day, frameIndex)` = `q.c(2,22, [day, idx])`,
     *  the same `[day, frameIndex]` shape as the other timing histories. We queried 48 for months and
     *  the ring never answered — see zaggash's 2026-07-25 capture, 23 sends and 0 replies. Its sample
     *  layout is still unconfirmed by a non-empty capture, so the reply stays an ack for now. */
    const val CMD_QUERY_HISTORY_TEMP = 22     // b1/i0.b: q.c(2,22, [day, frameIndex])
    const val HISTORY_DAY_TODAY = 0           // CRPHistoryDay.TODAY; YESTERDAY = 1

    // Group 2 — read-back queries. The ring can be *asked* what it supports and what is currently
    // enabled, so the app doesn't have to guess (vendor `d1/b.java` querySupport*/queryTiming*State).
    /** `b1/h.e`: q.b(2,37). Reply payload[0] is a `CRPBloodOxygenType`: 0 = NOT_SUPPORT,
     *  1 = SLEEP_OXYGEN, 2 = TIMING_OXYGEN (`g1/a.V0` → `onSupportBloodOxygenType`). The R11 has no
     *  SpO2 hardware at all — COLMI's spec lists one optical sensor, a Vcare VC30F heart-rate unit —
     *  so this is how a ring that *does* have it earns the capability back. */
    const val CMD_QUERY_SUPPORT_SPO2_TYPE = 37
    /** The all-day monitor state queries. Each reply carries the configured interval in minutes
     *  (`g1/a.{p1,r1,n1,t1}` → `onTimingInterval`); 0 means the monitor is off. */
    const val CMD_QUERY_TIMING_HR_STATE = 6       // b1/t.e:  q.b(2,6)
    const val CMD_QUERY_TIMING_HRV_STATE = 7      // b1/u.e:  q.b(2,7)
    const val CMD_QUERY_TIMING_SPO2_STATE = 8     // b1/h.f:  q.b(2,8)
    const val CMD_QUERY_TIMING_TEMP_STATE = 21    // b1/i0.a: q.b(2,21) → onTimingState(type, state)
    const val CMD_QUERY_TIMING_STRESS_STATE = 45  // b1/h0.e: q.b(2,45)

    // Group 3 — device control, identity queries, and device-state pushes. (Named GROUP_POWER from
    // when only the two power opcodes were known; the group is broader than the name.) Opcodes read
    // off the `b1/l` builders via their `d1/b.java` callers (the method letters are alphabetised by
    // the decompiler and carry no ordering, so each one is resolved by its caller, not by position).
    const val GROUP_POWER = 3
    const val CMD_FACTORY_RESET = 0     // b1/l.v: q.b(3,0)  → d1/b.reset
    const val CMD_SHUT_DOWN = 1         // b1/l.y: q.b(3,1)  → d1/b.shutDown (was mislabelled RESTART)
    // Firmware identity — the pair the vendor's own "Firmware information" screen shows
    // (`FirmwareInformationActivity`): version is a bare UTF-8 string, hash a hex code.
    const val CMD_QUERY_FIRMWARE_VERSION = 3  // b1/l.k: q.b(3,3) → d1/b.queryFirmwareVersion
    const val CMD_QUERY_FIRMWARE_HASH = 4     // b1/l.j: q.b(3,4) → d1/b.queryFirmwareHash
    const val CMD_QUERY_REALTIME_BATTERY = 6  // b1/l.f: q.b(3,6) → d1/b.queryRealTimeBattery
    // Autonomous wear-state push: vendor `g1/a.java` case 3→7 → onWearStateChange(payload[0] > 0).
    // payload[0] == 0 ⇒ ring not on finger / no skin contact (issue #29: an optical spot measure
    // returns nothing while this is 0; we surface it instead of spinning the full window).
    const val CMD_WEAR_STATE = 7
    const val CMD_RESTART = 14          // b1/l.w: q.b(3,14) → d1/b.restart

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

    // ---- Spot (manual) measurement toggles ----
    // start(true)/stop(false) an on-demand reading; the ring reports back on the same cmd byte,
    // decoded by CRPDecoder.decodeVitalResult. Mirrors the vendor's startMeasureX/stopMeasureX
    // (`d1/b.java` → `b1/t.d`,`u.d`,`h.d`,`h0.d`,`i0.d`).

    fun measureHeartRate(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_MEASURE_HR, byteArrayOf(if (enable) 1 else 0))

    fun measureHRV(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_MEASURE_HRV, byteArrayOf(if (enable) 1 else 0))

    fun measureSpO2(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_MEASURE_SPO2, byteArrayOf(if (enable) 1 else 0))

    fun measureStress(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_MEASURE_STRESS, byteArrayOf(if (enable) 1 else 0))

    fun measureTemp(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_MEASURE_TEMP, byteArrayOf(if (enable) 1 else 0))

    fun findDevice(enable: Boolean): ByteArray =
        frame(CRPCommands.GROUP_ACTION, CRPCommands.CMD_FIND_DEVICE, byteArrayOf(if (enable) 1 else 0))

    fun factoryReset(): ByteArray = frame(CRPCommands.GROUP_POWER, CRPCommands.CMD_FACTORY_RESET)

    // ---- Timing/enable commands (group 1) ----
    // HR/HRV/SpO2/Stress disable by sending enable with interval=0 (per d1/b.java disable* methods).
    // Temp disable uses a separate cmd (32) with [false] (per b1/i0.d and d1/b.java disableTimingTemp).
    // The vendor app always sends an interval byte when enabling vital timing.

    fun enableTimingHeartRate(intervalMinutes: Int): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_HR, byteArrayOf(intervalMinutes.toByte()))

    fun disableTimingHeartRate(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_HR, byteArrayOf(0))

    fun enableTimingHRV(intervalMinutes: Int): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_HRV, byteArrayOf(intervalMinutes.toByte()))

    fun disableTimingHRV(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_HRV, byteArrayOf(0))

    fun enableTimingSpO2(intervalMinutes: Int): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_SPO2, byteArrayOf(intervalMinutes.toByte()))

    fun disableTimingSpO2(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_SPO2, byteArrayOf(0))

    fun enableTimingStress(intervalMinutes: Int): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_STRESS, byteArrayOf(intervalMinutes.toByte()))

    fun disableTimingStress(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_STRESS, byteArrayOf(0))

    fun enableTimingTemp(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_TEMP, byteArrayOf(1))

    fun disableTimingTemp(): ByteArray =
        frame(CRPCommands.GROUP_DEVICE, CRPCommands.CMD_ENABLE_TIMING_TEMP, byteArrayOf(0))

    // ---- History query commands (all group 2; see GROUP_HISTORY) ----
    // The all-day vital timelines take a [day, frameIndex] payload (vendor `b1/{t,u,h,h0}.b`); sleep
    // takes [day]; temp takes none. `day` is CRPHistoryDay (0 = today). A day's 5-minute timeline is
    // split across several 144-slot frames selected by `frameIndex`: the vendor requests index 0,
    // then pulls the next index on each reply until the terminal frame (HR/SpO2/stress → index 1,
    // HRV → index 3), reassembling a full 288-slot (24 h × 5-min) day. See `e1/{f,d,g,l}.java` and
    // [CRPSyncEngine]'s follow-up on [RingDecodedEvent.TimingHistoryFrame].

    fun queryTimingHeartRateHistory(day: Int = CRPCommands.HISTORY_DAY_TODAY, frameIndex: Int = 0): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_HR, byteArrayOf(day.toByte(), frameIndex.toByte()))

    fun queryTimingHrvHistory(day: Int = CRPCommands.HISTORY_DAY_TODAY, frameIndex: Int = 0): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_HRV, byteArrayOf(day.toByte(), frameIndex.toByte()))

    fun queryTimingSpO2History(day: Int = CRPCommands.HISTORY_DAY_TODAY, frameIndex: Int = 0): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_SPO2, byteArrayOf(day.toByte(), frameIndex.toByte()))

    fun queryTimingStressHistory(day: Int = CRPCommands.HISTORY_DAY_TODAY, frameIndex: Int = 0): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_STRESS, byteArrayOf(day.toByte(), frameIndex.toByte()))

    fun queryHistorySleep(daysAgo: Int = 0): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, byteArrayOf(daysAgo.toByte()))

    fun queryHistoryTemp(day: Int = CRPCommands.HISTORY_DAY_TODAY, frameIndex: Int = 0): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_TEMP,
            byteArrayOf(day.toByte(), frameIndex.toByte()))

    // ---- Read-back queries: let the ring tell us what it supports and what is enabled ----

    /** Ask whether this unit has SpO2 hardware at all. See [CRPCommands.CMD_QUERY_SUPPORT_SPO2_TYPE]. */
    fun querySupportSpO2Type(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_SUPPORT_SPO2_TYPE)

    fun queryTimingHeartRateState(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_HR_STATE)

    fun queryTimingHrvState(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_HRV_STATE)

    fun queryTimingSpO2State(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_SPO2_STATE)

    fun queryTimingStressState(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_STRESS_STATE)

    fun queryTimingTempState(): ByteArray =
        frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_TEMP_STATE)

    // ---- Device identity queries (group 3) ----
    // `queryDeviceInfo`/`queryDeviceSN` are gone: they framed group-7 Gomore opcodes, which the ring
    // never answers. Firmware version is the one the vendor's Firmware-information screen reads.

    fun queryFirmwareVersion(): ByteArray =
        frame(CRPCommands.GROUP_POWER, CRPCommands.CMD_QUERY_FIRMWARE_VERSION)
}

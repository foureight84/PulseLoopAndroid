package com.pulseloop.ring

import java.time.Instant
import java.time.ZoneId

/**
 * Ported from LuckRingEncoder.swift (iOS #90).
 *
 * Builds *logical* LuckRing frames -- the connect bundle, the clock, history requests, the real-time
 * toggles, and the device actions. It owns the rolling `seq` counter (the head packet's `[3]`), so
 * each frame it hands out carries the next sequence number; [LuckRingPacketizer] stamps it into the
 * wire bytes.
 *
 * The connect bundle is **parameterized**, not a captured replay: it reproduces the exact property
 * set and order of `K6SendDataManager.sendAynInfoDetail()` -- the method the vendor app runs on
 * connect -- with every field built from the SDK's own struct byte layouts (`K6_SendUserInfo`,
 * `K6_CESyncTime`, `K6_SendGoal`, `K6_MixInfoStruct`), confirmed directly against the decompiled
 * `decompiled-coolring/` sources at the repo root.
 */
class LuckRingEncoder {
    /** The rolling per-frame sequence. `queue/b.java` writes it to head `[3]` and the ring echoes it
     *  in its ACK; only monotonic-ish uniqueness matters, so a plain wrapping `UByte` is enough. */
    private var seq: UByte = 0u

    private fun nextSeq(): UByte {
        val value = seq
        seq = (seq + 1u).toUByte()
        return value
    }

    private fun frame(cmdType: LuckRingCmdType, dataType: UByte, payload: List<UByte>): LuckRingFrame =
        LuckRingFrame(cmdType, dataType, payload, nextSeq())

    companion object {
        // MARK: - Struct byte layouts (each matches its `K6_*` entity exactly)

        /**
         * `K6_SendUserInfo.getBytes()` -- 9 bytes: `[userId u32 LE][sex][age][height cm][weight kg][reserved]`.
         *
         * **Sex is inverted on the wire.** `sendAynInfoDetail()` sends `(appSex == 1) ? 0 : 1`, where
         * the app's `1` is male -- so the ring's sex byte is `0 = male, 1 = otherwise`.
         * `UserProfileValues.gender` is `1 = male` (the Colmi convention this app already uses), hence
         * `gender == 1u -> 0u else 1u`. Age floors at the vendor's default of 20 when unset.
         */
        fun userInfoBytes(profile: UserProfileValues, userId: Long = 0): List<UByte> {
            val bytes = LuckRingBytes.le32(userId).toMutableList()
            bytes.add(if (profile.gender == 1u.toUByte()) 0u else 1u)
            bytes.add(if (profile.age < 1u.toUByte()) 20u.toUByte() else profile.age)
            bytes.add(profile.heightCm)
            bytes.add(profile.weightKg)
            bytes.add(0u)                     // reserved
            return bytes
        }

        /**
         * `K6_CESyncTime.getBytes()` -- 9 bytes: `[absSeconds u32 LE][utcOffsetSeconds u32 LE][formatByte]`.
         *
         * `absSeconds` is the **true UTC** Unix epoch, not local wall-clock -- so unlike the
         * jring/YCBT clocks, every ring-stamped record decodes with no offset to un-apply. The format
         * byte is `(timeDisplay xor (dateDisplay shl 1))`; both displays default to 0, so it is 0.
         */
        fun timeBytes(date: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<UByte> {
            val bytes = LuckRingBytes.le32(date.epochSecond).toMutableList()
            val offsetSeconds = zone.rules.getOffset(date).totalSeconds.toLong()
            bytes.addAll(LuckRingBytes.le32(offsetSeconds and 0xFFFFFFFFL))
            bytes.add(0u)                     // format byte (12/24h xor date order << 1); both default 0
            return bytes
        }

        /**
         * `K6_SendGoal.getBytes()` -- 16 bytes: `[step u32][distance u32][calories u32][sleep u16][duration u16]`,
         * all LE. PulseLoop only tracks a step goal; the other four are 0.
         */
        fun goalBytes(steps: Int): List<UByte> {
            val bytes = LuckRingBytes.le32(maxOf(0, steps).toLong()).toMutableList()
            bytes.addAll(LuckRingBytes.le32(0))   // distance
            bytes.addAll(LuckRingBytes.le32(0))   // calories
            bytes.addAll(LuckRingBytes.le16(0))   // sleep
            bytes.addAll(LuckRingBytes.le16(0))   // duration
            return bytes
        }
    }

    // MARK: - Connect bundle (MixInfo 110)

    /**
     * The binding / startup bundle, in `sendAynInfoDetail()`'s exact property order:
     * 102 user info -> 104 time -> 124 call-alarm -> 103 language -> 109 data-switch -> 111 goals -> 120 pair.
     *
     * - `109 data-switch = 1` is what enables the ring's real-time pushes.
     * - `120 pair = {firstPair ? 1 : 0, 0}`: the leading 1 asks the ring to run its pairing animation.
     *   **Deliberately always sent as `{0,0}` here** -- the ring/driver layer is intentionally
     *   Context-free (no persisted-flag seam exists at this layer, unlike `UserProfileValues`/
     *   `MeasurementSettings` which the app layer seeds via `setUserProfile`/`setMeasurementSettings`
     *   before `runStartup`), and the cosmetic cost of skipping the ring's one-time pairing flash is
     *   far smaller than the cost of replaying it on every reconnect. Revisit only if a real TK18
     *   shows this animation matters to users.
     * - `124 call-alarm = {1, 0xFF, 0xFF, 0, 0}` is the vendor's literal constant.
     */
    fun startupBundle(
        profile: UserProfileValues,
        goalSteps: Int,
        date: Instant = Instant.now(),
        languageCode: UByte = 0u,
    ): LuckRingFrame {
        val properties = listOf(
            LuckRingMixInfoTLV.Property(LuckRingDataType.USER_INFO, userInfoBytes(profile)),
            LuckRingMixInfoTLV.Property(LuckRingDataType.TIME, timeBytes(date)),
            LuckRingMixInfoTLV.Property(LuckRingDataType.CALL_ALARM, listOf<UByte>(1u, 0xFFu, 0xFFu, 0u, 0u)),
            LuckRingMixInfoTLV.Property(LuckRingDataType.LANGUAGE, listOf(languageCode)),
            LuckRingMixInfoTLV.Property(LuckRingDataType.DATA_SWITCH, listOf<UByte>(1u)),
            LuckRingMixInfoTLV.Property(LuckRingDataType.GOALS, goalBytes(goalSteps)),
            LuckRingMixInfoTLV.Property(LuckRingDataType.PAIR_FINISH, listOf<UByte>(0u, 0u)),
        )
        return frame(LuckRingCmdType.SEND, LuckRingDataType.MIX_INFO, LuckRingMixInfoTLV.encode(properties))
    }

    // MARK: - Standalone settings

    /** Push the ring clock on its own (`104`, the live timezone-change path). */
    fun setTime(date: Instant = Instant.now()): LuckRingFrame =
        frame(LuckRingCmdType.SEND, LuckRingDataType.TIME, timeBytes(date))

    /** Push the user's profile on its own (`102`). */
    fun userInfo(profile: UserProfileValues): LuckRingFrame =
        frame(LuckRingCmdType.SEND, LuckRingDataType.USER_INFO, userInfoBytes(profile))

    /** Set the step goal on its own (`111`). */
    fun setGoal(steps: Int): LuckRingFrame =
        frame(LuckRingCmdType.SEND, LuckRingDataType.GOALS, goalBytes(steps))

    /** Enable/disable the real-time push data-switch (`109`). */
    fun dataSwitch(on: Boolean): LuckRingFrame =
        frame(LuckRingCmdType.SEND, LuckRingDataType.DATA_SWITCH, listOf(if (on) 1u else 0u))

    /**
     * Auto-monitoring config (`128`, `K6_DATA_TYPE_HEART_AUTO_SWITCH` -- 8 bytes:
     * `[autoHR][hr24h][interval min][autoO2][0x4]`). This is what makes the ring log HR/SpO2 history
     * **on its own**: the firmware default is *off* (`new K6_DATA_TYPE_HEART_AUTO_SWITCH(0, 0, 30)`),
     * so a ring that never visited the vendor app's monitoring screen records nothing between syncs
     * until this is sent. `hr24h` (continuous mode) stays 0, matching the vendor default.
     */
    fun autoMonitoring(settings: MeasurementSettings): LuckRingFrame = frame(
        LuckRingCmdType.SEND, LuckRingDataType.HEART_AUTO_SWITCH,
        listOf(
            if (settings.hrEnabled) 1u else 0u,
            0u,
            settings.hrIntervalMinutes.coerceIn(0, 255).toUByte(),
            if (settings.spo2Enabled) 1u else 0u,
            0u, 0u, 0u, 0u,
        ),
    )

    // MARK: - Requests (cmdType REQUEST, empty payload)

    /** Ask the ring for a data type: a bare `REQUEST` with no payload (`new CEDevData(3, dataType)`).
     *  Used for device info (2), battery (3), settings-sync (9), and every history stream. */
    fun request(dataType: UByte): LuckRingFrame = frame(LuckRingCmdType.REQUEST, dataType, emptyList())

    // MARK: - Real-time toggles (each is its own `K6_DATA_TYPE_REAL_*` send)

    /** Real HR toggle (`24`, `K6_DATA_TYPE_REAL_HR` -- 1 payload byte). The stream itself comes back
     *  on dataType 7. */
    fun realHeartRate(on: Boolean): LuckRingFrame =
        frame(LuckRingCmdType.SEND, LuckRingDataType.REAL_HR, listOf(if (on) 1u else 0u))

    /** Real SpO2 toggle (`20`, `K6_DATA_TYPE_REAL_O2` -- `[on,0,0,0,0]`). */
    fun realSpO2(on: Boolean): LuckRingFrame =
        frame(LuckRingCmdType.SEND, LuckRingDataType.REAL_O2, listOf(if (on) 1u else 0u, 0u, 0u, 0u, 0u))

    /** Real HRV toggle (`45`, `K6_DATA_TYPE_REAL_HRV` -- 1 payload byte). */
    fun realHRV(on: Boolean): LuckRingFrame =
        frame(LuckRingCmdType.SEND, LuckRingDataType.REAL_HRV, listOf(if (on) 1u else 0u))

    /** Real blood-pressure toggle (`18`, `K6_DATA_TYPE_REAL_BP` -- `[on,0,0,0,0,0]`). */
    fun realBloodPressure(on: Boolean): LuckRingFrame = frame(
        LuckRingCmdType.SEND, LuckRingDataType.REAL_BP,
        listOf(if (on) 1u else 0u, 0u, 0u, 0u, 0u, 0u),
    )

    /** Real temperature toggle (`46`, `K6_DATA_TYPE_REAL_TEMP` -- 1 payload byte). */
    fun realTemperature(on: Boolean): LuckRingFrame =
        frame(LuckRingCmdType.SEND, LuckRingDataType.REAL_TEMP, listOf(if (on) 1u else 0u))

    // MARK: - Device actions

    /** Buzz the ring (`11`, `K6_DATA_TYPE_FIND_PHONE_OR_DEVICE` -- 1 payload byte). */
    fun findDevice(): LuckRingFrame = frame(LuckRingCmdType.SEND, LuckRingDataType.FIND_DEVICE, listOf<UByte>(1u))

    /** Release the ring on Forget (`159`, `sendUnbind` -- 1 payload byte). */
    fun unbind(): LuckRingFrame = frame(LuckRingCmdType.SEND, LuckRingDataType.UNBIND, listOf<UByte>(1u))
}

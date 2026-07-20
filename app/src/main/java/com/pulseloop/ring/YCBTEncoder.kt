package com.pulseloop.ring

import java.time.Instant

/**
 * Ported from YCBTEncoder.swift (iOS #82).
 * Builds *logical* YCBT commands — `[type, cmd, payload...]` without the length field or CRC,
 * which `YCBTDriver.frame(_)` appends.
 *
 * The connect handshake is **parameterized**, not a captured byte replay: it mirrors the order the
 * SmartHealth app actually runs (`HomeFragment.getCompile` -> `syncSettingData`), with every
 * payload built from the SDK's own definitions and the user's real settings. The Setting-group
 * builders live in [YCBTSettingsEncoder], shared with the other YCBT families.
 */
object YCBTEncoder {
    /** Set the ring clock (`01 00`), including the Mon=0 weekday byte. */
    fun setTime(instant: Instant = Instant.now()): List<UByte> = YCBTSettingsEncoder.setTime(instant)

    /**
     * The connect handshake, in the SmartHealth app's own order: clock -> device interrogation ->
     * locale -> all-day monitors -> user profile -> live-status stream.
     *
     * **Never add these to it** — each was once here, and each was a different kind of wrong:
     *   - **No `05 xx`.** The Health group is the *history* protocol: [YCBTHistoryTransfer] owns
     *     those queries and a stray one here would race it. Worse, `05 40..4E` are the Health
     *     **Delete** opcodes — they erase the ring's stored log. The five `01 xx {enable, interval}`
     *     monitors below are what actually makes the ring *record* between syncs.
     *   - **No `04 xx`.** Group 4 is DevControl, the *device->app* push channel. The app's only
     *     legitimate `04` write is an ACK for a push it received (`YCBTDriver.acknowledgePush`).
     */
    fun startupSequence(
        instant: Instant = Instant.now(),
        measurement: MeasurementSettings = MeasurementSettings.ALL_ON_DEFAULT,
        profile: UserProfileValues = UserProfileValues(metric = true, gender = 0x02u, age = 0u, heightCm = 0u, weightKg = 0u),
        languageCode: UByte = 0u,
        is24Hour: Boolean = true,
    ): List<List<UByte>> {
        val seq = mutableListOf<List<UByte>>()
        seq.add(setTime(instant))
        // Device interrogation. The 2-byte tags are cosmetic (the firmware ignores the payload of
        // a Get) but we keep the app's exact bytes: they cost nothing and keep a byte-diff against
        // a capture clean.
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO, listOf(0x47u, 0x43u)))
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_SUPPORT_FUNCTION, listOf(0x47u, 0x46u)))
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_CHIP_SCHEME, emptyList()))
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_DEVICE_NAME, listOf(0x47u, 0x50u)))
        seq.add(logical(YCBTGroup.GET, YCBTCommand.GET_USER_CONFIG, listOf(0x43u, 0x46u)))
        seq.add(YCBTSettingsEncoder.language(languageCode))
        seq.add(YCBTSettingsEncoder.units(metric = profile.metric, is24Hour = is24Hour))
        seq.addAll(YCBTSettingsEncoder.monitorCommands(measurement))
        seq.add(YCBTSettingsEncoder.userInfo(profile))
        seq.add(enableLiveStatus())
        return seq
    }

    /** Re-push the all-day monitors without the rest of the handshake (the live "Save" path). */
    fun monitorCommands(measurement: MeasurementSettings): List<List<UByte>> =
        YCBTSettingsEncoder.monitorCommands(measurement)

    /** Push the user's real height/weight/sex/age (`01 03`). */
    fun userInfo(profile: UserProfileValues): List<UByte> = YCBTSettingsEncoder.userInfo(profile)

    /** Read device info (`02 00`) — battery and firmware come back in the reply. */
    fun deviceInfoRequest(): List<UByte> = logical(YCBTGroup.GET, YCBTCommand.GET_DEVICE_INFO, listOf(0x47u, 0x43u))

    /**
     * Enable the ring's **live status auto-push** (`03 09 01 00 02`). Once sent, the ring streams
     * `06 00` status frames (current step count / distance / calories) on be940003 continuously
     * while connected. Without it the app only sees the one-time history dump, so today's live
     * step count never updates. (`03 09 00 00 02` disables it.)
     */
    fun enableLiveStatus(): List<UByte> = logical(YCBTGroup.APP_CONTROL, YCBTCommand.LIVE_STATUS_PUSH, listOf(0x01u, 0x00u, 0x02u))

    // MARK: - Health history

    /** Ask for one history type: `05 <queryKey>`, empty payload. */
    fun healthHistoryRequest(type: YCBTHistoryType): List<UByte> = YCBTHealthCommand.historyRequest(type)

    /** The mandatory end-of-transfer ACK: `05 80 {00}` accepted / `{04}` CRC failure. */
    fun historyBlockAck(status: UByte): List<UByte> = YCBTHealthCommand.historyBlockAck(status)

    // MARK: - Live actions

    /**
     * Live measurement start/stop via `03 2f` with a `[enable:1][mode:1]` payload. The **mode byte
     * selects the sensor**: 0x00 heart rate (green LED) -> `06 01` stream, 0x01 blood pressure ->
     * `06 03`, 0x02 SpO2 (red/IR LED) -> `06 02`, 0x0a HRV -> `06 03`. Using the wrong mode lights
     * the wrong LED and yields no reading, so each metric must use its own start.
     *
     * **The stop echoes its own mode** — it is not mode-agnostic. Stopping an SpO2 sweep with mode
     * 0 tells the ring to stop *heart rate*, leaving the SpO2 sweep running.
     */
    fun heartRateStart(): List<UByte> = liveMeasurement(enable = true, mode = YCBTMeasurementMode.HEART_RATE)
    fun heartRateStop(): List<UByte> = liveMeasurement(enable = false, mode = YCBTMeasurementMode.HEART_RATE)
    fun spo2Start(): List<UByte> = liveMeasurement(enable = true, mode = YCBTMeasurementMode.SPO2)
    fun spo2Stop(): List<UByte> = liveMeasurement(enable = false, mode = YCBTMeasurementMode.SPO2)
    fun hrvStart(): List<UByte> = liveMeasurement(enable = true, mode = YCBTMeasurementMode.HRV)
    fun hrvStop(): List<UByte> = liveMeasurement(enable = false, mode = YCBTMeasurementMode.HRV)
    fun bloodPressureStart(): List<UByte> = liveMeasurement(enable = true, mode = YCBTMeasurementMode.BLOOD_PRESSURE)
    fun bloodPressureStop(): List<UByte> = liveMeasurement(enable = false, mode = YCBTMeasurementMode.BLOOD_PRESSURE)

    /**
     * Find device — make the ring buzz (`03 00`), with the exact three payload bytes SmartHealth's
     * own "find ring" button sends (`appFindDevice(1, 5, 2)`).
     *
     * **UNVERIFIED:** the SDK never names those three arguments, so replaying the app's literal
     * values is the only way to be sure of the ring's response.
     */
    fun findDevice(): List<UByte> = logical(YCBTGroup.APP_CONTROL, YCBTCommand.FIND_DEVICE, listOf(0x01u, 0x05u, 0x02u))

    // MARK: - Helpers

    private fun liveMeasurement(enable: Boolean, mode: UByte): List<UByte> =
        logical(YCBTGroup.APP_CONTROL, YCBTCommand.LIVE_MEASUREMENT, listOf(if (enable) 1u else 0u, mode))

    private fun logical(group: UByte, cmd: UByte, payload: List<UByte>): List<UByte> =
        listOf(group, cmd) + payload
}

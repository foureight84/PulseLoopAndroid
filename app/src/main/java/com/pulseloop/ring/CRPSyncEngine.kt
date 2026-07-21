package com.pulseloop.ring

/**
 * Per-connection orchestration for a CRP ("crrepa") ring. Ported in spirit from the Moyoung
 * "Da Rings" connect flow (`d1/b.java` + `b1` package builders): after the link is up the app sets the
 * clock and pushes user anthropometrics, then the ring streams current steps (`fdd1`) on its own
 * and answers measurement commands. The bulk-history state machine stays deferred for now.
 *
 * Scope: clock + user-info handshake, spot HR + SpO2 (Measure button), all-day vital timing
 * enable/disable driven by [MeasurementSettings], find-device, factory reset. Steps and battery
 * arrive as autonomous pushes/reads (see [CRPDriver]). HRV / stress / temperature are all-day
 * metrics — their timing is enabled here and live results decode via [CRPDecoder], but pulling the
 * stored day timeline (group-7/group-2 history queries) is still TODO pending a hardware capture.
 */
class CRPSyncEngine(private val writer: RingCommandWriter?) : RingSyncEngine {

    private var profile: UserProfileValues? = null

    /** User-chosen all-day measurement config. Applied in the connect handshake and updatable
     *  live via [applyMeasurementSettings]. `null` ⇒ the user has never saved one, so the engine
     *  skips the vital enable commands (the ring's own settings are the source of truth). */
    private var measurementSettings: MeasurementSettings? = null

    override fun runStartup() {
        // Set the device clock first (matches the vendor's connect handshake), then user info so
        // the ring's step/calorie algorithm has real inputs.
        send(CRPProtocol.setTime())
        // Query firmware version so the UI doesn't show "Firmware: reading" (zaggash's report).
        send(CRPProtocol.queryFirmwareVersion())
        profile?.let { send(userInfoFrame(it)) }
        // Enable vital monitoring only when the user has configured it (mirrors the vendor app's
        // connect flow). Uses the user's polling interval for all vital types — the CRP protocol
        // takes a single interval byte per enable command, and MeasurementSettings only exposes
        // hrIntervalMinutes (no per-vital intervals), so we share it across the board.
        measurementSettings?.let { settings ->
            if (settings.hrEnabled) send(CRPProtocol.enableTimingHeartRate(settings.hrIntervalMinutes))
            if (settings.hrvEnabled) send(CRPProtocol.enableTimingHRV(settings.hrIntervalMinutes))
            if (settings.stressEnabled) send(CRPProtocol.enableTimingStress(settings.hrIntervalMinutes))
            if (settings.spo2Enabled) send(CRPProtocol.enableTimingSpO2(settings.hrIntervalMinutes))
            if (settings.temperatureEnabled) send(CRPProtocol.enableTimingTemp())
        }
    }

    override fun handle(event: RingDecodedEvent) {
        // Steps/HR/battery are persisted by RingBLEClient via RingEventBridge; v1 keeps no
        // engine-side state (no staged history pipeline to advance).
    }

    // ---- Spot (manual) measurements. Trigger via the fdda command channel; the ring replies on
    // the same group-1/cmd, decoded by CRPDecoder.decodeVitalResult and persisted via the bridge.
    // HR and SpO2 are the app's spot-measurable vitals (MANUAL_* capabilities); HRV/stress/temp are
    // all-day metrics pulled through timing + history, not the Measure button. ----
    override fun startHeartRate() { send(CRPProtocol.measureHeartRate(true)) }
    override fun stopHeartRate() { send(CRPProtocol.measureHeartRate(false)) }

    override fun startSpO2() { send(CRPProtocol.measureSpO2(true)) }
    override fun stopSpO2() { send(CRPProtocol.measureSpO2(false)) }

    override fun findDevice() { send(CRPProtocol.findDevice(true)) }

    override fun factoryReset() { send(CRPProtocol.factoryReset()) }

    override fun powerOff() {
        // No confirmed shut-down opcode in the v1 subset (the vendor uses a callback-gated
        // notification write). Left unsupported rather than sending a guessed command.
    }

    override fun setGoal(steps: Int) {
        // Step-goal command layout not yet confirmed from the decompile; no-op for now.
    }

    override fun setUserProfile(profile: UserProfileValues) { this.profile = profile }

    override fun applyUserProfile(profile: UserProfileValues) {
        this.profile = profile
        send(userInfoFrame(profile))
    }

    override fun setMeasurementSettings(settings: MeasurementSettings?) {
        measurementSettings = settings
    }

    override fun applyMeasurementSettings(settings: MeasurementSettings) {
        measurementSettings = settings
        // Re-send vital enable/disable commands with the updated settings.
        if (settings.hrEnabled) send(CRPProtocol.enableTimingHeartRate(settings.hrIntervalMinutes))
        else send(CRPProtocol.disableTimingHeartRate())
        if (settings.hrvEnabled) send(CRPProtocol.enableTimingHRV(settings.hrIntervalMinutes))
        else send(CRPProtocol.disableTimingHRV())
        if (settings.stressEnabled) send(CRPProtocol.enableTimingStress(settings.hrIntervalMinutes))
        else send(CRPProtocol.disableTimingStress())
        if (settings.spo2Enabled) send(CRPProtocol.enableTimingSpO2(settings.hrIntervalMinutes))
        else send(CRPProtocol.disableTimingSpO2())
        if (settings.temperatureEnabled) send(CRPProtocol.enableTimingTemp())
        else send(CRPProtocol.disableTimingTemp())
    }

    override fun resyncTime() { send(CRPProtocol.setTime()) }

    /** Map the app's [UserProfileValues] onto the CRP user-info payload. Stride length isn't
     *  carried by the profile, so estimate it from height (~0.43·height, a common default). */
    private fun userInfoFrame(p: UserProfileValues): ByteArray {
        val heightCm = p.heightCm.toInt()
        val strideCm = (heightCm * 0.43).toInt().coerceIn(0, 255)
        return CRPProtocol.setUserInfo(
            heightCm = heightCm,
            weightKg = p.weightKg.toInt(),
            ageYears = p.age.toInt(),
            gender = p.gender.toInt(),
            strideCm = strideCm,
        )
    }

    private fun send(frame: ByteArray?) {
        if (frame != null) writer?.enqueue(frame)
    }
}

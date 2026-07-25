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
 * metrics — their timing is enabled here and live results decode via [CRPDecoder]. Of the stored
 * day timelines, sleep (group-2/cmd-14) is decoded ([CRPDecoder.decodeSleep], confirmed against a
 * hardware capture), and the group-2 all-day "timing" vital histories (HR/SpO2/HRV/stress) decode
 * into [RingDecodedEvent.HistoryMeasurement] samples (confirmed against zaggash's R11 rc2 capture);
 * their multi-frame replies reassemble via the next-frame follow-up in [handle].
 */
class CRPSyncEngine(private val writer: RingCommandWriter?) : RingSyncEngine {

    private var profile: UserProfileValues? = null

    /** User-chosen all-day measurement config. Applied in the connect handshake and updatable
     *  live via [applyMeasurementSettings]. `null` ⇒ the user has never saved one; unlike QRing/YCBT
     *  the CRP ring exposes no way to read back its own config, so a fresh R11 ships with every
     *  all-day monitor OFF and never records anything to sync. We therefore fall back to
     *  [MeasurementSettings.ALL_ON_DEFAULT] (matching how [ColmiSyncEngine] force-enables on connect)
     *  so the day timeline actually accumulates. */
    private var measurementSettings: MeasurementSettings? = null

    override fun runStartup() {
        // Set the device clock first (matches the vendor's connect handshake), then user info so
        // the ring's step/calorie algorithm has real inputs.
        send(CRPProtocol.setTime())
        // Query firmware version so the UI doesn't show "Firmware: reading" (zaggash's report).
        send(CRPProtocol.queryFirmwareVersion())
        profile?.let { send(userInfoFrame(it)) }
        // Enable all-day vital monitoring. A fresh ring has these OFF, so without this the ring
        // stores no HR/SpO2/HRV/stress/temperature history and every history query below returns an
        // empty reply (issue #29, zaggash's full-day capture). When the user has saved a config we
        // honour it exactly (interval included); until then we fall back to ALL_ON_DEFAULT, whose
        // interval matches DeviceMeasurementConfigEntity's own default — i.e. the same app-wide
        // sampling cadence every other ring uses, not a CRP-specific value. The Measurement Settings
        // screen writes that config, and its interval flows straight back here via
        // RingSyncCoordinator's loadMeasurementSettings.
        applyTimingSettings(measurementSettings ?: MeasurementSettings.ALL_ON_DEFAULT)
        // Pull the day's stored all-day timeline. runStartup() IS the poll pass (RingSyncWorker's
        // ~30-min background sync and the foreground syncNow() both re-invoke it), so this runs at
        // the app's configured polling cadence; the ring samples at hrIntervalMinutes (above). The
        // ring only emits history replies once asked; CRPDecoder parses the group-2 timing frames
        // into HistoryMeasurement samples (confirmed against zaggash's R11 rc2 capture) and this
        // engine walks the remaining frames of each day via the follow-up in handle().
        queryAllHistory()
    }

    /** Frame follow-ups already requested this poll pass, keyed `cmd * 100 + frameIndex`, so a ring
     *  that re-sends the same frame can't trigger a request storm. Cleared at the start of every
     *  [queryAllHistory] pass so each sync re-pulls the full timeline. */
    private val requestedTimingFrames = mutableSetOf<Int>()

    /** Request the stored all-day timelines the ring has accumulated: the group-2 "timing" vital
     *  timelines (HR/SpO2/HRV/stress), temperature, and sleep. Vendor `u3/g1.java` fires the same set
     *  on its sync pass. Each timing query pulls frame 0; the ring's reply drives [handle] to pull
     *  the next frame until the day is complete. */
    private fun queryAllHistory() {
        requestedTimingFrames.clear()
        // The group-2 "timing" histories for TODAY, frame 0. The previous group-7 HR/SpO2/HRV/stress
        // queries were the wrong opcodes (device-info group) and the ring returned empty every time
        // (issue #29). Confirmed decoding into samples against zaggash's R11 rc2 capture; the
        // multi-frame replies reassemble via the next-frame follow-up in [handle].
        send(CRPProtocol.queryTimingHeartRateHistory())
        send(CRPProtocol.queryTimingSpO2History())
        send(CRPProtocol.queryTimingHrvHistory())
        send(CRPProtocol.queryTimingStressHistory())
        send(CRPProtocol.queryHistoryTemp())
        send(CRPProtocol.queryHistorySleep())
    }

    /** The last frame index each timing vital emits before its day is complete (vendor terminal
     *  index: HR/SpO2/stress finalize at frame 1 — two 144-slot frames; HRV at frame 3 — four
     *  72-slot frames). A reply below this index triggers a pull of the next frame. */
    private fun terminalFrameIndex(cmd: Int): Int =
        if (cmd == CRPCommands.CMD_QUERY_TIMING_HRV) 3 else 1

    /** Build the next-frame query for a timing vital, or null for a non-timing cmd. */
    private fun timingQuery(cmd: Int, day: Int, frameIndex: Int): ByteArray? = when (cmd) {
        CRPCommands.CMD_QUERY_TIMING_HR -> CRPProtocol.queryTimingHeartRateHistory(day, frameIndex)
        CRPCommands.CMD_QUERY_TIMING_HRV -> CRPProtocol.queryTimingHrvHistory(day, frameIndex)
        CRPCommands.CMD_QUERY_TIMING_SPO2 -> CRPProtocol.queryTimingSpO2History(day, frameIndex)
        CRPCommands.CMD_QUERY_TIMING_STRESS -> CRPProtocol.queryTimingStressHistory(day, frameIndex)
        else -> null
    }

    override fun handle(event: RingDecodedEvent) {
        // Steps/HR/battery are persisted by RingBLEClient via RingEventBridge. The one piece of
        // engine-side state is the all-day timeline's multi-frame pull: on each timing-history frame
        // the ring returns, request the next frame until the vital's terminal index — the vendor's
        // sequential `insertBleMessage(<query>.b(day, index + 1))` (`e1/{f,d,g,l}.java`). The
        // samples themselves are decoded + persisted via the bridge; this only advances the cursor.
        if (event is RingDecodedEvent.TimingHistoryFrame) {
            if (event.frameIndex >= terminalFrameIndex(event.cmd)) return
            val nextIndex = event.frameIndex + 1
            // Guard against a ring that re-sends the same frame spamming duplicate follow-ups.
            if (!requestedTimingFrames.add(event.cmd * 100 + nextIndex)) return
            send(timingQuery(event.cmd, event.day, nextIndex))
        }
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
        applyTimingSettings(settings)
    }

    /** Send the all-day enable/disable command for every vital. The CRP protocol takes a single
     *  interval byte per enable, and [MeasurementSettings] carries only [MeasurementSettings.hrIntervalMinutes]
     *  (no per-vital cadence), so the HR interval is shared across the board. Disabled vitals are
     *  explicitly turned off so a reconnect can't leave a previously-enabled monitor running. */
    private fun applyTimingSettings(settings: MeasurementSettings) {
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

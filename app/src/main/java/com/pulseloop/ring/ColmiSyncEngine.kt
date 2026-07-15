package com.pulseloop.ring

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.*

/**
 * Ported from [ColmiSyncEngine] in ColmiSyncEngine.swift.
 * Colmi R02 sync engine: response-driven history state machine + realtime-HR keepalive.
 *
 * Stage order: activity(0..7) → HR(0..7) → stress(0..6) → spo2(bigdata) → sleep(bigdata)
 * → hrv(0..6) → temperature(bigdata; interval action 119 when the ring supports it, paged
 * per day 0..6 with per-packet continuation, else legacy action 37) → done.
 */
class ColmiSyncEngine(
    private var writer: RingCommandWriter?,
    private val decoder: ColmiDecoder
) : RingSyncEngine {
    private val encoder = ColmiEncoder
    private val zone = ZoneId.systemDefault()

    /** User-chosen all-day measurement config, applied in the connect handshake and updatable
     *  live. `null` ⇒ the user has never saved one: the ring's own settings (possibly
     *  configured in the official app) are the source of truth, so the handshake reads them
     *  and seeds the app config from the replies instead of force-writing defaults. */
    private var measurementSettings: MeasurementSettings? = null

    /** Sink for the config seeded from the ring's pref-read replies (no persisted config). */
    private var onConfigSeeded: ((MeasurementSettings) -> Unit)? = null

    /** Invoked when the ring's device-support reply advertises `supportBlePair`; the BLE client
     *  creates the OS bond in response (mirrors the official QRing app). */
    private var onBondRequested: (() -> Unit)? = null

    // Read-reply seeding state: collected until both the auto-HR and temp prefs reported.
    private var seedingFromRing = false
    private var seededHrIntervalMinutes: Int? = null
    private var seededTempEnabled: Boolean? = null

    /** The user's profile for the ring's user-preferences command. `null` ⇒ send the encoder's
     *  neutral defaults (matches prior behaviour) until the coordinator pushes real values. */
    private var userProfile: UserProfileValues? = null

    // History state machine
    private enum class Stage { IDLE, ACTIVITY, HEART_RATE, STRESS, SPO2, SLEEP, HRV, TEMPERATURE, DONE }

    private var stage = Stage.IDLE
    private var daysAgo = 0
    private var syncDay = LocalDate.now(zone)

    // Per-day paged-stage metadata from the ring's packet 0 (QRing ReadHeartRateRsp /
    // PressureRsp / HRVRsp): how many packets the day has (drives terminal detection) and the
    // sampling cadence in minutes (drives the decoder's timestamp grid). Reset on every request.
    private var expectedPackets: Int? = null
    private var slotMinutes: Int? = null

    /** From the 0x3C device-support reply: ring uses the interval-temperature big-data path
     *  (action 119) instead of the legacy action 37 that such firmware may not answer. */
    private var supportsIntervalTemp = false

    /** A standalone sleep-only request is outstanding (see [syncSleepNow]). Set true when we fire
     *  `bigDataSleep()` outside the staged history sync; its big-data completion clears it and
     *  must NOT advance the pipeline into HRV. Volatile: set on Main, read on the notify thread. */
    @Volatile private var sleepOnlyActive = false
    private var sleepOnlyWatchdogJob: Job? = null

    // Watchdog
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val watchdogTimeoutMs = 10_000L
    private val activityWatchdogTimeoutMs = 20_000L

    // Realtime HR keepalive
    private var realtimeHRActive = false
    private var realtimeKeepaliveJob: Job? = null
    private var manualHRActive = false
    private var manualSpO2Active = false

    /** Last bpm seen while a manual spot measurement runs: QRing reports it in the 0x6A stop
     *  frame so the ring's own measurement log records the reading (0 = cancelled). */
    private var lastManualBpm = 0

    companion object {
        fun isHistoryOpcode(op: UByte): Boolean =
            op == ColmiCommandID.SYNC_ACTIVITY ||
            op == ColmiCommandID.SYNC_HEART_RATE ||
            op == ColmiCommandID.SYNC_STRESS ||
            op == ColmiCommandID.SYNC_HRV

        /** 20 s wall-clock re-arm, matching QRing's HeartActivity timer. */
        private const val REALTIME_KEEPALIVE_MS = 20_000L
    }

    override fun runStartup() {
        writer?.enqueue(encoder.phoneName())
        writer?.enqueue(encoder.setDateTime())
        writer?.enqueue(userPreferencesCommand())
        // Read the ring's capability bitfield early: the reply's supportBlePair bit drives the
        // OS bond (see handleRawNotify → onBondRequested). Harmless on rings that don't answer.
        writer?.enqueue(encoder.deviceSupport())
        writer?.enqueue(encoder.battery())
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_HR_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_STRESS_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_SPO2_PREF))
        writer?.enqueue(encoder.readPref(ColmiCommandID.AUTO_HRV_PREF))
        writer?.enqueue(encoder.readTempPref())
        writer?.enqueue(encoder.readGoals())
        val settings = measurementSettings
        if (settings != null) {
            // The user saved a config in PulseLoop — it is the source of truth; push it so
            // the ring accumulates the data the history sync returns.
            enqueueMeasurementCommands(settings)
        } else {
            // Nothing persisted (fresh install / first connect): don't clobber ring-side
            // settings the user may have configured elsewhere. The AUTO_HR_PREF / temp-pref
            // reads above seed the app config from the ring's replies (handleRawNotify).
            // SpO2/stress/HRV are still force-enabled — the history sync depends on them
            // and they have always been enabled on connect.
            seedingFromRing = true
            seededHrIntervalMinutes = null
            seededTempEnabled = null
            writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_SPO2_PREF, enabled = true))
            writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_STRESS_PREF, enabled = true))
            writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_HRV_PREF, enabled = true))
        }
        startHistorySync()
    }

    /**
     * On-demand sleep fetch (QRing parity): request just the sleep big-data record, off the
     * staged history pipeline, so it can't be lost to an earlier stage's watchdog skip. The
     * decoder emits sleep events from the reply exactly as it does during a full sync; the only
     * difference is [handleBigDataComplete] must not advance into HRV afterwards
     * ([sleepOnlyActive]). If a full history sync is already running it will fetch sleep itself,
     * so this becomes a no-op then.
     */
    override fun syncSleepNow() {
        if (sleepOnlyActive) return  // one already outstanding — don't double-request
        if (stage != Stage.IDLE && stage != Stage.DONE) return  // a full sync already covers sleep
        sleepOnlyActive = true
        requestSleep()
        sleepOnlyWatchdogJob?.cancel()
        sleepOnlyWatchdogJob = scope.launch {
            delay(watchdogTimeoutMs)
            sleepOnlyActive = false  // reply never completed — give up quietly, no pipeline advance
        }
    }

    // MARK: Measurement settings + user profile (iOS #19)

    override fun setMeasurementSettings(settings: MeasurementSettings?) {
        measurementSettings = settings
    }

    override fun setOnMeasurementConfigSeeded(callback: (MeasurementSettings) -> Unit) {
        onConfigSeeded = callback
    }

    override fun setOnBondRequested(callback: () -> Unit) {
        onBondRequested = callback
    }

    /**
     * Consume the connect handshake's pref-read replies while seeding (no persisted config).
     * One exception to "the ring's settings win": all-day HR must be ON or the `0x15` HR
     * history sync returns nothing — so a disabled reply gets a re-enable write, keeping
     * the ring's reported interval.
     */
    override fun handleRawNotify(data: ByteArray) {
        // Device-support reply is independent of config seeding: remember the temperature-path
        // capability and, if the ring wants a bond, ask the client to create one. Return early —
        // a 0x3C frame carries nothing else we consume.
        decoder.decodeDeviceSupport(data)?.let { support ->
            supportsIntervalTemp = support.supportsIntervalTemp
            if (support.supportsBlePair) onBondRequested?.invoke()
            return
        }
        if (!seedingFromRing) return
        decoder.decodeAutoHRPrefRead(data)?.let { readout ->
            val interval = if (readout.intervalMinutes in 5..60) readout.intervalMinutes else 5
            if (!readout.enabled) {
                writer?.enqueue(encoder.autoHeartRate(enabled = true, intervalMinutes = interval))
            }
            seededHrIntervalMinutes = interval
            finishSeedingIfComplete()
        }
        decoder.decodeTempPrefRead(data)?.let { enabled ->
            seededTempEnabled = enabled
            finishSeedingIfComplete()
        }
    }

    /** Once both prefs reported, adopt the seeded config and surface it for persistence. */
    private fun finishSeedingIfComplete() {
        val interval = seededHrIntervalMinutes ?: return
        val tempEnabled = seededTempEnabled ?: return
        seedingFromRing = false
        val settings = MeasurementSettings(
            hrEnabled = true, hrIntervalMinutes = interval,   // HR forced on (see handleRawNotify)
            spo2Enabled = true, stressEnabled = true, hrvEnabled = true,  // force-enabled at startup
            temperatureEnabled = tempEnabled,
        )
        measurementSettings = settings
        onConfigSeeded?.invoke(settings)
    }

    override fun applyMeasurementSettings(settings: MeasurementSettings) {
        measurementSettings = settings
        enqueueMeasurementCommands(settings)
    }

    override fun setUserProfile(profile: UserProfileValues) {
        userProfile = profile
    }

    override fun applyUserProfile(profile: UserProfileValues) {
        userProfile = profile
        writer?.enqueue(userPreferencesCommand())
    }

    /** Translate the device-agnostic settings into Colmi pref commands. HR uses the dedicated
     *  `0x16` command (interval + on/off in one); SpO2/stress/HRV/temp are simple on/off prefs. */
    private fun enqueueMeasurementCommands(s: MeasurementSettings) {
        writer?.enqueue(encoder.autoHeartRate(enabled = s.hrEnabled, intervalMinutes = s.hrIntervalMinutes))
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_SPO2_PREF, enabled = s.spo2Enabled))
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_STRESS_PREF, enabled = s.stressEnabled))
        writer?.enqueue(encoder.writePref(ColmiCommandID.AUTO_HRV_PREF, enabled = s.hrvEnabled))
        writer?.enqueue(encoder.writeTempPref(enabled = s.temperatureEnabled))
    }

    /** Build the user-preferences command from the stored profile, falling back to the encoder's
     *  neutral defaults when no profile has been pushed yet. */
    private fun userPreferencesCommand(): ByteArray {
        val p = userProfile ?: return encoder.userPreferences()
        return encoder.userPreferences(
            metric = p.metric, gender = p.gender, age = p.age,
            heightCm = p.heightCm, weightKg = p.weightKg,
        )
    }

    private fun startHistorySync() {
        // A full sync fetches sleep itself; cancel any standalone sleep-only request so its
        // big-data completion doesn't short-circuit the pipeline's own SLEEP stage.
        sleepOnlyActive = false
        sleepOnlyWatchdogJob?.cancel(); sleepOnlyWatchdogJob = null
        daysAgo = 0
        stage = Stage.ACTIVITY
        requestActivity()
        armWatchdog()
    }

    override fun handle(event: RingDecodedEvent) {
        if (manualHRActive && event is RingDecodedEvent.HeartRateSample) lastManualBpm = event.bpm
    }

    // MARK: Driver hooks

    fun handleHistoryFrame(data: ByteArray): List<RingDecodedEvent> {
        captureStageMetadata(data)
        val events = decoder.decodeHistory(data, day = syncDay, slotMinutes = slotMinutes)
        advanceAfterPagedFrame(data)
        armWatchdog()
        return events
    }

    /**
     * Capture what the ring's own frames tell us about the current paged day, before decoding:
     * - packet 0 carries the day's packet count (v[2], drives terminal detection) and the
     *   sampling cadence in minutes (v[3]) — QRing's ReadHeartRateRsp/PressureRsp/HRVRsp;
     * - packet 1 echoes which day the log actually belongs to (HR: start time as
     *   "local-wall-clock read as UTC" at v[2..5]; stress/HRV: day offset at v[2]) — trust the
     *   echo over the request-side [syncDay] so a late or shifted reply can't be attributed to
     *   the wrong day.
     */
    private fun captureStageMetadata(data: ByteArray) {
        val packet = ColmiPacket.validating(data) ?: return
        val v = packet.bytes.map { it.toUByte() }
        when (v[0]) {
            ColmiCommandID.SYNC_HEART_RATE, ColmiCommandID.SYNC_STRESS, ColmiCommandID.SYNC_HRV -> {}
            else -> return
        }
        when (v[1].toInt()) {
            0 -> {
                expectedPackets = v[2].toInt().takeIf { it in 2..64 }
                slotMinutes = v[3].toInt().takeIf { it in 1..240 }
            }
            1 -> when (v[0]) {
                ColmiCommandID.SYNC_HEART_RATE -> {
                    val echo = v[2].toLong() or (v[3].toLong() shl 8) or
                        (v[4].toLong() shl 16) or (v[5].toLong() shl 24)
                    if (echo > 0) {
                        syncDay = Instant.ofEpochSecond(echo).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                }
                else -> {
                    val offset = v[2].toInt()
                    if (offset in 0..29) syncDay = dayStart(offset)
                }
            }
            else -> {}
        }
    }

    fun handleBigDataComplete(type: UByte, data: ByteArray? = null) {
        when (type) {
            ColmiCommandID.BIG_DATA_SPO2 -> {
                // Same stray-frame guard as SLEEP/TEMPERATURE below: rings can re-emit a big-data
                // frame outside its stage, and an unguarded advance would jump the pipeline back
                // to SLEEP and re-request it.
                if (stage != Stage.SPO2) return
                stage = Stage.SLEEP; requestSleep(); armWatchdog()
            }
            ColmiCommandID.BIG_DATA_SLEEP -> {
                // A standalone sleep-only fetch just completed: the decoder already emitted the
                // sleep events; do NOT drive the full-sync pipeline into HRV.
                if (sleepOnlyActive) {
                    sleepOnlyActive = false
                    sleepOnlyWatchdogJob?.cancel(); sleepOnlyWatchdogJob = null
                    return
                }
                // Only the pipeline's own SLEEP stage should advance to HRV. Ignore a stray sleep
                // completion that arrives when we're not on SLEEP — e.g. a standalone reply that
                // landed after a full sync started (startHistorySync cleared sleepOnlyActive), or a
                // late reply after the watchdog already skipped SLEEP. Otherwise it jumps/duplicates
                // the pipeline (ACTIVITY→HRV, skipping HR/STRESS/SPO2, or a second HRV request).
                if (stage != Stage.SLEEP) return
                stage = Stage.HRV; daysAgo = 0; requestHRV(); armWatchdog()
            }
            ColmiCommandID.BIG_DATA_TEMPERATURE -> {
                // Temperature is the final history stage: Colmi rings support neither blood pressure
                // nor blood sugar (see ColmiCoordinator.capabilities and this class's stage-order doc).
                // Finishing here — instead of querying blood sugar (0x47) — avoids an unsupported
                // request the ring answers by re-emitting its temperature frame, which re-entered this
                // branch and looped into a GATT write storm that starved the sleep sync. The stage
                // guard also makes a repeated temperature frame idempotent (finish once, then ignore).
                if (stage == Stage.TEMPERATURE) finishSync()
            }
            ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE -> {
                if (stage != Stage.TEMPERATURE) return
                // Interval temperature (action 119) needs app-driven continuation (QRing
                // LargeDataHandler §119): the header carries packetCount [8] / packetIndex [9];
                // re-request packetIndex+1 until the day's last packet, then move to the next
                // day (0..6) and finish after the oldest.
                val packetCount = data?.getOrNull(8)?.toInt()?.and(0xFF) ?: 0
                val packetIndex = data?.getOrNull(9)?.toInt()?.and(0xFF) ?: 0
                when {
                    packetCount > 0 && packetIndex < packetCount - 1 -> {
                        writer?.enqueue(encoder.bigDataIntervalTemperature(daysAgo, packetIndex + 1))
                        armWatchdog()
                    }
                    daysAgo < 6 -> {
                        daysAgo++
                        writer?.enqueue(encoder.bigDataIntervalTemperature(daysAgo, 0))
                        armWatchdog()
                    }
                    else -> finishSync()
                }
            }
            // BIG_DATA_SLEEP_LUNCH (0x3E) arrives unsolicited alongside the action-39 sleep
            // reply on rings with nap support; the decoder already emitted its events and it
            // must never advance the pipeline — no branch, intentionally.
        }
    }

    // MARK: Stage requests

    private fun resetStageMetadata() {
        expectedPackets = null
        slotMinutes = null
    }

    private fun requestActivity() {
        syncDay = dayStart(daysAgo)
        resetStageMetadata()
        writer?.enqueue(encoder.syncActivity(daysAgo))
    }

    private fun requestHeartRate() {
        syncDay = dayStart(daysAgo)
        resetStageMetadata()
        // The ring indexes its daily HR logs on "local wall clock read as UTC": QRing sends
        // localMidnightEpoch + tzOffset (= the calendar date at 00:00 UTC) and subtracts the
        // offset back from the echo. Sending the true local-midnight epoch landed inside the
        // ring's previous day in GMT+ timezones.
        val unix = syncDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond().toInt()
        writer?.enqueue(encoder.syncHeartRate(unix))
    }

    private fun requestStress() {
        syncDay = dayStart(daysAgo)
        resetStageMetadata()
        writer?.enqueue(encoder.syncStress(daysAgo))
    }

    private fun requestHRV() {
        syncDay = dayStart(daysAgo)
        resetStageMetadata()
        writer?.enqueue(encoder.syncHRV(daysAgo))
    }

    private fun requestSpo2() { writer?.enqueue(encoder.bigDataSpo2()) }
    private fun requestSleep() { writer?.enqueue(encoder.bigDataSleep()) }

    private fun requestTemperature() {
        if (supportsIntervalTemp) {
            // Modern path (QRing gates on the same 0x3C bit): per-day interval series, starting
            // at today packet 0; handleBigDataComplete drives packet/day continuation.
            writer?.enqueue(encoder.bigDataIntervalTemperature(daysAgo, 0))
        } else {
            writer?.enqueue(encoder.bigDataTemperature())
        }
    }

    private fun dayStart(daysAgo: Int): LocalDate = LocalDate.now(zone).minusDays(daysAgo.toLong())

    // MARK: Paged stage advancement

    private fun advanceAfterPagedFrame(data: ByteArray) {
        val packetNr = ColmiDecoder.historyPacketNumber(data)
        val isEmpty = packetNr == 0xFF
        val dayComplete = isEmpty || isTerminalPacket(data)
        if (!dayComplete) return

        when (stage) {
            Stage.ACTIVITY -> {
                if (daysAgo < 7) { daysAgo++; requestActivity() }
                else { daysAgo = 0; stage = Stage.HEART_RATE; requestHeartRate() }
            }
            Stage.HEART_RATE -> {
                if (daysAgo < 7) { daysAgo++; requestHeartRate() }
                else { daysAgo = 0; stage = Stage.STRESS; requestStress() }
            }
            Stage.STRESS -> {
                // Stress pages over today+6 like HRV (QRing PressureRepository); the old
                // single bare-0x37 request only ever fetched today.
                if (daysAgo < 6) { daysAgo++; requestStress() }
                else { stage = Stage.SPO2; requestSpo2() }
            }
            Stage.HRV -> {
                // Skip BP: Colmi rings don't support it (see ColmiCoordinator.capabilities).
                if (daysAgo < 6) { daysAgo++; requestHRV() }
                else { daysAgo = 0; stage = Stage.TEMPERATURE; requestTemperature() }
            }
            else -> {}
        }
    }

    /**
     * A day's last data packet. QRing ends a day at packet == size−1, where size comes from the
     * day's packet 0 ([captureStageMetadata]) — the old hardcoded checks (nothing for HR, 4 for
     * stress/HRV) meant an HR day with data NEVER completed: the watchdog force-skipped the
     * whole stage and days 1..7 were never requested. Fallbacks when packet 0 was missed:
     * stress/HRV keep the old size-5 assumption; HR ends on packet 23 (24 packets ≥ a full
     * 5-min-cadence day — QRing's today-cap) or the watchdog.
     */
    private fun isTerminalPacket(data: ByteArray): Boolean {
        val v = data.map { it.toUByte() }
        if (v.size < 7) return false
        val packetNr = v[1].toInt()
        return when (v[0]) {
            ColmiCommandID.SYNC_STRESS, ColmiCommandID.SYNC_HRV ->
                packetNr >= 2 && packetNr == (expectedPackets ?: 5) - 1
            ColmiCommandID.SYNC_HEART_RATE ->
                (packetNr >= 2 && packetNr == (expectedPackets ?: -1) - 1) || packetNr == 23
            // Gate the steps/day-count equality off the 0xF0 header packet — QRing evaluates it
            // only for data packets, and a header satisfying it would end the day early.
            ColmiCommandID.SYNC_ACTIVITY ->
                packetNr != 0xF0 && v[5].toInt() == v[6].toInt() - 1
            else -> false
        }
    }

    // MARK: Watchdog

    private fun armWatchdog() {
        watchdogJob?.cancel()
        val expected = stage
        val timeout = if (expected == Stage.ACTIVITY) activityWatchdogTimeoutMs else watchdogTimeoutMs
        watchdogJob = scope.launch {
            delay(timeout)
            if (isActive) forceAdvanceStage(expected)
        }
    }

    private fun forceAdvanceStage(stuck: Stage) {
        when (stuck) {
            Stage.ACTIVITY -> { daysAgo = 0; stage = Stage.HEART_RATE; requestHeartRate() }
            Stage.HEART_RATE -> { daysAgo = 0; stage = Stage.STRESS; requestStress() }
            Stage.STRESS -> { stage = Stage.SPO2; requestSpo2() }
            Stage.SPO2 -> { stage = Stage.SLEEP; requestSleep() }
            Stage.SLEEP -> { daysAgo = 0; stage = Stage.HRV; requestHRV() }
            Stage.HRV -> { daysAgo = 0; stage = Stage.TEMPERATURE; requestTemperature() }  // BP unsupported — skip
            Stage.TEMPERATURE -> finishSync()  // blood sugar unsupported — temperature is terminal
            else -> {}
        }
        if (stage != Stage.DONE) armWatchdog()
    }

    private fun finishSync() {
        stage = Stage.DONE
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // MARK: Measurement actions

    override fun startHeartRate() {
        realtimeHRActive = true
        writer?.enqueue(encoder.realtimeHeartRate(enable = true))
        // Re-arm the stream on a wall-clock timer like QRing (20 s), not per received frame:
        // a frame-count keepalive starves exactly when the ring pauses the stream to wait for
        // the continue, killing the session.
        realtimeKeepaliveJob?.cancel()
        realtimeKeepaliveJob = scope.launch {
            while (isActive) {
                delay(REALTIME_KEEPALIVE_MS)
                if (realtimeHRActive) writer?.enqueue(encoder.realtimeHeartRateContinue())
            }
        }
    }

    override fun stopHeartRate() {
        realtimeKeepaliveJob?.cancel(); realtimeKeepaliveJob = null
        if (manualHRActive) {
            manualHRActive = false
            writer?.enqueue(encoder.manualHeartRate(enable = false, lastBpm = lastManualBpm))
        }
        if (!realtimeHRActive) return
        realtimeHRActive = false
        writer?.enqueue(encoder.realtimeHeartRate(enable = false))
    }

    override fun measureHeartRateSpot() {
        manualHRActive = true
        lastManualBpm = 0
        writer?.enqueue(encoder.manualHeartRate(enable = true))
    }

    override fun startSpO2() {
        // On-demand live SpO₂ via the real-time command (0x69/3). The ring streams
        // [0x69, 3, error, value] frames decoded to Spo2Result. (Historical SpO₂ is a
        // separate big-data path, requestSpo2(), used by the startup history sync.)
        manualSpO2Active = true
        writer?.enqueue(encoder.manualSpO2(enable = true))
    }

    override fun stopSpO2() {
        if (!manualSpO2Active) return
        manualSpO2Active = false
        writer?.enqueue(encoder.manualSpO2(enable = false))
    }

    override fun findDevice() {
        writer?.enqueue(encoder.findDevice())
    }

    override fun setGoal(steps: Int) {
        // Colmi goals write left minimal pending verification
    }

    override fun powerOff() {
        writer?.enqueue(encoder.powerOff())
    }

    override fun factoryReset() {
        writer?.enqueue(encoder.factoryReset())
    }

    fun destroy() {
        scope.cancel()
    }
}

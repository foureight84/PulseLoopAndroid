package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.ring.*
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Ported from [RingSyncCoordinator] in RingSyncCoordinator.swift.
 * High-level orchestration of ring command flows. Subscribes to PulseEventBus to
 * track latest measurement values and expose app-facing measurement actions.
 */
class RingSyncCoordinator(
    private val client: RingBLEClient,
    private val db: PulseLoopDatabase,
    private val apiKeyStore: ApiKeyStore? = null,
) {
    enum class MeasureState { IDLE, MEASURING, DONE, FAILED }

    var hrState: MeasureState = MeasureState.IDLE
        private set
    var spo2State: MeasureState = MeasureState.IDLE
        private set
    var hrvState: MeasureState = MeasureState.IDLE
        private set
    var bloodPressureState: MeasureState = MeasureState.IDLE
        private set
    var combinedState: MeasureState = MeasureState.IDLE
        private set
    var lastSyncAt: Long? = null
        private set

    /**
     * History-sync progress, 0–100 while records stream in after a connect, null when
     * idle/done. Computed the same way as the official app's "Sync data X%": the newest
     * received record's timestamp mapped onto the [now − N days, now] window, monotonic.
     */
    private val _syncProgress = MutableStateFlow<Int?>(null)
    val syncProgress: StateFlow<Int?> = _syncProgress.asStateFlow()
    private var syncWindowStart = 0L
    private var syncWindowEnd = 0L
    private var syncResetJob: Job? = null
    private var lastAdvanceAt = 0L
    /** Days of history requested on startup — must match makeHistoryQueryCommand's default. */
    private val syncWindowDays = 1
    /** How often the stall-watcher checks, and how long without progress before it gives up. */
    private val SYNC_STALL_CHECK_MS = 2_000L
    private val SYNC_STALL_MS = 12_000L

    /** Latest live HR bpm, mirrored for UI without a query. */
    var latestHRValue: Int? = null
        private set
    /** Latest live SpO2 %, mirrored for UI without a query. */
    var latestSpO2Value: Int? = null
        private set
    var latestHrvValue: Int? = null
        private set
    var latestBloodPressure: Pair<Int, Int>? = null
        private set

    var workoutHRActive = false
        private set
    private var hrNoReadingReported = false
    private var spo2NoReadingReported = false
    private var hrvNoReadingReported = false
    private var bloodPressureNoReadingReported = false
    /** The ring told us it isn't on the finger during a spot measure (CRP wear-state push). Read by
     *  the Vitals UI to show "put the ring on" instead of the generic steadiness hint. Set only when
     *  the not-worn signal arrives *before* any reading, so a wear-state drop right after a good
     *  reading (seen on real hardware, issue #29) can't turn a success into "not worn". */
    var measureNotWorn: Boolean = false
        private set
    /** The samples of the HR measurement in flight, and the rule for whether they settled — see
     *  [HRSampleWindow], which owns the warm-up echo and the consistency gate (iOS #66). */
    private val hrWindow = HRSampleWindow()
    /** The SpO2 samples of the measurement in flight, and the rule for settling them — see
     *  [Spo2SampleWindow]. Only consulted for a family that says when it has finished. */
    private val spo2Window = Spo2SampleWindow()
    /** The refusal fast-fail gate for spot measurements (iOS `c8969a4`) — the ring's `03 2f`
     *  verdict can only ever abort the measurement it names, while it is actually running. */
    private val spot = SpotMeasurementGate()
    /** True once the current measurement has produced a real (post-warm-up) bpm; keeps a stale
     *  [latestHRValue] from passing for a fresh reading. */
    val measurementReceivedReading: Boolean get() = hrWindow.receivedReading

    /**
     * While a spot HR measurement is settling, the live bpm stream is working, not reporting, and
     * must not be written to history (issue #60).
     *
     * A spot measurement's output is **one** reading — the settled value this leg returns. The
     * samples it settles *from* are a sensor converging: on the #59 ring the PPG spends its first
     * ~26 s on a plateau tens of bpm below the real rate, and every one of those estimates used to
     * be stored as its own heart-rate row stamped with the moment it arrived. One failed
     * measurement therefore left a whole train of readings that were never the user's heart rate,
     * with no way to remove them, and they drag every average built over that window.
     *
     * So the live stream is suppressed for the duration and the settled value is published once at
     * the end. A live *workout* is the opposite case — there the stream is the data — so a
     * measurement that runs during one suppresses nothing.
     */
    @Volatile
    var suppressesLiveHeartRatePersistence: Boolean = false
        private set

    /**
     * The same rule for SpO₂ (issue #60, extended on RC-1 feedback).
     *
     * It matters more here since the leg started settling rather than returning the first sample:
     * the captured run streams twelve values over ~50 s, every one of which would otherwise be
     * stored as its own SpO₂ reading. Unlike heart rate there is no workout carve-out, because
     * nothing streams live SpO₂ for its own sake — a spot measurement is the only thing that
     * produces these, and its output is one reading.
     */
    @Volatile
    var suppressesLiveSpo2Persistence: Boolean = false
        private set

    val connectionState: RingConnectionState get() = client.state.value.connectionState
    val isConnected: Boolean get() = connectionState == RingConnectionState.CONNECTED
    /** Selects the single-packet Jring measurement flow. YCBT advertises manual BP/glucose
     * capabilities but measures each vital with separate AppStartMeasurement modes. */
    val supportsCombinedMeasurement: Boolean get() = engine?.supportsCombinedMeasurement == true

    /** The HR leg's ceiling for the ring that is actually connected (issue #59). */
    private val hrMeasureSeconds: Long get() = (engine?.spotHeartRateSeconds ?: HR_MEASURE_SECONDS).toLong()
    /**
     * Upper bound on the whole sequential sweep for the connected ring — what the Vitals countdown
     * runs against, so it can't finish while a leg is still measuring.
     *
     * Summed over the legs [measureSpot] will *actually* run, gated on the same capabilities, and
     * using this ring's own HR ceiling (issue #59). The flat sum of all four legs told an RC-1
     * tester his measurement would take 188 s when his ring runs two of them; a countdown that
     * overstates by 80 s is worse than no countdown, because the user reads it as a promise.
     */
    val spotMeasureSeconds: Int
        get() {
            val caps = client.state.value.activeCapabilities
            var total = 3
            if (caps.contains(WearableCapability.MANUAL_HEART_RATE)) total += hrMeasureSeconds.toInt()
            if (caps.contains(WearableCapability.MANUAL_SPO2)) total += SPO2_MEASURE_SECONDS
            if (caps.contains(WearableCapability.MANUAL_BLOOD_PRESSURE)) total += BP_MEASURE_SECONDS
            if (caps.contains(WearableCapability.MANUAL_HRV)) total += HRV_MEASURE_SECONDS
            return total
        }
    private val spo2MeasureSeconds = SPO2_MEASURE_SECONDS.toLong()
    private val combinedMeasureSeconds = COMBINED_MEASURE_SECONDS.toLong()

    companion object {
        /** Duration of a combined spot measurement (0x23→0x24); also drives the UI countdown. */
        const val COMBINED_MEASURE_SECONDS = 45
        /** Default window for the live-HR leg of a spot measurement. A family that ends its own
         *  measurement may raise its own ceiling — see [RingSyncEngine.spotHeartRateSeconds]. */
        const val HR_MEASURE_SECONDS = RingSyncEngine.DEFAULT_SPOT_HEART_RATE_SECONDS
        /** Window for the live-SpO₂ leg of a spot measurement. iOS raised this 40 → 60
         *  (`c8969a4`): the R99's successful sweep took 38s while another attempt ran past 41s
         *  with no result — at 40s the outcome is a coin toss where the user watches the ring's
         *  red LED work and gets an error anyway. */
        const val SPO2_MEASURE_SECONDS = 60
        const val BP_MEASURE_SECONDS = 40
        const val HRV_MEASURE_SECONDS = 40
        /** Intentional UX upper bound for sequential HR + SpO₂ + BP + HRV; drives the countdown.
         *  Derived from the legs so the countdown can't desync when one is tuned. Post-#66 the
         *  HR leg samples its full window by design, so this is a real bound, not slack. This is
         *  the bound for a ring with the default HR window; with one connected, prefer the
         *  instance's [spotMeasureSeconds], which uses that ring's own HR ceiling (issue #59). */
        const val SPOT_MEASURE_SECONDS =
            HR_MEASURE_SECONDS + SPO2_MEASURE_SECONDS + BP_MEASURE_SECONDS + HRV_MEASURE_SECONDS + 3
        /** Max time to wait for the pre-factory-reset history sync before resetting anyway. */
        const val SYNC_BEFORE_RESET_TIMEOUT_MS = 30_000L
    }

    private val engine: RingSyncEngine? get() = client.syncEngine
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamJob: Job? = null
    private var startupEngine: RingSyncEngine? = null
    private var startupJob: Job? = null

    fun start() {
        streamJob?.cancel()
        streamJob = null
        streamJob = scope.launch {
            PulseEventBus.events.collect { event -> handle(event) }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        startupJob?.cancel()
        startupJob = null
        startupEngine = null
    }

    // MARK: - Actions

    /** Canonical startup sequence run on connect. */
    fun runStartupSequence() {
        val targetEngine = engine ?: return
        if (!isConnected || startupEngine === targetEngine) return
        startupEngine = targetEngine
        // Begin progress here (a real sync request), NOT on DeviceStateChanged(CONNECTED):
        // the ring re-emits CONNECTED on every 0x0C status packet, which would otherwise
        // keep resetting the bar to 0%.
        beginSyncProgress()
        startupJob?.cancel()
        startupJob = scope.launch {
            // Push the persisted measurement config + profile into the engine BEFORE
            // runStartup so the connect handshake reflects them (the engine emits the
            // commands itself, so we don't double-send here). iOS #19 parity.
            // No persisted config (null) ⇒ the engine seeds one from the ring's own
            // reported settings; persist that as the device's initial config.
            val persisted = loadMeasurementSettings()
            if (!isConnected || engine !== targetEngine) return@launch
            targetEngine.setMeasurementSettings(persisted)
            if (persisted == null) {
                targetEngine.setOnMeasurementConfigSeeded { seeded ->
                    scope.launch { persistSeededMeasurementConfig(db, seeded) }
                }
            }
            val profile = loadUserProfileValues()
            if (!isConnected || engine !== targetEngine) return@launch
            profile?.let { targetEngine.setUserProfile(it) }
            // Claim the ring for this app FIRST (0x48). The ring binds to the connecting app's
            // id and otherwise can stay mute after another app (e.g. the official one) claimed it.
            apiKeyStore?.ringAppId?.let { targetEngine.setAppId(it) }
            targetEngine.runStartup()
            // Push the user's profile so the ring's blood-sugar (profile-derived) and
            // calorie algorithms run on real inputs. BP is a direct sensor reading and
            // does not depend on user info. Matches the official app, which calls
            // setUserInfo on every connect anyway.
            pushUserSettingsFromStore(targetEngine)
            lastSyncAt = System.currentTimeMillis()
        }
    }

    /** The persisted per-device measurement config, or null when the user never saved one. */
    private suspend fun loadMeasurementSettings(): MeasurementSettings? =
        loadPersistedMeasurementSettings(db)

    private suspend fun loadUserProfileValues(): UserProfileValues? =
        loadPersistedUserProfile(db, apiKeyStore)

    /**
     * Live "Save" from the Measurement settings section: persist nothing here (the view owns
     * the Room write), just push the latest config to the connected ring so it takes effect
     * immediately. No-op when disconnected — applied on the next connect handshake instead.
     */
    fun applyMeasurementSettings() {
        if (!isConnected) return
        scope.launch {
            loadMeasurementSettings()?.let { engine?.applyMeasurementSettings(it) }
        }
    }

    /** Live push of the profile-backed user-preferences command (Colmi 0x02-equivalent). */
    fun applyUserProfileToRing() {
        if (!isConnected) return
        scope.launch {
            loadUserProfileValues()?.let { engine?.applyUserProfile(it) }
        }
    }

    /** Read the stored profile + BP calibration and push them to the ring. */
    private fun pushUserSettingsFromStore(targetEngine: RingSyncEngine) {
        scope.launch {
            val profile = try { db.userProfileDao().get() } catch (_: Exception) { null }
            if (!isConnected || engine !== targetEngine) return@launch
            applyUserSettingsToEngine(
                targetEngine,
                profile,
                apiKeyStore?.bpAdjustSystolic ?: 0,
                apiKeyStore?.bpAdjustDiastolic ?: 0,
            )
        }
    }

    /**
     * Send user info (0x02) and BP calibration (0x33) to the ring. Called on
     * connect and immediately after the user edits their profile in Settings.
     * User info feeds the ring's blood-sugar (profile-derived estimate) and
     * calorie algorithms only; BP is a direct sensor reading. Values must be
     * metric (cm / kg); [makeUserInfoCommand] transmits them with the metric flag.
     */
    fun applyUserSettings(profile: UserProfileEntity?, bpSystolic: Int, bpDiastolic: Int) {
        if (!isConnected) return
        val targetEngine = engine ?: return
        applyUserSettingsToEngine(targetEngine, profile, bpSystolic, bpDiastolic)
    }

    private fun applyUserSettingsToEngine(
        targetEngine: RingSyncEngine,
        profile: UserProfileEntity?,
        bpSystolic: Int,
        bpDiastolic: Int,
    ) {
        profile?.let { p ->
            val age = p.age
            val heightCm = p.heightCm?.toInt()
            val weightKg = p.weightKg?.toInt()
            if (age != null && heightCm != null && weightKg != null) {
                val isMale = p.sex?.equals("male", ignoreCase = true) == true
                targetEngine.setUserInfo(age, isMale, heightCm, weightKg)
            }
        }
        if (bpSystolic in 1..300 && bpDiastolic in 1..300) {
            targetEngine.setBloodPressureAdjust(bpSystolic, bpDiastolic)
        }
    }

    fun syncNow() {
        if (!isConnected) return
        beginSyncProgress()
        engine?.refresh()
        lastSyncAt = System.currentTimeMillis()
    }

    /**
     * On-demand, sleep-only sync (QRing-style): fetch just the sleep record without running the
     * whole history pipeline. Wired to the Sleep screen opening so a user who wants last night's
     * sleep gets a dedicated request instead of depending on the full sync's SLEEP stage
     * surviving four earlier stages. No-op when disconnected, or on rings that fetch sleep in
     * bulk (jring).
     */
    fun syncSleepNow() {
        if (!isConnected) return
        engine?.syncSleepNow()
    }

    /** Pull-to-refresh entry point. */
    suspend fun pullToRefresh() {
        if (isConnected) {
            syncNow()
        } else if (client.state.value.activeDeviceType != null) {
            client.connectLastKnown()
        } else {
            client.startScanning()
        }
        delay(1200)
    }

    // MARK: - Workout HR streaming

    fun startWorkoutHeartRate() {
        if (!isConnected) return
        engine?.startHeartRate()
        workoutHRActive = true
    }

    fun stopWorkoutHeartRate() {
        if (!workoutHRActive) return
        engine?.stopHeartRate()
        workoutHRActive = false
        engine?.syncVitalsHistory()
    }

    /**
     * Ported from iOS's `restartWorkoutHeartRateIfActive()` (RingSyncCoordinator.swift:418).
     * A spot read's stop also tears down the realtime stream (Colmi stops both 0x69 and 0x1e),
     * so if a workout stream is supposed to be running, bring it straight back — the same shape
     * the SmartHealth vendor app uses (it re-issues the identical enable after every
     * interruption: reconnect, sync-end, resume — no delay, no special opcode). The engine's
     * `startHeartRate()` is idempotent (re-arms the keepalive), so this is safe to call
     * liberally; without it the keepalive only sends the *continue* frame, which can't revive a
     * mode the ring already dropped.
     */
    fun restartWorkoutHeartRateIfActive() {
        if (!workoutHRActive || !isConnected) return
        engine?.startHeartRate()
    }

    fun querySleep() {
        if (!isConnected) return
        engine?.querySleep()
    }

    fun findRing() {
        if (!isConnected) return
        if (!client.state.value.activeCapabilities.contains(WearableCapability.FIND_DEVICE)) return
        engine?.findDevice()
    }

    /**
     * Non-destructive: unbind + disconnect + drop the ring from the app. The ring keeps all
     * of its on-device data, stays powered on, and can be re-paired immediately. Does NOT
     * power off or factory-reset — that would wipe a Colmi ring's unsynced history and leave
     * it dark until charged. For a true wipe use [factoryResetRing].
     *
     * [onCleared] runs on the coordinator's own long-lived scope, so callers can do their
     * cleanup (e.g. clearing the device row) without tying it to a screen's lifecycle.
     */
    fun forgetRing(onCleared: suspend () -> Unit) {
        // client.forgetAndWait() sends the protocol unbind, waits for the ack, removes any
        // OS bond, and clears the stored peripheral. That is the whole forget.
        scope.launch {
            stop()
            client.forgetAndWait()
            try {
                onCleared()
            } finally {
                // The coordinator is process-long; resume its event collector so a ring paired
                // without restarting the app still receives sync and measurement events.
                start()
            }
        }
    }

    /**
     * Destructive: wipe the ring's on-device storage. Because a Colmi ring buffers days of
     * unsynced history, we sync the latest data into the app FIRST, then send the factory
     * reset, then forget. Gate this on the ring's FACTORY_RESET capability at the call site.
     * [onProgress] receives a short status for the UI; [onCleared] fires when fully done and
     * runs on the coordinator's own long-lived scope (see [forgetRing]).
     */
    fun factoryResetRing(onProgress: (String) -> Unit = {}, onCleared: suspend () -> Unit) {
        scope.launch {
            if (isConnected) {
                onProgress("Syncing latest data…")
                syncNow()
                // Wait for the history sync to drain (progress reaches 100 or clears), capped
                // so a stale link can never hang the reset.
                kotlinx.coroutines.withTimeoutOrNull(SYNC_BEFORE_RESET_TIMEOUT_MS) {
                    syncProgress.first { it == null || it >= 100 }
                }
                kotlinx.coroutines.delay(800)  // let the final history writes flush
                onProgress("Resetting ring…")
                engine?.factoryReset()
                // The reset frame is only ENQUEUED on the GATT op queue; wait until it has
                // actually been written and acked before forget() tears the queue down —
                // otherwise a slow queue silently swallows the wipe the user confirmed.
                client.awaitOpsFlushed()
            }
            stop()
            client.forgetAndWait()
            try {
                onCleared()
            } finally {
                start()
            }
        }
    }

    fun setGoal(steps: Int) {
        if (isConnected) engine?.setGoal(steps)
        scope.launch {
            val goal = db.userGoalDao().get()
            if (goal != null) {
                db.userGoalDao().upsert(goal.copy(steps = steps, updatedAt = System.currentTimeMillis()))
            } else {
                db.userGoalDao().upsert(UserGoalEntity(steps = steps))
            }
        }
    }

    // MARK: - Spot measurements

    /**
     * Manual spot measurement for rings without the combined 0x23 packet (e.g. Colmi):
     * live HR then live SpO₂, each capability-gated, run sequentially through the same
     * paths the Today/Vitals views read. Each leg returns early once it gets a reading.
     *
     * Triggered only by the Vitals "Measure" button. Matching iOS, connecting does NOT
     * auto-measure — the ring does its own low-power periodic monitoring (pulled in via the
     * history sync), so we never pin the optical sensor on just for connecting.
     */
    suspend fun measureSpot() {
        if (!isConnected) return
        val caps = client.state.value.activeCapabilities
        if (caps.contains(WearableCapability.MANUAL_HEART_RATE)) measureHR()
        if (caps.contains(WearableCapability.MANUAL_SPO2)) measureSpO2()
        if (caps.contains(WearableCapability.MANUAL_BLOOD_PRESSURE)) measureBloodPressure()
        if (caps.contains(WearableCapability.MANUAL_HRV)) measureHRV()
    }

    suspend fun measureHR(): Int? {
        if (hrState == MeasureState.MEASURING) return null
        if (!isConnected) { hrState = MeasureState.FAILED; return null }
        hrState = MeasureState.MEASURING
        // Do NOT clear latestHRValue — it's the live value the workout UI shows, so a new
        // measurement keeps the last reading on screen until a fresh one replaces it.
        hrNoReadingReported = false
        measureNotWorn = false
        hrWindow.begin()
        suppressesLiveHeartRatePersistence = !workoutHRActive

        val spotToken = spot.begin(YCBTMeasurementMode.HEART_RATE)
        engine?.measureHeartRateSpot()
        var result: Int? = null
        try {
            // Sample the full window in 0.5s steps: handle() drops everything inside the 5s warm-up
            // (the ring's cached-echo bpm) and collects the rest. We break out early only where
            // continuing is pointless — and each of those is an abort, not a short-but-usable reading,
            // so none of them report a value (iOS #66).
            var aborted = false
            val steps = (hrMeasureSeconds * 2).toInt()   // 0.5s granularity
            for (i in 0 until steps) {
                // The ring reported "worn incorrectly", or refused the measurement outright.
                if (hrNoReadingReported || spot.isRejected(spotToken)) { aborted = true; break }
                // Ring removed / BLE dropped mid-measure → fail rather than settle a truncated window.
                if (!isConnected) { aborted = true; break }
                // The ring ended the measurement itself (YCBT `04 0e`, issue #59). Its own verdict
                // beats our window: on success settle what we have instead of idling out the rest
                // of a window the ring has already stopped streaming into; on failure, abort.
                val completed = spot.completedSuccessfully(spotToken)
                if (completed != null) { aborted = !completed; break }
                // Contact lost after readings began (ring slipped / hand moved).
                if (hrWindow.contactLost()) { aborted = true; break }
                delay(500)
            }
            result = if (aborted) null else hrWindow.stableValue
        } finally {
            spot.end(spotToken)
            // Always switch the optical sensor off — even if the caller's coroutine is
            // cancelled (e.g. the user navigates away mid-measurement) — or the ring keeps pulsing.
            engine?.stopHeartRate()
            // The stop also tears down the workout's realtime stream; bring it straight back.
            restartWorkoutHeartRateIfActive()
            // Lift the suppression BEFORE publishing, or the one reading worth keeping is the one
            // reading dropped. The sensor is already stopped, so nothing else is arriving.
            suppressesLiveHeartRatePersistence = false
            hrState = if (result != null) MeasureState.DONE else MeasureState.FAILED
            // The measurement's actual output, stored once. A failed measurement stores nothing —
            // "we couldn't read it" is not a heart rate.
            result?.let { settled ->
                PulseEventBus.publishBlocking(
                    PulseEvent.HeartRateSample(bpm = settled, timestamp = java.time.Instant.now())
                )
            }
        }
        return result
    }

    suspend fun measureSpO2(): Int? {
        if (spo2State == MeasureState.MEASURING) return null
        if (!isConnected) { spo2State = MeasureState.FAILED; return null }
        spo2State = MeasureState.MEASURING
        latestSpO2Value = null
        spo2NoReadingReported = false
        measureNotWorn = false
        spo2Window.begin()
        suppressesLiveSpo2Persistence = true
        val spotToken = spot.begin(YCBTMeasurementMode.SPO2)
        engine?.startSpO2()
        var result: Int? = null
        try {
            result = if (engine?.signalsMeasurementCompletion == true) {
                // The ring will say when it is done, so collect the whole run and settle it
                // (issue #59 RC-1). Returning the first plausible sample handed back a reading
                // taken 37 s before the ring finished, with nine better ones still to come.
                settleSpO2(spotToken)
            } else {
                // No completion signal: the first plausible value is all we will ever be sure of,
                // and waiting out the window past it buys nothing. Abort early when the ring
                // reports the run ended with an error (finger off, ring not worn) or refused it.
                pollForValue(spo2MeasureSeconds, { latestSpO2Value }, { spo2NoReadingReported || spot.isRejected(spotToken) })
            }
        } finally {
            spot.end(spotToken)
            engine?.stopSpO2()   // stop the sensor even on cancellation (see measureHR)
            restartWorkoutHeartRateIfActive()   // the stop preempts the workout's HR stream
            // Lift the suppression before publishing, or the one reading worth keeping is dropped.
            suppressesLiveSpo2Persistence = false
            spo2State = if (result != null) MeasureState.DONE else MeasureState.FAILED
            // The measurement's actual output, stored once — and what the card then shows, so the
            // settled value is on screen rather than whichever sample happened to arrive last.
            result?.let { settled ->
                PulseEventBus.publishBlocking(
                    PulseEvent.Spo2Result(value = settled, timestamp = java.time.Instant.now())
                )
            }
        }
        return result
    }

    suspend fun measureBloodPressure(): Pair<Int, Int>? {
        if (bloodPressureState == MeasureState.MEASURING) return null
        if (!isConnected) { bloodPressureState = MeasureState.FAILED; return null }
        bloodPressureState = MeasureState.MEASURING
        latestBloodPressure = null
        bloodPressureNoReadingReported = false
        val spotToken = spot.begin(YCBTMeasurementMode.BLOOD_PRESSURE)
        engine?.startBloodPressure()
        var result: Pair<Int, Int>? = null
        try {
            result = pollForValue(
                BP_MEASURE_SECONDS.toLong(),
                { latestBloodPressure },
                { bloodPressureNoReadingReported || spot.isRejected(spotToken) || spot.completedSuccessfully(spotToken) != null },
            )
        } finally {
            spot.end(spotToken)
            engine?.stopBloodPressure()
            restartWorkoutHeartRateIfActive()
            bloodPressureState = if (result != null) MeasureState.DONE else MeasureState.FAILED
        }
        return result
    }

    suspend fun measureHRV(): Int? {
        if (hrvState == MeasureState.MEASURING) return null
        if (!isConnected) { hrvState = MeasureState.FAILED; return null }
        hrvState = MeasureState.MEASURING
        latestHrvValue = null
        hrvNoReadingReported = false
        val spotToken = spot.begin(YCBTMeasurementMode.HRV)
        engine?.startHRV()
        var result: Int? = null
        try {
            result = pollForValue(
                HRV_MEASURE_SECONDS.toLong(),
                { latestHrvValue },
                { hrvNoReadingReported || spot.isRejected(spotToken) || spot.completedSuccessfully(spotToken) != null },
            )
        } finally {
            spot.end(spotToken)
            engine?.stopHRV()
            restartWorkoutHeartRateIfActive()
            hrvState = if (result != null) MeasureState.DONE else MeasureState.FAILED
        }
        return result
    }

    /**
     * Trigger the combined spot measurement (0x23). The ring replies with 0x24 carrying
     * blood pressure, SpO₂, stress, fatigue and blood sugar in one packet; those decode
     * through the normal event pipeline into Room and onto the Vitals/Today views.
     * Runs for ~45s, matching the official app's combined measurement window.
     */
    suspend fun measureCombined() {
        if (combinedState == MeasureState.MEASURING) return
        if (!isConnected) { combinedState = MeasureState.FAILED; return }
        combinedState = MeasureState.MEASURING
        engine?.startCombinedMeasurement()
        try {
            repeat(combinedMeasureSeconds.toInt()) { delay(1000) }
        } finally {
            engine?.stopCombinedMeasurement()   // stop even on cancellation (see measureHR)
            restartWorkoutHeartRateIfActive()   // the stop preempts the workout's HR stream
            combinedState = MeasureState.DONE
        }
    }

    /**
     * Run the SpO2 leg to its natural end and settle what it collected — the path for a family
     * whose ring reports completion ([RingSyncEngine.signalsMeasurementCompletion]).
     *
     * Mirrors the HR leg's structure: sample the window in 0.5 s steps, break out only where
     * continuing is pointless, and report a value only when the leg was not aborted.
     */
    private suspend fun settleSpO2(spotToken: SpotMeasurementGate.Token): Int? {
        var aborted = false
        val steps = (spo2MeasureSeconds * 2).toInt()   // 0.5s granularity
        for (i in 0 until steps) {
            if (spo2NoReadingReported || spot.isRejected(spotToken)) { aborted = true; break }
            if (!isConnected) { aborted = true; break }
            val completed = spot.completedSuccessfully(spotToken)
            if (completed != null) { aborted = !completed; break }
            delay(500)
        }
        return if (aborted) null else spo2Window.settled
    }

    private suspend fun <T> pollForValue(
        windowSec: Long,
        value: () -> T?,
        abort: () -> Boolean,
    ): T? {
        val steps = (windowSec * 2).toInt()
        repeat(steps) {
            value()?.let { return it }
            if (abort()) return null
            delay(500)
        }
        return value()
    }

    // MARK: - Event handling

    private fun handle(event: PulseEvent) {
        when (event) {
            is PulseEvent.HeartRateSample -> {
                // During a spot measure the window decides what counts: a sample it rejects is the
                // ring's cached echo (a bpm from hours ago, stamped now), so it must not become the
                // live value either — that echo is exactly the number issue #59 saw on the card
                // next to a "couldn't get a steady reading" error. Outside a measurement there is
                // no window to consult and the workout stream's value passes straight through.
                if (hrState == MeasureState.MEASURING) {
                    if (hrWindow.collect(event.bpm)) latestHRValue = event.bpm
                } else {
                    latestHRValue = event.bpm
                }
            }
            is PulseEvent.HeartRateComplete -> {
                if (hrState == MeasureState.MEASURING && !measurementReceivedReading) {
                    hrNoReadingReported = true
                }
            }
            is PulseEvent.Spo2Result -> {
                latestSpO2Value = event.value
                if (spo2State == MeasureState.MEASURING) spo2Window.collect(event.value)
            }
            is PulseEvent.HrvSample -> {
                if (hrvState == MeasureState.MEASURING) latestHrvValue = event.value
            }
            is PulseEvent.BloodPressureSample -> {
                if (bloodPressureState == MeasureState.MEASURING) {
                    latestBloodPressure = event.systolic to event.diastolic
                }
            }
            is PulseEvent.Spo2Complete -> {
                if (spo2State == MeasureState.MEASURING && latestSpO2Value == null) {
                    spo2NoReadingReported = true
                }
            }
            // The ring ended a spot measurement itself and said how it went (YCBT `04 0e`,
            // issue #59). Ownership is by token inside the gate, so this can only ever end the
            // measurement it names, and only while that measurement is actually running.
            is PulseEvent.MeasurementComplete -> {
                spot.noteCompleted(event.mode, event.success)
            }
            is PulseEvent.MeasurementRejected -> {
                spot.noteRejected(event.mode)
                when (event.mode) {
                    YCBTMeasurementMode.HEART_RATE -> hrNoReadingReported = true
                    YCBTMeasurementMode.SPO2 -> spo2NoReadingReported = true
                    YCBTMeasurementMode.BLOOD_PRESSURE -> bloodPressureNoReadingReported = true
                    YCBTMeasurementMode.HRV -> hrvNoReadingReported = true
                }
            }

            // The CRP ring pushes wear state; `worn == false` means no skin contact, so an optical
            // spot measure can't read (issue #29). Fast-fail the in-flight measure instead of idling
            // out the full window, and flag *why* — but only if no reading landed first (a wear-state
            // drop right after a good reading must not turn a success into a failure). Gated to CRP:
            // other families' wear polarity is unverified (RingDecodedEvent.WearingStatus).
            is PulseEvent.WearState -> {
                if (!event.worn && client.state.value.activeDeviceType == RingDeviceType.CRP) {
                    var flagged = false
                    if (hrState == MeasureState.MEASURING && !measurementReceivedReading) {
                        hrNoReadingReported = true; flagged = true
                    }
                    if (spo2State == MeasureState.MEASURING && latestSpO2Value == null) {
                        spo2NoReadingReported = true; flagged = true
                    }
                    if (flagged) measureNotWorn = true
                }
            }
            is PulseEvent.DeviceStateChanged -> {
                when (event.state) {
                    RingConnectionState.CONNECTED -> lastSyncAt = System.currentTimeMillis()
                    RingConnectionState.DISCONNECTED,
                    RingConnectionState.FAILED,
                    RingConnectionState.IDLE -> {
                        clearSyncProgress()
                        startupEngine = null
                    }
                    else -> {}
                }
            }
            // History records stream in oldest→newest; advance the progress bar by mapping
            // each record's timestamp onto the sync window.
            is PulseEvent.ActivityBucket -> advanceSyncProgress(event.timestamp.toEpochMilli())
            is PulseEvent.ActivityUpdate -> advanceSyncProgress(event.timestamp.toEpochMilli())
            is PulseEvent.SleepTimeline -> advanceSyncProgress(event.timestamp.toEpochMilli())
            is PulseEvent.HistoryMeasurement -> {
                advanceSyncProgress(event.timestamp.toEpochMilli())
            }
            is PulseEvent.SyncProgress -> if (event.stage == "done") finishSyncProgressSoon()
            else -> {}
        }
    }

    // MARK: - Sync progress (mirrors official "Sync data X%")

    private fun beginSyncProgress() {
        syncWindowEnd = System.currentTimeMillis()
        syncWindowStart = syncWindowEnd - syncWindowDays * 86_400_000L
        lastAdvanceAt = syncWindowEnd
        syncResetJob?.cancel()
        _syncProgress.value = 0
        // Never let the indicator stick: if no history record advances it for a while
        // (e.g. a stale link, or the ring has nothing to send), wrap it up / hide it.
        syncResetJob = scope.launch {
            while (isActive) {
                delay(SYNC_STALL_CHECK_MS)
                val v = _syncProgress.value ?: break
                if (v >= 100) break
                if (System.currentTimeMillis() - lastAdvanceAt > SYNC_STALL_MS) {
                    if (v > 0) finishSyncProgressSoon() else _syncProgress.value = null
                    break
                }
            }
        }
    }

    private fun advanceSyncProgress(recordEpochMs: Long) {
        // Only while a sync is active — ignore live measurements arriving after sync.
        val current = _syncProgress.value ?: return
        if (syncWindowEnd <= syncWindowStart) return
        val span = (syncWindowEnd - syncWindowStart).toDouble()
        val pct = (((recordEpochMs - syncWindowStart) * 100.0) / span).toInt().coerceIn(0, 100)
        lastAdvanceAt = System.currentTimeMillis()  // data is flowing — keep the bar alive
        if (pct > current) _syncProgress.value = pct
        if (pct >= 100) finishSyncProgressSoon()
    }

    private fun finishSyncProgressSoon() {
        if (_syncProgress.value == null) return
        syncResetJob?.cancel()
        syncResetJob = scope.launch {
            _syncProgress.value = 100
            delay(1500)
            _syncProgress.value = null
        }
    }

    private fun clearSyncProgress() {
        syncResetJob?.cancel()
        _syncProgress.value = null
    }
}

/**
 * The persisted per-device measurement config, or null when the user never saved one.
 * Shared by the foreground coordinator and the background [RingSyncWorker] so BOTH
 * connect handshakes push the user's saved settings. null tells the engine to seed
 * from the ring's own reported settings instead of force-writing all-on defaults —
 * which would silently override ring-side settings (e.g. a 60-min HR interval or
 * temperature off, configured in the official app) on every fresh install.
 */
internal suspend fun loadPersistedMeasurementSettings(db: PulseLoopDatabase): MeasurementSettings? {
    val device = try { db.deviceDao().current() } catch (_: Exception) { null }
        ?: return null
    val config = try { db.deviceMeasurementConfigDao().byDevice(device.id) } catch (_: Exception) { null }
        ?: return null
    return MeasurementSettings(
        hrEnabled = config.hrEnabled,
        hrIntervalMinutes = config.hrIntervalMinutes,
        spo2Enabled = config.spo2Enabled,
        stressEnabled = config.stressEnabled,
        hrvEnabled = config.hrvEnabled,
        temperatureEnabled = config.temperatureEnabled,
    )
}

/**
 * Persist the config the engine seeded from the ring's own reported settings as this
 * device's initial DeviceMeasurementConfig. Only fills the gap — a config the user saved
 * (or a concurrent seed) always wins, and no row is written without a current device.
 */
internal suspend fun persistSeededMeasurementConfig(
    db: PulseLoopDatabase,
    settings: MeasurementSettings,
) {
    val device = try { db.deviceDao().current() } catch (_: Exception) { null } ?: return
    val existing = try { db.deviceMeasurementConfigDao().byDevice(device.id) } catch (_: Exception) { null }
    if (existing != null) return
    try {
        db.deviceMeasurementConfigDao().upsert(
            com.pulseloop.data.entity.DeviceMeasurementConfigEntity(
                deviceId = device.id,
                hrIntervalMinutes = settings.hrIntervalMinutes,
                hrEnabled = settings.hrEnabled,
                spo2Enabled = settings.spo2Enabled,
                stressEnabled = settings.stressEnabled,
                hrvEnabled = settings.hrvEnabled,
                temperatureEnabled = settings.temperatureEnabled,
            )
        )
    } catch (_: Exception) {}
}

/** The persisted user profile as ring-protocol values, or null when no profile saved. */
internal suspend fun loadPersistedUserProfile(
    db: PulseLoopDatabase,
    apiKeyStore: ApiKeyStore?,
): UserProfileValues? {
    val profile = try { db.userProfileDao().get() } catch (_: Exception) { null } ?: return null
    return UserProfileValues.from(
        metric = apiKeyStore?.resolvedUnitSystem != com.pulseloop.settings.UnitSystem.IMPERIAL,
        sex = profile.sex, age = profile.age,
        heightCm = profile.heightCm, weightKg = profile.weightKg,
    )
}

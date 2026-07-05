package com.pulseloop.service

import android.content.Context
import android.content.Intent
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ported from [LiveWorkoutManager] in LiveWorkoutManager.swift.
 * Single orchestrator for a live workout: state machine, GPS, sensor polling, and
 * foreground service notification (replaces Live Activity).
 */
class LiveWorkoutManager(
    private val coordinator: RingSyncCoordinator,
    private val db: PulseLoopDatabase,
    val gps: GpsRouteRecorder,
    private val context: Context,
) {
    data class WorkoutState(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val activeSession: ActivitySessionEntity? = null,
        val elapsedSeconds: Int = 0,
        val distanceMeters: Double = 0.0,
        val latestHeartRate: Int? = null,
        val latestSpO2: Int? = null,
        val hrZone: HeartRateZones.Zone = HeartRateZones.Zone.REST,
        val calories: Double = 0.0,
        val workoutSteps: Int = 0,
        val hrvMs: Double? = null,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(WorkoutState())
    val state = _state.asStateFlow()

    private val polling = WorkoutSensorPollingService(coordinator, db)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickJob: Job? = null

    // Foreground service intent for start/stop
    private val serviceIntent = Intent(context, WorkoutForegroundService::class.java)

    companion object {
        /** An "active" session older than this is a crash leftover, not a workout. */
        private const val STALE_SESSION_MS = 12 * 60 * 60_000L
    }

    // ── Calories (Keytel et al. 2005 — kcal/min from HR + weight + age + sex) ──
    // Keytel is HR-only, so anaerobic work (Weights) gets a MET-based floor:
    // between sets HR falls but the body is still working well above rest.
    private var kcalAccum = 0.0
    private var activeType = "Workout"
    private var profileAge = 35
    private var profileWeightKg = 75.0
    private var profileMale = true

    private suspend fun loadProfile() {
        db.userProfileDao().get()?.let { p ->
            p.age?.let { profileAge = it }
            p.weightKg?.let { profileWeightKg = it }
            p.sex?.let { profileMale = !it.startsWith("f", ignoreCase = true) }
        }
    }

    private fun kcalPerMinute(hr: Int): Double = maxOf(
        keytelKcalPerMinute(hr, profileWeightKg, profileAge, profileMale),
        metFloorKcalPerMinute(activeType, profileWeightKg),
    )

    init {
        polling.onPollCompleted = { refreshNotification() }
    }

    // MARK: - Lifecycle

    suspend fun start(type: String, useGps: Boolean): ActivitySessionEntity {
        val session = ActivitySessionEntity(
            type = type,
            startedAt = System.currentTimeMillis(),
            useGps = useGps,
        )
        db.activitySessionDao().upsert(session)

        kcalAccum = 0.0
        activeType = type
        hrBuffer.clear(); liveHrv = null; workoutSteps = 0
        loadProfile()
        coordinator.startWorkoutHeartRate()
        if (useGps) gps.start(session.id, type)
        polling.start(session.id)
        startForegroundService(session.type)

        startTick(session)
        _state.value = _state.value.copy(isRecording = true, activeSession = session)
        return session
    }

    /**
     * Re-attach to an unfinished session after the Activity/process was recreated
     * (rotation, memory pressure, crash). The session row survives in Room; the
     * in-memory state and loops do not. Elapsed time needs no bookkeeping — the
     * tick derives it from session.startedAt. Sessions too old to plausibly still
     * be running are marked cancelled instead of resurrected.
     */
    suspend fun reattachIfActive() {
        if (_state.value.isRecording) return
        val session = db.activitySessionDao().active() ?: return
        val now = System.currentTimeMillis()
        if (now - session.startedAt > STALE_SESSION_MS) {
            db.activitySessionDao().upsert(session.copy(
                statusRaw = "cancelled", endedAt = session.updatedAt, updatedAt = now,
            ))
            return
        }
        val paused = session.statusRaw == "paused"
        kcalAccum = session.calories ?: 0.0  // restore what pause/finish persisted
        activeType = session.type
        loadProfile()
        if (!paused) {
            coordinator.startWorkoutHeartRate()
            // Permission may have been revoked while we were away — degrade to no-GPS.
            if (session.useGps) try { gps.start(session.id, session.type) } catch (_: SecurityException) {}
            polling.start(session.id)
            startForegroundService(session.type)
            startTick(session)
        }
        _state.value = _state.value.copy(isRecording = true, isPaused = paused, activeSession = session)
    }

    suspend fun pause(session: ActivitySessionEntity) {
        val now = System.currentTimeMillis()
        val updated = session.copy(
            statusRaw = "paused",
            endedAt = now,
            totalPauseSeconds = session.totalPauseSeconds + ((now - session.startedAt) / 1000.0),
            calories = kcalAccum,
            updatedAt = now,
        )
        db.activitySessionDao().upsert(updated)
        gps.stop()
        polling.pause()
        tickJob?.cancel()
        _state.value = _state.value.copy(isPaused = true, activeSession = updated)
        refreshNotification()
    }

    suspend fun resume(session: ActivitySessionEntity) {
        val updated = session.copy(statusRaw = "recording", updatedAt = System.currentTimeMillis())
        db.activitySessionDao().upsert(updated)
        if (updated.useGps) gps.start(updated.id, updated.type)
        polling.resume()
        startTick(updated)
        _state.value = _state.value.copy(isPaused = false, activeSession = updated)
        refreshNotification()
    }

    suspend fun finish(session: ActivitySessionEntity) {
        gps.stop()
        polling.stop()
        stopForegroundService()
        tickJob?.cancel()
        val now = System.currentTimeMillis()
        db.activitySessionDao().upsert(session.copy(
            statusRaw = "finished", endedAt = now,
            distanceMeters = gps.state.value.totalDistance,
            calories = kcalAccum,
            notes = "steps=$workoutSteps",
            updatedAt = now,
        ))
        coordinator.stopWorkoutHeartRate()
        _state.value = WorkoutState()
    }

    suspend fun cancel(session: ActivitySessionEntity) {
        gps.stop()
        polling.stop()
        stopForegroundService()
        tickJob?.cancel()
        db.activitySessionDao().upsert(session.copy(
            statusRaw = "cancelled", endedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        ))
        coordinator.stopWorkoutHeartRate()
        _state.value = WorkoutState()
    }

    // MARK: - Tick loop

    private fun startTick(session: ActivitySessionEntity) {
        tickJob?.cancel()
        // Steps baseline: workout steps = today's total minus what it was at start.
        scope.launch {
            val today = com.pulseloop.util.TimeUtil.startOfTodayLocal()
            stepsBaseline = db.activityDailyDao().byDay(today)?.steps ?: 0
        }
        tickJob = scope.launch {
            var lastHrKick = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = ((now - session.startedAt) / 1000).toInt()
                val distance = gps.state.value.totalDistance
                val hr = coordinator.latestHRValue
                val zone = hr?.let { HeartRateZones.zoneFor(it) } ?: HeartRateZones.Zone.REST
                hr?.let { kcalAccum += kcalPerMinute(it) / 60.0 }  // 1s tick

                // Live HRV proxy over the last ~5 min of stream samples.
                hr?.let { hrBuffer.add(it); if (hrBuffer.size > 300) hrBuffer.removeAt(0) }
                if (elapsed % 30 == 0) liveHrv = sdnnProxy(hrBuffer.toList())

                // Stream watchdog: the ring stops streaming when a start command is
                // lost (unACK'd write) or after its own hiccups. No sample for 20s
                // while connected → re-send the start command, at most every 20s.
                if (coordinator.isConnected &&
                    now - coordinator.latestHRAt > 20_000 && now - lastHrKick > 20_000) {
                    lastHrKick = now
                    coordinator.startWorkoutHeartRate()
                }

                // Workout steps from today's row (cheap read, every 5s).
                if (elapsed % 5 == 0) {
                    val today = com.pulseloop.util.TimeUtil.startOfTodayLocal()
                    val steps = db.activityDailyDao().byDay(today)?.steps ?: 0
                    workoutSteps = (steps - stepsBaseline).coerceAtLeast(0)
                }

                _state.value = _state.value.copy(
                    elapsedSeconds = elapsed,
                    distanceMeters = distance,
                    latestHeartRate = hr,
                    hrZone = zone,
                    calories = kcalAccum,
                    workoutSteps = workoutSteps,
                    hrvMs = liveHrv,
                )

                if (elapsed % 5 == 0) refreshNotification()
                delay(1000)
            }
        }
    }

    private var stepsBaseline = 0
    private var workoutSteps = 0
    private var liveHrv: Double? = null
    private val hrBuffer = mutableListOf<Int>()

    // MARK: - Notification

    private fun refreshNotification() {
        serviceIntent.apply {
            val s = _state.value
            val notification = Intent(context, WorkoutForegroundService::class.java).apply {
                putExtra("activityName", s.activeSession?.type ?: "Workout")
                putExtra("status", if (s.isPaused) "paused" else "recording")
                putExtra("elapsedSeconds", s.elapsedSeconds)
                putExtra("distanceMeters", s.distanceMeters)
                putExtra("heartRate", s.latestHeartRate ?: 0)
            }
            context.startService(notification)
        }
    }

    private fun startForegroundService(name: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(Intent(context, WorkoutForegroundService::class.java))
        } else {
            context.startService(Intent(context, WorkoutForegroundService::class.java))
        }
    }

    private fun stopForegroundService() {
        context.stopService(Intent(context, WorkoutForegroundService::class.java))
    }

    fun destroy() { scope.cancel(); tickJob?.cancel() }
}

/**
 * MET-based kcal/min floor per activity type (Compendium of Physical Activities;
 * kcal/min = MET × 3.5 × kg / 200). HR-based estimates miss anaerobic load, so
 * resistance training gets a floor; aerobic types trust HR (floor 0).
 */
internal fun metFloorKcalPerMinute(type: String, weightKg: Double): Double {
    val met = when (type) {
        "Weights" -> 4.0  // resistance training, moderate effort (Compendium 02052)
        else -> 0.0
    }
    return met * 3.5 * weightKg / 200.0
}

/** Keytel et al. 2005: energy expenditure from HR, floor at 0 (formula goes negative near rest). */
internal fun keytelKcalPerMinute(hr: Int, weightKg: Double, age: Int, male: Boolean): Double {
    val kjPerMin = if (male)
        -55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age
    else
        -20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.0740 * age
    return (kjPerMin / 4.184).coerceAtLeast(0.0)
}

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
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(WorkoutState())
    val state = _state.asStateFlow()

    private val polling = WorkoutSensorPollingService(coordinator, db)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickJob: Job? = null

    // Foreground service intent for start/stop
    private val serviceIntent = Intent(context, WorkoutForegroundService::class.java)

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

        coordinator.startWorkoutHeartRate()
        if (useGps) gps.start(session.id, type)
        polling.start(session.id)
        startForegroundService(session.type)

        startTick(session)
        _state.value = _state.value.copy(isRecording = true, activeSession = session)
        return session
    }

    suspend fun pause(session: ActivitySessionEntity) {
        val now = System.currentTimeMillis()
        val updated = session.copy(
            statusRaw = "paused",
            endedAt = now,
            totalPauseSeconds = session.totalPauseSeconds + ((now - session.startedAt) / 1000.0),
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
        val finished = session.copy(statusRaw = "finished", endedAt = now, updatedAt = now)
        val summarized = recomputeSummary(finished)
        db.activitySessionDao().upsert(summarized)
        ActivityRollup.credit(db, summarized)
        coordinator.stopWorkoutHeartRate()
        _state.value = WorkoutState()
    }

    /** Re-derive calories/HR/SpO2/distance aggregates from whatever the ring and GPS reported
     *  during the workout's window. Mirrors ActivityService.recomputeSummary (iOS #57a) — Android
     *  has no per-session sample-linking table, so this queries the shared measurements store by
     *  [startedAt, endedAt] directly instead of iOS's ActivitySample join. Distance/splits are
     *  recomputed from the persisted GPS points (not the live accumulator) so finish-time totals
     *  always agree with what `RouteDistanceEngine` reports everywhere else (iOS #57b). */
    private suspend fun recomputeSummary(session: ActivitySessionEntity): ActivitySessionEntity {
        val end = session.endedAt ?: System.currentTimeMillis()
        val hrRows = db.measurementDao().range(com.pulseloop.ring.MeasurementKind.HEART_RATE.name, session.startedAt, end)
            .filter { it.value > 0 }
        val spo2Rows = db.measurementDao().range(com.pulseloop.ring.MeasurementKind.SPO2.name, session.startedAt, end)
            .filter { it.value > 0 }
            .sortedBy { it.timestamp }
        val duration = maxOf(0, (((end - session.startedAt) / 1000.0) - session.totalPauseSeconds).toInt())
        val profileEntity = db.userProfileDao().get()

        val gpsPoints = if (session.useGps) db.activityGpsPointDao().forSession(session.id) else emptyList()
        val acceptedCount = gpsPoints.count { it.accepted }
        val distanceMeters = if (session.useGps) {
            RouteDistanceEngine.distanceMeters(gpsPoints, ActivityTrackingProfile.profile(session.type))
        } else null

        val calories = WorkoutMetricsEngine.calories(
            type = session.type,
            durationSeconds = duration,
            distanceMeters = distanceMeters,
            hrSamples = hrRows.map { it.timestamp to it.value },
            profile = MetricsProfileValues(sex = profileEntity?.sex, age = profileEntity?.age, weightKg = profileEntity?.weightKg),
        )
        return session.copy(
            calories = calories,
            distanceMeters = distanceMeters,
            avgHeartRate = hrRows.map { it.value }.average0(),
            maxHeartRate = hrRows.maxOfOrNull { it.value },
            minHeartRate = hrRows.minOfOrNull { it.value },
            avgSpO2 = spo2Rows.map { it.value }.average0(),
            latestSpO2 = spo2Rows.lastOrNull()?.value,
            gpsPointCount = acceptedCount,
            rejectedGpsPointCount = gpsPoints.size - acceptedCount,
            lastGpsPointAt = gpsPoints.maxOfOrNull { it.timestamp },
        )
    }

    private fun List<Double>.average0(): Double? = if (isEmpty()) null else average()

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
        tickJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = ((now - session.startedAt) / 1000).toInt()
                val distance = gps.state.value.totalDistance
                val hr = coordinator.latestHRValue
                val zone = hr?.let { HeartRateZones.zoneFor(it) } ?: HeartRateZones.Zone.REST

                _state.value = _state.value.copy(
                    elapsedSeconds = elapsed,
                    distanceMeters = distance,
                    latestHeartRate = hr,
                    hrZone = zone,
                )

                if (elapsed % 5 == 0) refreshNotification()
                delay(1000)
            }
        }
    }

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

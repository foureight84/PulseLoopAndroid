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
        tickJob?.cancel()
        val now = System.currentTimeMillis()
        val finished = session.copy(statusRaw = "finished", endedAt = now, updatedAt = now)
        val summarized = ActivityAggregates.recompute(db, finished)
        db.activitySessionDao().upsert(summarized)
        ActivityRollup.credit(db, summarized)
        coordinator.stopWorkoutHeartRate()
        finishForegroundService(summarized)
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

    // Replaces the ongoing tracker notification with a "workout complete" summary card that
    // lingers for a while before auto-dismissing, instead of vanishing immediately (mirrors iOS
    // Live Activity's `.after(.now + 10*60)` dismissal policy vs. `.immediate` on cancel/discard).
    private fun finishForegroundService(session: ActivitySessionEntity) {
        val elapsedSeconds = (((session.endedAt ?: System.currentTimeMillis()) - session.startedAt) / 1000).toInt()
        val intent = Intent(context, WorkoutForegroundService::class.java).apply {
            action = WorkoutForegroundService.ACTION_FINISH
            putExtra("activityName", session.type)
            putExtra("elapsedSeconds", elapsedSeconds)
            putExtra("distanceMeters", session.distanceMeters ?: 0.0)
            session.calories?.let { putExtra("calories", it) }
            session.avgHeartRate?.let { putExtra("avgHeartRate", it) }
        }
        context.startService(intent)
    }

    fun destroy() { scope.cancel(); tickJob?.cancel() }
}

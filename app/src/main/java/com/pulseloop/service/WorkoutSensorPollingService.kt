package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySensorPollEntity
import kotlinx.coroutines.*

/**
 * Ported from [WorkoutSensorPollingService] in WorkoutSensorPollingService.swift.
 * Periodically polls the ring for HR (~60s) and SpO2 (~5min) during a workout.
 */
class WorkoutSensorPollingService(
    private val coordinator: RingSyncCoordinator,
    private val db: PulseLoopDatabase,
) {
    var onPollCompleted: (() -> Unit)? = null

    private var sessionId: String? = null
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var nextHRPoll = 0L
    private var nextSpO2Poll = 0L
    private var isPolling = false

    private val hrIntervalMs = 60_000L
    private val spo2IntervalMs = 300_000L
    private val disconnectedRetryMs = 10_000L

    fun start(sessionId: String) {
        this.sessionId = sessionId
        nextHRPoll = System.currentTimeMillis()
        nextSpO2Poll = System.currentTimeMillis()
        launchLoop()
    }

    fun pause() { pollingJob?.cancel(); pollingJob = null }
    fun resume() { launchLoop() }
    fun stop() { pollingJob?.cancel(); pollingJob = null; sessionId = null }

    private fun launchLoop() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()

                if (!isPolling && now >= nextHRPoll) {
                    val didRead = poll("hr") { coordinator.measureHR() }
                    nextHRPoll = System.currentTimeMillis() + if (didRead) hrIntervalMs else disconnectedRetryMs
                }

                if (!isPolling && now >= nextSpO2Poll) {
                    val didRead = poll("spo2") { coordinator.measureSpO2() }
                    nextSpO2Poll = System.currentTimeMillis() + if (didRead) spo2IntervalMs else disconnectedRetryMs
                }

                delay(5000)
            }
        }
    }

    private suspend fun poll(kind: String, measure: suspend () -> Int?): Boolean {
        val sid = sessionId ?: return true
        if (!coordinator.isConnected) {
            record(sid, kind, "skipped", errorMessage = "ring disconnected")
            return false
        }
        isPolling = true
        return try {
            record(sid, kind, "started")
            val value = measure()
            if (value != null) {
                record(sid, kind, "success", value = value.toDouble())
                true
            } else {
                record(sid, kind, "failed", errorMessage = "no reading")
                false
            }
        } finally {
            isPolling = false
            onPollCompleted?.invoke()
        }
    }

    private fun record(sessionId: String, kind: String, status: String, value: Double? = null, errorMessage: String? = null) {
        kotlinx.coroutines.MainScope().launch {
            db.activitySessionDao()  // placeholder - full persistence in Phase 7
        }
    }
}

package com.pulseloop.coach.summaries

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.*

/**
 * Ported from CoachSummaryCoordinator.swift.
 * Subscribes to PulseEventBus and triggers coach-summary regeneration on new
 * data, debounced so streaming packets coalesce into one refresh. The service
 * self-gates (signature + rate limit), so this just nudges it on relevant events.
 * Lives for the app lifetime, like EventPersistenceSubscriber.
 */
class CoachSummaryCoordinator(
    private val db: PulseLoopDatabase,
    private val apiKeyStore: ApiKeyStore,
    /** Optional multi-provider settings, threaded through to the service so
     *  summaries follow the selected coach provider. */
    providerSettings: com.pulseloop.coach.config.CoachProviderSettingsStore? = null,
) {
    private val service = CoachSummaryService(db, apiKeyStore, providerSettings)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamJob: Job? = null
    private var debounceJob: Job? = null
    private var pendingToday = false
    private var pendingSleep = false

    /** Debounce window — long enough for a sleep download / activity sync to settle. */
    private val debounceMs = 30_000L

    fun start() {
        if (streamJob != null) return
        streamJob = scope.launch {
            PulseEventBus.events.collect { event -> handle(event) }
        }
    }

    fun stop() {
        streamJob?.cancel(); streamJob = null
        debounceJob?.cancel(); debounceJob = null
    }

    fun destroy() { scope.cancel() }

    private fun handle(event: PulseEvent) {
        when (event) {
            is PulseEvent.ActivityUpdate,
            is PulseEvent.HeartRateSample,
            is PulseEvent.Spo2Result,
            is PulseEvent.BloodSugarSample,
            is PulseEvent.HistoryMeasurement -> {
                pendingToday = true
                scheduleRefresh()
            }
            is PulseEvent.SleepTimeline -> {
                pendingSleep = true
                pendingToday = true
                scheduleRefresh()
            }
            // A completed full sync is exactly the signal the sleep-card sync gate
            // (iOS #65) waits on — re-check so a night that arrived too early to pass
            // the gate isn't stranded until some unrelated event happens to fire.
            is PulseEvent.SyncProgress -> if (event.stage == "done") {
                pendingSleep = true
                scheduleRefresh()
            }
            else -> {}
        }
    }

    private fun scheduleRefresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            if (!apiKeyStore.coachEnabled) {
                pendingToday = false; pendingSleep = false; return@launch
            }
            if (pendingToday) { pendingToday = false; service.refreshTodayIfNeeded() }
            if (pendingSleep) { pendingSleep = false; service.refreshSleepDayIfNeeded() }
        }
    }
}

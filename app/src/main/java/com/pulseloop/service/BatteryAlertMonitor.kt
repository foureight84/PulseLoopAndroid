package com.pulseloop.service

import android.content.Context
import com.pulseloop.notifications.BatteryNotifications
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Ported from BatteryAlertMonitor.swift (#61a). Subscribes to [PulseEventBus] and fires a local
 * notification when the ring's battery crosses the low (<20%) / critical (<10%) thresholds. The pure
 * [BatteryAlertEngine] plus level-latched state persisted in SharedPreferences means a single
 * crossing fires once per day per threshold. Lives for the app lifetime, started from PulseLoopApp
 * (the Android analog of iOS's app-lifetime monitors).
 */
class BatteryAlertMonitor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore = ApiKeyStore(context)

    fun start() {
        if (job != null) return
        job = scope.launch {
            PulseEventBus.events
                .filterIsInstance<PulseEvent.BatteryLevel>()
                .collect { handle(it.percent, BatteryAlertEngine.currentDateKey()) }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /**
     * Run the crossing engine for a fresh sample and deliver an alert when one fires. Separated from
     * the stream so it's directly exercisable; `dateKey` is threaded in so the per-day reset is
     * deterministic.
     */
    fun handle(percent: Int, dateKey: String) {
        // Absent/default = ON, matching iOS — the alert works out of the box.
        if (!keyStore.batteryAlertsEnabled) return
        val state = loadState()
        val (alert, newState) = BatteryAlertEngine.evaluate(percent, state, dateKey)
        if (newState != state) saveState(newState)
        alert?.let { BatteryNotifications.deliver(context, it) }
    }

    private fun loadState(): BatteryAlertState = BatteryAlertState(
        dateKey = prefs.getString(KEY_DATE, "") ?: "",
        firedLow = prefs.getBoolean(KEY_LOW, false),
        firedCritical = prefs.getBoolean(KEY_CRITICAL, false),
    )

    private fun saveState(state: BatteryAlertState) {
        prefs.edit()
            .putString(KEY_DATE, state.dateKey)
            .putBoolean(KEY_LOW, state.firedLow)
            .putBoolean(KEY_CRITICAL, state.firedCritical)
            .apply()
    }

    private companion object {
        const val PREFS = "pulseloop.batteryalerts"
        const val KEY_DATE = "date_key"
        const val KEY_LOW = "fired_low"
        const val KEY_CRITICAL = "fired_critical"
    }
}

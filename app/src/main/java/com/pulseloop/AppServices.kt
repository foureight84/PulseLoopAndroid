package com.pulseloop

import android.content.Context
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.coach.summaries.CoachSummaryCoordinator
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.notifications.CoachCheckinSettings
import com.pulseloop.notifications.CoachNotificationDataTrigger
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.service.BatteryAlertMonitor
import com.pulseloop.service.EventPersistenceSubscriber
import com.pulseloop.service.GpsRouteRecorder
import com.pulseloop.service.LiveWorkoutManager
import com.pulseloop.service.RingSyncCoordinator
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's long-lived services, one set per process (issue #113).
 *
 * These used to be `remember`ed in `PulseLoopApp`, which lives only as long as the Activity. The
 * Activity is recreated on any configuration change (`MainActivity` handles none itself — a dark
 * theme switch, which Bedtime mode does overnight, is enough), and each recreation built a second
 * `RingBLEClient` while the first, never destroyed, kept the ring's GATT link and PulseLoop's
 * process-wide connection slot. The new client then logged "Connection skipped: another PulseLoop
 * BLE client owns GATT" every second and showed Disconnected over a ring that was still connected.
 * The same recreation started a second persistence subscriber and sync coordinator on the event bus.
 *
 * Built from the application context, so nothing here holds on to a destroyed Activity.
 */
class AppServices private constructor(context: Context) {
    val db: PulseLoopDatabase = PulseLoopDatabase.getInstance(context)
    val bleClient = RingBLEClient(context)
    val apiKeyStore = ApiKeyStore(context)
    val coordinator = RingSyncCoordinator(bleClient, db, apiKeyStore)
    val gpsRecorder = GpsRouteRecorder(context, db)
    val liveWorkout = LiveWorkoutManager(coordinator, db, gpsRecorder, context)

    /** Process-wide, not per-[AppServices]: background workers need it without the UI's services. */
    val persistence = EventPersistenceSubscriber.shared(context)
    val batteryAlerts = BatteryAlertMonitor(context)
    val providerStore = CoachProviderSettingsStore(context)
    val summaryCoordinator = CoachSummaryCoordinator(db, apiKeyStore, providerStore)

    // iOS #94: runs the due check-in slot the moment a full sync completes, so a slot the periodic
    // worker skipped for stale data is delivered a few minutes later instead of being lost.
    val checkinDataTrigger = CoachNotificationDataTrigger(
        context = context,
        checkinSettings = {
            CoachCheckinSettings(
                coachEnabled = apiKeyStore.coachEnabled,
                notificationsEnabled = apiKeyStore.notificationsEnabled,
                apiKey = apiKeyStore.apiKey,
                model = apiKeyStore.model,
                morningHour = apiKeyStore.morningHour,
                eveningHour = apiKeyStore.eveningHour,
            )
        },
    )

    private val startup = StartupGate()

    /** Where the one-shot startup runs: process-long, so a disposed composition can't cancel it. */
    val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * True for the first caller in this process only. The app's one-shot startup (wiring the ring
     * callbacks, starting the subscribers, the stale-state reset, the auto-reconnect) belongs to
     * the process, not to a composition: re-running the stale-state reset after a recreation would
     * mark a live connection DISCONNECTED.
     */
    fun claimStartup(): Boolean = startup.claim()

    companion object {
        @Volatile private var instance: AppServices? = null

        fun get(context: Context): AppServices =
            instance ?: synchronized(this) {
                instance ?: AppServices(context.applicationContext).also { instance = it }
            }

        /** The services if the UI already built them, without building them: a background worker
         *  must not create the app's ring client, whose watchdog would start reconnecting. */
        fun peek(): AppServices? = instance
    }
}

/** Opens once: [claim] is true for its first caller and false for every caller after. */
class StartupGate {
    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

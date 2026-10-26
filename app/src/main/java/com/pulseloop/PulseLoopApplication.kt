package com.pulseloop

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.pulseloop.data.DataRepairs
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.diagnostics.CrashLogger
import com.pulseloop.diagnostics.DiagnosticsSubscriber
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.widgets.WidgetRefreshWorker
import com.pulseloop.widgets.WidgetSnapshotPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Installs the crash logger as early as possible so uncaught
 * exceptions from anywhere in the app are persisted for the next diagnostics export.
 */
class PulseLoopApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // Before any sync can read them: Bedtime tracking no longer needs the schedule (issue #126),
        // so a Bedtime flag left on under a switched-off schedule is cleared rather than reread.
        QuietHoursPrefs.migrateIndependentBedtime(this)
        // Application-scoped so capture runs whether or not the Debug screen is open.
        DiagnosticsSubscriber(PulseLoopDatabase.getInstance(this)).start(appScope)
        // Persistence subscribes from here — not from PulseLoopApp's LaunchedEffect — so that
        // nothing published after the Activity appears (MainActivity.onResume → refreshPhoneSteps
        // → PulseEventBus.publishBlocking) can reach a bus with no persister attached. Before this,
        // the phone read raced the composition's LaunchedEffect and today's PhoneStepsUpdate (and
        // every backfill event) went to Diagnostics alone. Review item 1, PR #98. Idempotent:
        // PulseLoopApp's LaunchedEffect no longer calls persistence.start().
        AppServices.get(this).persistence.start()
        // One-time cleanup of activity totals corrupted by the old bucket handling; runs off the
        // main thread and is prefs-gated so it executes once. Safe to race the first sync — the
        // ring can't connect before this completes a couple of DELETE statements.
        appScope.launch { DataRepairs.runIfNeeded(this@PulseLoopApplication) }
        appScope.launch { DataRepairs.repairSleepDurationsIfNeeded(this@PulseLoopApplication) }
        appScope.launch { DataRepairs.repairLuckRingActivityIfNeeded(this@PulseLoopApplication) }

        // Home-screen widgets (iOS #44): publish the snapshot on every foreground/background
        // edge (the iOS scene-phase triggers — catches goal/unit/profile edits that don't run
        // through the sync pipeline), and keep a ~30 min periodic republish alive so widget
        // staleness and the midnight day-rollover appear without the app opening.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                    WidgetSnapshotPublisher.publish(this)
                }
                // Bedtime/DND (issue #83): reconcile the window log with the current Mode in case
                // the listener was unbound across an edge. The listener records the edges.
                if (event == Lifecycle.Event.ON_START && QuietHoursPrefs.isZenTrackingActive(this)) {
                    com.pulseloop.settings.ZenModeTracker.recordCurrentFilter(this)
                }
            },
        )
        WidgetRefreshWorker.schedule(this)

    }
}

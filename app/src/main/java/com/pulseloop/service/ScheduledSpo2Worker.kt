package com.pulseloop.service

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import com.pulseloop.AppServices
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.ScheduledSpo2Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Starts a real spot SpO₂ on the app's own schedule (issue #103), when the user opted in
 * ([ScheduledSpo2Prefs] — a cadence of its own, 60/120/180 min, independent of the ring's SpO₂
 * interval).
 *
 * One-time work that enqueues the next slot as it finishes, rather than periodic work: a periodic
 * request can't be pinned to the half hour, and its 15-minute floor and flex would put runs on top
 * of the ring's own reading. It is best effort — Doze defers it, and [ScheduledSpo2Slots.isClear]
 * skips a run that lands too close to the ring's reading. A skipped run is retried shortly after
 * the ring's own reading ([ScheduledSpo2Slots.nextClear]), so a Doze deferral that lands the run
 * on the ring's slot does not lose the spot reading for the whole interval. A cancelled chain is
 * revived by [ensureScheduled] from app start and from every [RingSyncWorker] run.
 *
 * The ring stops the measurement the moment the link drops (the reporter's test: start, disconnect
 * 3 s later, LED off, nothing stored), so the run holds the connection until the ring's `04 0e`.
 * It uses the app's own connection when there is one, and otherwise opens a private one the way
 * [RingSyncWorker] does, with its own [RingSyncCoordinator] so the reading is settled and stored
 * exactly as the Vitals button's is.
 *
 * Doze can release this worker and [RingSyncWorker] in the same maintenance window; [isMeasuring]
 * is raised before any database work so a concurrent sync sees it and yields GATT instead of
 * racing for the link. Because the startup sync itself can take minutes, the clearance is
 * re-checked ([ScheduledSpo2Slots.isClearToMeasure]) after the sync, just before the sensor is
 * taken — a run that drifted into the ring's reading during the sync is skipped and retried.
 */
class ScheduledSpo2Worker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "scheduled_spot_spo2"
        private const val TAG = "ScheduledSpo2Worker"
        private const val CONNECT_TIMEOUT_MS = 45_000L
        /** Startup history has to drain before the sensor is taken — the ring serves one thing at a time. */
        private const val SYNC_DRAIN_TIMEOUT_MS = 45_000L

        @Volatile
        var isMeasuring: Boolean = false
            internal set

        /** After a settings change: replace whatever is queued with the next slot, or cancel. */
        suspend fun reschedule(context: Context) {
            enqueueNext(context, ExistingWorkPolicy.REPLACE)
        }

        /** Revive the chain if it died (cancelled, or never started); a queued run is left alone. */
        suspend fun ensureScheduled(context: Context) {
            enqueueNext(context, ExistingWorkPolicy.KEEP)
        }

        internal fun shouldEnqueueNext(isStopped: Boolean): Boolean = !isStopped

        private suspend fun enqueueNext(
            context: Context,
            policy: ExistingWorkPolicy,
            retryAfterSkip: Boolean = false,
        ) {
            val wm = WorkManager.getInstance(context)
            if (!ScheduledSpo2Prefs(context).enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            // A run re-checks the intervals when it starts, so a change after this only moves one run.
            val appInterval = ScheduledSpo2Prefs(context).intervalMinutes
            val ringInterval = withContext(Dispatchers.IO) {
                intervalMinutes(PulseLoopDatabase.getInstance(context))
            } ?: run { wm.cancelUniqueWork(WORK_NAME); return }
            val now = ZonedDateTime.now()
            val nextSlot = ScheduledSpo2Slots.nextSlot(now, appInterval, ringInterval)
            // A run skipped for sitting on the ring's reading is retried just after that reading,
            // unless the next regular slot already comes sooner.
            val target = if (retryAfterSkip) {
                val retryAt = ScheduledSpo2Slots.nextClear(now, ringInterval)
                if (retryAt.isBefore(nextSlot)) retryAt else nextSlot
            } else {
                nextSlot
            }
            val delayMs = Duration.between(now, target).toMillis().coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<ScheduledSpo2Worker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniqueWork(WORK_NAME, policy, request)
        }

        /**
         * The interval the ring measures on, to keep the app's spot SpO₂ clear of.
         * On YCBT rings (including Colmi SmartHealth), background measurements follow the
         * heart-rate schedule (the ring derives SpO₂ during HR measurements and ignores 0x26).
         * Even if the user turns off the ring's SpO₂ toggle, the ring still measures on the HR
         * interval. Supports HR intervals up to 120 min (issue #103).
         */
        internal fun computeRingIntervalMinutes(
            isYcbt: Boolean,
            hrIntervalMinutes: Int,
            spo2IntervalMinutes: Int,
            spo2Enabled: Boolean,
        ): Int? {
            val minutes = if (isYcbt) {
                hrIntervalMinutes.takeIf { it > 0 } ?: 60
            } else {
                if (!spo2Enabled) return null
                spo2IntervalMinutes.takeIf { it > 0 } ?: hrIntervalMinutes
            }
            return minutes.coerceIn(5, 120)
        }

        private suspend fun intervalMinutes(db: PulseLoopDatabase): Int? {
            val settings = loadPersistedMeasurementSettings(db) ?: return null
            val device = db.deviceDao().currentReal()
            val isYcbt = device?.deviceType in setOf(
                com.pulseloop.ring.RingDeviceType.YCBT,
                com.pulseloop.ring.RingDeviceType.COLMI_SMART_HEALTH,
            )
            return computeRingIntervalMinutes(
                isYcbt = isYcbt,
                hrIntervalMinutes = settings.hrIntervalMinutes,
                spo2IntervalMinutes = settings.spo2IntervalMinutes,
                spo2Enabled = settings.spo2Enabled,
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!ScheduledSpo2Prefs(applicationContext).enabled) return Result.success()
        // Raised before any database work: a RingSyncWorker released in the same Doze maintenance
        // window sees isMeasuring and yields immediately instead of grabbing GATT for a
        // redundant sync (issue #103).
        isMeasuring = true
        val db = PulseLoopDatabase.getInstance(applicationContext)
        var skippedForRingReading = false
        try {
            val ringInterval = intervalMinutes(db) ?: return Result.success()
            if (!ScheduledSpo2Prefs.isSupported(db.deviceDao().currentReal())) return Result.success()
            if (!ScheduledSpo2Slots.isClear(ZonedDateTime.now(), ringInterval)) {
                Log.i(TAG, "Skipped: too close to the ring's own reading")
                skippedForRingReading = true
                return Result.success()
            }
            skippedForRingReading = !measureInternal(db, ringInterval)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scheduled SpO2 failed", e)
        } finally {
            isMeasuring = false
            // Append: the next run waits for this one, and REPLACE from inside a running worker
            // would cancel it. Always succeed, since a failed run fails its appended successor.
            // Do not enqueue a successor if stopped/cancelled: WorkManager will retry a stopped
            // worker (which will enqueue its own successor upon completion), and scheduling here
            // would cause duplicate chained runs and schedule drift (issue #103).
            if (shouldEnqueueNext(isStopped)) {
                withContext(NonCancellable) {
                    enqueueNext(
                        applicationContext,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        retryAfterSkip = skippedForRingReading,
                    )
                }
            }
        }
        return Result.success()
    }

    /**
     * Runs the spot SpO₂. Returns `true` when the run completed normally (or was handed off to
     * the foreground app); `false` only when it was skipped after the startup sync because it
     * had drifted too close to the ring's own reading — the caller then schedules a retry via
     * [ScheduledSpo2Slots.nextClear].
     */
    private suspend fun measureInternal(db: PulseLoopDatabase, ringInterval: Int): Boolean {
        // The app's own link, if it has one: a second client can't connect while it holds GATT.
        AppServices.peek()?.coordinator?.let { coordinator ->
            val ran = withContext(Dispatchers.Main) {
                if (!coordinator.isConnected) return@withContext false
                // A workout polls SpO₂ itself, and a measurement in flight holds the sensor.
                if (!coordinator.workoutHRActive) coordinator.measureSpO2Only()
                true
            }
            if (ran) return true
        }
        // The app is opening and owns the connection it is about to make.
        if (isAppForeground()) return true

        val client = RingBLEClient(applicationContext, transientOwner = true)
        if (!client.hasPermissions()) { client.destroy(); return true }
        val coordinator = RingSyncCoordinator(client, db, ApiKeyStore(applicationContext))
        try {
            withContext(Dispatchers.Main) { coordinator.start() }
            // The same startup the app runs on connect: claims the ring and pushes settings, and
            // pulls history while it is at it.
            client.onConnected = { withContext(Dispatchers.Main) { coordinator.runStartupSequence() } }
            client.connectLastKnown()

            // If background sync is currently running, pause our connect timeout countdown so we
            // don't burn the connection budget while sync holds GATT (issue #103).
            var remainingTimeoutMs = CONNECT_TIMEOUT_MS
            var syncWaitMs = 60_000L
            while (!coordinator.isConnected && remainingTimeoutMs > 0) {
                if (isAppForeground()) return true
                if (RingSyncWorker.isSyncing && syncWaitMs > 0) {
                    delay(1000)
                    syncWaitMs -= 1000
                    continue
                }
                if (client.state.value.connectionState == RingConnectionState.DISCONNECTED) {
                    client.connectLastKnown()
                }
                delay(1000)
                remainingTimeoutMs -= 1000
            }
            if (!coordinator.isConnected) return true

            // runStartupSequence opens the progress just after connect; wait for it to open, then
            // to drain (the same wait factoryResetRing uses before it takes the ring).
            withTimeoutOrNull(3_000L) { coordinator.syncProgress.first { it != null } }
            withTimeoutOrNull(SYNC_DRAIN_TIMEOUT_MS) {
                coordinator.syncProgress.first { it == null || it >= 100 }
            }
            delay(800)
            // Hand the ring over to the app if it opened meanwhile; once measuring, finish — the
            // reading is lost if the link drops, and the app only waits for one minute.
            if (isAppForeground() || !coordinator.isConnected) return true
            // The startup sync can take minutes; re-check the clearance so a run that drifted
            // into the ring's own reading while draining history does not fight the ring for the
            // sensor. The finally block tears GATT down cleanly and the caller retries after the
            // ring's reading (issue #103).
            if (!ScheduledSpo2Slots.isClearToMeasure(ZonedDateTime.now(), ringInterval)) {
                Log.i(TAG, "Skipped after sync: too close to the ring's own reading")
                return false
            }
            val value = withContext(Dispatchers.Main) { coordinator.measureSpO2Only() }
            Log.i(TAG, "Scheduled SpO2: ${value ?: "no reading"}")
            // The stop frame is only enqueued; let it reach the ring before the link closes.
            client.awaitOpsFlushed()
            return true
        } finally {
            withContext(NonCancellable) {
                // Cancel watchdog and tear down GATT before stopping coordinator, so that a
                // CancellationException or failure in stop() cannot leave the BLE watchdog
                // reconnecting indefinitely (issue #103).
                val releasedConnection = client.destroy()
                withContext(Dispatchers.Main) { coordinator.stop() }
                if (releasedConnection && !isAppForeground()) {
                    PulseEventBus.publishBlocking(
                        PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, null)
                    )
                }
            }
        }
    }

    private suspend fun isAppForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}

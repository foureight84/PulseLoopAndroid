package com.pulseloop.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.data.PulseLoopDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Runs the Health Connect export pass (docs/health-connect-integration.md Phase 1).
 *
 * A plain one-time worker — no foreground service: a pass is a bounded DB read + chunked writes,
 * and watermarks advance per chunk, so a 10-minute WorkManager timeout mid-backfill simply
 * resumes from the watermark on the next trigger. Triggers (ring sync done, background sync done)
 * are debounced by the 15 s initial delay + [ExistingWorkPolicy.REPLACE]: a burst of sync events
 * coalesces into one pass.
 *
 * Hard gate (plan §3): while [HealthConnectPrefs.BackfillChoice.NOT_ASKED] the export never runs —
 * first-enable asks "Sync all history / Only new data from now on" before anything is written.
 */
class HealthConnectExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = HealthConnectPrefsStore.get(applicationContext)
        val prefs = store.current
        if (!prefs.enabled) return Result.success()
        if (prefs.backfillChoice == HealthConnectPrefs.BackfillChoice.NOT_ASKED) {
            Log.i(TAG, "backfill choice not made yet — export gated")
            return Result.success()
        }
        if (HealthConnectSdk.availability(applicationContext) != HealthConnectAvailability.AVAILABLE) {
            Log.i(TAG, "Health Connect provider unavailable — skipping pass")
            return Result.success()
        }

        val client = HealthConnectClient.getOrCreate(applicationContext)
        return try {
            val exporter = HealthConnectExporter(
                client = client,
                db = PulseLoopDatabase.getInstance(applicationContext),
                store = store,
            )
            // The pass and "Remove PulseLoop data" must never interleave (review pass 5): a pass
            // still inserting while the removal deletes would re-write records the user asked to
            // delete, and its non-suspending setWatermark could land AFTER clearWatermarks(),
            // leaving watermarks that claim deleted records were exported. Cancelling the work is
            // not enough on its own — cancellation is only observed at a suspension point — so
            // both sides take this process-wide lock (the iOS `isSyncing` latch analogue).
            passMutex.withLock {
                val result = exporter.run()
                store.update { it.copy(lastSyncAt = System.currentTimeMillis(), lastSyncSummary = result.summary()) }
                Log.i(TAG, "pass done: ${result.summary()}")
            }
            Result.success()
        } catch (e: SecurityException) {
            // Permission revoked mid-pass: never retry in a loop. Re-check the live granted set
            // now (plan: "a SecurityException from insertRecords should also trigger a re-check")
            // and correct the stored lastGrantedPermissions; the settings screen / next app start
            // then surface a full revocation, and the automatic grow-reset makes any later re-grant
            // re-export regardless.
            Log.w(TAG, "SecurityException — permission revoked mid-pass", e)
            runCatching {
                val live = HealthConnectPermissionReconcile.storedSetOf(
                    client.permissionController.getGrantedPermissions(),
                )
                HealthConnectPermissionReconcile.reconcile(
                    store.current.lastGrantedPermissions.toSet(), live.toSet(), store,
                )
                store.update { it.copy(lastGrantedPermissions = live) }
            }
            Result.success()
        }
    }

    companion object {
        private const val TAG = "HealthConnectExport"
        private const val WORK_NAME = "health_connect_export"

        /**
         * Serializes an export pass against [HealthConnectRemoval.removeAll]. Process-wide: both
         * run as WorkManager workers in the app process. See the use site in [doWork].
         */
        internal val passMutex = Mutex()
        private const val DEBOUNCE_SECONDS = 15L

        /** Debounced enqueue — safe to call from every trigger; a burst coalesces into one pass. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<HealthConnectExportWorker>()
                .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Cancel a pending/in-flight export pass. Called before a removal so the two cannot race
         * (iOS guards removeAllExportedData with an isSyncing latch; Android has no equivalent
         * latch, so we cancel the unique work instead).
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}

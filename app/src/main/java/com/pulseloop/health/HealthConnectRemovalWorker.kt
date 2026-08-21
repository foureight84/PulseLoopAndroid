package com.pulseloop.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Runs "Remove PulseLoop data from Health Connect" ([HealthConnectRemoval.removeAll]).
 *
 * A worker rather than a coroutine in the settings screen's scope (review pass 5). The removal
 * deletes 15 record classes and then resets the export state; run from a
 * `rememberCoroutineScope()`, a back-press mid-delete cancelled it at the next `deleteRecords`
 * suspension, leaving some types deleted, the rest alive, and `clearWatermarks()` never called —
 * watermarks then claim records were exported that no longer exist, and write-only means nothing
 * re-exports them. There is also nowhere to report the outcome once the screen is gone. A worker
 * survives navigation, rotation and process death, and reports through
 * [HealthConnectPrefs.removalStatus].
 *
 * Not retried: the delete is idempotent, but a permanent failure would otherwise loop; the user
 * gets an actionable message and the button back instead.
 */
class HealthConnectRemovalWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = HealthConnectPrefsStore.get(applicationContext)
        if (HealthConnectSdk.availability(applicationContext) != HealthConnectAvailability.AVAILABLE) {
            store.update { it.copy(removalStatus = "Health Connect isn't available right now. Try again.") }
            return Result.success()
        }
        val client = runCatching { HealthConnectClient.getOrCreate(applicationContext) }.getOrNull()
        if (client == null) {
            store.update { it.copy(removalStatus = "Could not reach Health Connect. Try again.") }
            return Result.success()
        }
        return try {
            val result = HealthConnectRemoval.removeAll(applicationContext, client, store)
            val message = when {
                result.failedTypes > 0 ->
                    "Removed PulseLoop data, but ${result.failedTypes} type(s) could not be deleted — try again."
                result.deletedTypes == 0 ->
                    "Nothing to remove: PulseLoop has no write permissions left, so it can't delete " +
                        "what it wrote. Delete it in the Health Connect app, or re-grant and try again."
                else -> "Removed PulseLoop data from Health Connect."
            }
            store.update { it.copy(removalStatus = message) }
            Log.i(TAG, "removal done: $message")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "removal failed", e)
            store.update { it.copy(removalStatus = "Could not remove PulseLoop data. Try again.") }
            Result.success()
        }
    }

    companion object {
        private const val TAG = "HealthConnectExport"
        private const val WORK_NAME = "health_connect_removal"

        /**
         * Enqueue a removal and mark it in progress. [ExistingWorkPolicy.KEEP] so a double-tap
         * cannot start two concurrent removals; the running one already deletes everything.
         */
        fun enqueue(context: Context) {
            val appContext = context.applicationContext
            HealthConnectPrefsStore.get(appContext)
                .update { it.copy(removalStatus = HealthConnectPrefs.REMOVAL_IN_PROGRESS) }
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HealthConnectRemovalWorker>().build(),
            )
        }
    }
}

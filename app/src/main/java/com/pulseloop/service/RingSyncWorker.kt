package com.pulseloop.service

import android.content.Context
import androidx.work.*
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic task that connects to the ring, pulls data, and disconnects.
 * Matches the official JRing app's background sync behavior.
 * Runs every ~30 minutes (minimum WorkManager interval with flex).
 */
class RingSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "ring_background_sync"
        private const val SYNC_TIMEOUT_SECONDS = 45L
        private const val TAG = "RingSyncWorker"

        /** Schedule periodic background ring sync. Call from MainActivity. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RingSyncWorker>(
                30, TimeUnit.MINUTES,  // repeat interval
                15, TimeUnit.MINUTES,  // flex interval
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val keyStore = ApiKeyStore(applicationContext)
        val db = PulseLoopDatabase.getInstance(applicationContext)

        // Only sync if a ring was previously paired
        val device = db.deviceDao().current()
        if (device == null) return Result.success()

        val bleClient = RingBLEClient(applicationContext)

        return try {
            withTimeout(SYNC_TIMEOUT_SECONDS * 1000L) {
                // Connect to last-known ring
                if (!bleClient.hasPermissions()) return@withTimeout Result.success()

                var connected = false
                bleClient.onConnected = {
                    connected = true
                    // Run startup sync to pull activity, HR, sleep, etc.
                    val engine = bleClient.syncEngine
                    engine?.runStartup()
                }

                bleClient.connectLastKnown()

                // Wait for connection and data to arrive
                var waited = 0L
                while (!connected && waited < 20_000L) {
                    delay(1000)
                    waited += 1000
                }

                if (!connected) {
                    bleClient.disconnect()
                    return@withTimeout Result.success()
                }

                // Let data stream in for a few seconds after connect
                delay(15_000L)

                // Disconnect cleanly
                bleClient.disconnect()
                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

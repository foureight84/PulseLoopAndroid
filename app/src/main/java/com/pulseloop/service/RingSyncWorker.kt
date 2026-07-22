package com.pulseloop.service

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // The foreground app owns the live GATT. An overdue periodic job can start at the same
        // moment as MainActivity after process launch; opening a second client interleaves both
        // command queues and lets this worker's cleanup disconnect the UI-owned session.
        if (isAppForeground()) return Result.success()

        val keyStore = ApiKeyStore(applicationContext)
        val db = PulseLoopDatabase.getInstance(applicationContext)

        // Only sync if a REAL ring was previously paired — the seeded demo device row
        // must not trigger background BLE connects.
        val device = db.deviceDao().currentReal()
        if (device == null) return Result.success()

        val bleClient = RingBLEClient(applicationContext, transientOwner = true)

        // Load the persisted measurement config + profile up front so the connect
        // handshake pushes the user's saved settings. Null (never saved) makes the
        // engine seed from the ring's own pref reads instead of force-writing
        // defaults, so ring-side settings from the official app are preserved.
        val measurementSettings = loadPersistedMeasurementSettings(db)
        val profileValues = loadPersistedUserProfile(db, keyStore)

        return try {
            withTimeout(SYNC_TIMEOUT_SECONDS * 1000L) {
                // Connect to last-known ring
                if (!bleClient.hasPermissions()) return@withTimeout Result.success()

                var connected = false
                bleClient.onConnected = {
                    connected = true
                    // Run startup sync to pull activity, HR, sleep, etc.
                    val engine = bleClient.syncEngine
                    engine?.setMeasurementSettings(measurementSettings)
                    profileValues?.let { engine?.setUserProfile(it) }
                    engine?.runStartup()
                }

                bleClient.connectLastKnown()

                // Wait for connection and data to arrive
                var waited = 0L
                while (!connected && waited < 20_000L) {
                    delay(1000)
                    waited += 1000
                    if (isAppForeground()) return@withTimeout Result.success()
                }

                if (!connected) {
                    return@withTimeout Result.success()
                }

                // Let data stream in after connect, but yield ownership promptly if the app opens.
                repeat(15) {
                    delay(1000L)
                    if (isAppForeground()) return@withTimeout Result.success()
                }

                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        } finally {
            // This client is worker-owned. Cancel its watchdog as well as closing GATT so it
            // cannot reconnect after doWork() has returned.
            val releasedConnection = bleClient.destroy()
            // A private client must not overwrite a foreground client's state during an ownership
            // race. If the app remains backgrounded, persist the worker's actual teardown.
            if (releasedConnection && !isAppForeground()) {
                PulseEventBus.publishBlocking(
                    PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, null)
                )
            }
        }
    }

    private suspend fun isAppForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}

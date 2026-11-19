package com.pulseloop.mqtt

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class MqttExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = MqttPrefsStore.get(applicationContext).current
        if (!prefs.isConfigured) {
            return Result.success()
        }

        return passMutex.withLock {
            when (MqttExporter.run(applicationContext, isLive = false)) {
                is MqttExporter.Result.Failure ->
                    if (runAttemptCount < 3) Result.retry() else Result.success()
                else -> Result.success()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "pulseloop.mqtt.export"
        private val passMutex = Mutex()

        fun enqueue(context: Context) {
            if (!MqttPrefsStore.get(context).current.isConfigured) return

            val request = OneTimeWorkRequestBuilder<MqttExportWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

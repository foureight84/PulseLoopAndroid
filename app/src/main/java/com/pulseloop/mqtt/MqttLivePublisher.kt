package com.pulseloop.mqtt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MqttLivePublisher {

    private const val THROTTLE_MS = 5_000L
    private const val TAG = "MqttLivePublisher"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val publishMutex = Mutex()
    private val lock = Any()

    private var lastDispatchMs = 0L
    private var pendingJob: Job? = null

    fun publishLive(context: Context) {
        val prefs = MqttPrefsStore.get(context).current
        if (!prefs.isConfigured || !prefs.liveStreamingEnabled) return

        val appContext = context.applicationContext
        synchronized(lock) {
            if (pendingJob?.isActive == true) return
            val now = System.currentTimeMillis()
            val waitMs = (lastDispatchMs + THROTTLE_MS - now).coerceAtLeast(0L)
            pendingJob = scope.launch {
                if (waitMs > 0L) delay(waitMs)
                synchronized(lock) {
                    pendingJob = null
                    lastDispatchMs = System.currentTimeMillis()
                }
                val latestPrefs = MqttPrefsStore.get(appContext).current
                if (!latestPrefs.isConfigured || !latestPrefs.liveStreamingEnabled) return@launch
                try {
                    publishMutex.withLock {
                        MqttExporter.run(appContext, isLive = true)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "Live publish failed", t)
                }
            }
        }
    }

    fun onWorkoutFinished(context: Context) {
        val prefs = MqttPrefsStore.get(context).current
        if (!prefs.isConfigured) return

        val appContext = context.applicationContext
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = null
            lastDispatchMs = System.currentTimeMillis()
        }
        scope.launch {
            try {
                publishMutex.withLock {
                    val useLive = MqttPrefsStore.get(appContext).current.liveStreamingEnabled
                    MqttExporter.run(appContext, isLive = useLive)
                    MqttSessionPool.shared.close()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "Workout finish publish failed", t)
            }
        }
    }

    fun resetPool() {
        scope.launch {
            runCatching { MqttSessionPool.shared.close() }
        }
    }
}

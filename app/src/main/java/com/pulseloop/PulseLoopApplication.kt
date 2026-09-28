package com.pulseloop

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.pulseloop.data.DataRepairs
import com.pulseloop.diagnostics.CrashLogger
import com.pulseloop.widgets.WidgetRefreshWorker
import com.pulseloop.widgets.WidgetSnapshotPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PulseLoopApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        appScope.launch { DataRepairs.runIfNeeded(this@PulseLoopApplication) }
        appScope.launch { DataRepairs.repairSleepDurationsIfNeeded(this@PulseLoopApplication) }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                    WidgetSnapshotPublisher.publish(this)
                }
            },
        )
        WidgetRefreshWorker.schedule(this)
    }
}
package com.pulseloop

import android.app.Application
import com.pulseloop.data.DataRepairs
import com.pulseloop.diagnostics.CrashLogger
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
        // One-time cleanup of activity totals corrupted by the old bucket handling; runs off the
        // main thread and is prefs-gated so it executes once. Safe to race the first sync — the
        // ring can't connect before this completes a couple of DELETE statements.
        appScope.launch { DataRepairs.runIfNeeded(this@PulseLoopApplication) }
    }
}

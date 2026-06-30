package com.pulseloop

import android.app.Application
import com.pulseloop.diagnostics.CrashLogger

/**
 * Application entry point. Installs the crash logger as early as possible so uncaught
 * exceptions from anywhere in the app are persisted for the next diagnostics export.
 */
class PulseLoopApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}

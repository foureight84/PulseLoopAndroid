package com.pulseloop.settings

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Listens for Android Bedtime / Do Not Disturb / Zen mode changes (issue #83).
 */
class ZenModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
            ZenModeTracker.recordCurrentFilter(context)
        }
    }
}

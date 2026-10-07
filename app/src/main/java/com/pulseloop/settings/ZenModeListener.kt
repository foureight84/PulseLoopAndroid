package com.pulseloop.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Records Bedtime / DND / Mode transitions as they happen (issue #83).
 *
 * Replaces a context-registered `ACTION_INTERRUPTION_FILTER_CHANGED` receiver, which never
 * recorded a window in the field: that broadcast goes to registered receivers only, Android 14+
 * queues those while the process is cached, and overnight it always is — so both the on and off
 * edges arrived together in the morning, read the filter as already off, and saved nothing
 * (PulseLoopAndroid #83, two nights on a Pixel / Android 17). A notification listener is bound by
 * the system and gets [onInterruptionFilterChanged] at the transition instead.
 *
 * Needs Notification access, granted by the user in system settings. The component ships
 * disabled ([setEnabled]) so the app is not listed under Notification access until the user opts
 * in. It never reads notifications: every notification type is filtered out in the manifest.
 */
class ZenModeListener : NotificationListenerService() {
    override fun onListenerConnected() {
        // Reconcile on (re)bind: an edge may have passed while we were unbound.
        ZenModeTracker.recordState(this, ZenModeTracker.isZenModeActive(currentInterruptionFilter))
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        ZenModeTracker.recordState(this, ZenModeTracker.isZenModeActive(interruptionFilter))
    }

    override fun onListenerDisconnected() {
        // The system unbinds a listener whose process died; ask to be bound again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(component(this))
        }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, ZenModeListener::class.java)

        fun isAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Enable or disable the listener component; disabled it drops out of Notification access. */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

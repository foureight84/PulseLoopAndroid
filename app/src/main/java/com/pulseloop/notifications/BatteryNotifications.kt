package com.pulseloop.notifications

import com.pulseloop.R
import com.pulseloop.util.L10n
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pulseloop.MainActivity
import com.pulseloop.service.BatteryAlertKind

/**
 * Ported from BatteryAlertMonitor.deliver() in the iOS app (#61a). One-shot local notification for
 * the ring low/critical battery alerts — no coach/LLM coupling. Its own channel, separate from the
 * coach check-in channel, so the user can silence one without the other.
 */
object BatteryNotifications {
    private const val CHANNEL_ID = "ring_battery"

    // Shared id across severities: a critical alert replaces a still-showing low one (iOS uses a
    // shared notification identifier for the same reason).
    private const val NOTIFICATION_ID = 2002

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.channel_battery), NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_battery_desc)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Deliver a battery alert. Silently no-ops if POST_NOTIFICATIONS isn't granted on 13+ — a
     * background monitor can't prompt for it; the settings toggle owns the permission request.
     */
    fun deliver(context: Context, alert: BatteryAlertKind) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val (title, body) = when (alert) {
            is BatteryAlertKind.Low ->
                L10n.get(R.string.battery_low_title) to L10n.get(R.string.battery_low_body, alert.percent)
            is BatteryAlertKind.Critical ->
                L10n.get(R.string.battery_critical_title) to L10n.get(R.string.battery_critical_body, alert.percent)
        }

        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}

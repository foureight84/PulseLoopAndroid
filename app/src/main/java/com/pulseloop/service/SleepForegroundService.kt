package com.pulseloop.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Minimal foreground service that keeps the process alive while
 * [SleepStreamController] holds the overnight 1 Hz stream. Without it the
 * controller's poll loop is a plain background coroutine that Android throttles
 * under Doze — which is why last night's stream came in bursts, not 1 Hz.
 *
 * Type connectedDevice (prerequisite BLUETOOTH_CONNECT, already held): the data
 * source is a connected BLE ring, same classification as the workout service.
 * Start/stop are driven purely by the controller's charging+window gates.
 */
class SleepForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tracking sleep")
            .setContentText("Recording overnight heart rate & SpO₂")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            android.util.Log.e("SleepFGS", "startForeground rejected", e)
            stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sleep Tracking", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Overnight signal recording" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "sleep_tracking"
        private const val NOTIFICATION_ID = 2001
        fun start(context: Context) {
            val i = Intent(context, SleepForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, SleepForegroundService::class.java))
        }
    }
}

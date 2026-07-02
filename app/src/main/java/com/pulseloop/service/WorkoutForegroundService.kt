package com.pulseloop.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pulseloop.R

/**
 * Android foreground service for live workout — replaces iOS Live Activity / Dynamic Island.
 * Shows a persistent notification with live HR, distance, pace, and elapsed time.
 */
class WorkoutForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "workout_live"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.pulseloop.STOP_WORKOUT"
        const val ACTION_PAUSE = "com.pulseloop.PAUSE_WORKOUT"
        const val ACTION_RESUME = "com.pulseloop.RESUME_WORKOUT"
    }

    private var status = "recording"
    private var elapsedSec = 0
    private var distanceM = 0.0
    private var heartRate: Int? = null
    private var activityName = "Workout"

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_PAUSE -> { status = "paused"; updateNotification() }
            ACTION_RESUME -> { status = "recording"; updateNotification() }
        }
        // Never crash-loop again: if the OS rejects the FGS promotion (permission
        // regression, background-start restriction), degrade to no-notification
        // instead of throwing out of onStartCommand on every sticky restart.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            android.util.Log.e("WorkoutFGS", "startForeground rejected — stopping service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun update(
        activityName: String = this.activityName,
        status: String = this.status,
        elapsedSeconds: Int = this.elapsedSec,
        distanceMeters: Double = this.distanceM,
        heartRate: Int? = this.heartRate,
    ) {
        this.activityName = activityName
        this.status = status
        this.elapsedSec = elapsedSeconds
        this.distanceM = distanceMeters
        this.heartRate = heartRate
        updateNotification()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val elapsed = formatElapsed(elapsedSec)
        val dist = if (distanceM >= 1000) "%.1f km".format(distanceM / 1000) else "%.0f m".format(distanceM)
        val hr = heartRate?.let { "$it bpm" } ?: "--"

        val pauseResumeAction = if (status == "paused") {
            NotificationCompat.Action.Builder(android.R.drawable.ic_media_play, "Resume",
                PendingIntent.getService(this, 3, Intent(this, WorkoutForegroundService::class.java).apply { action = ACTION_RESUME },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        } else {
            NotificationCompat.Action.Builder(android.R.drawable.ic_media_pause, "Pause",
                PendingIntent.getService(this, 2, Intent(this, WorkoutForegroundService::class.java).apply { action = ACTION_PAUSE },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$activityName · $status")
            .setContentText("$elapsed · $dist · $hr")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(pauseResumeAction)
            .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_media_previous, "Stop",
                PendingIntent.getService(this, 1, Intent(this, WorkoutForegroundService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Workout Live", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Live workout tracking" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun formatElapsed(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

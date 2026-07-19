package com.pulseloop.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.R
import java.util.concurrent.TimeUnit

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
        const val ACTION_FINISH = "com.pulseloop.FINISH_WORKOUT"
        private const val DISMISS_WORK_NAME = "workout_complete_dismiss"
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
            ACTION_FINISH -> { finishWithSummary(intent); return START_NOT_STICKY }
            else -> applyLiveExtras(intent)
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // The plain (no-action) update path was only ever mutating this instance's fields via the
    // public `update()` method, which nothing called — `LiveWorkoutManager.refreshNotification()`
    // sends its live elapsed/distance/HR as intent extras instead, and those were silently
    // dropped, so the ongoing notification never advanced past its initial "0:00 / -- bpm" state.
    private fun applyLiveExtras(intent: Intent?) {
        intent ?: return
        if (!intent.hasExtra("elapsedSeconds")) return
        activityName = intent.getStringExtra("activityName") ?: activityName
        status = intent.getStringExtra("status") ?: status
        elapsedSec = intent.getIntExtra("elapsedSeconds", elapsedSec)
        distanceM = intent.getDoubleExtra("distanceMeters", distanceM)
        val hr = intent.getIntExtra("heartRate", 0)
        heartRate = if (hr > 0) hr else null
    }

    // Swaps the ongoing tracker notification for a dismissible "workout complete" summary card
    // (mirrors iOS's Live Activity `.after(.now + 10*60)` dismissal policy on a real finish, vs.
    // `.immediate` on cancel/discard, which still goes through `stopService` with no summary).
    private fun finishWithSummary(intent: Intent) {
        val name = intent.getStringExtra("activityName") ?: activityName
        val elapsed = intent.getIntExtra("elapsedSeconds", elapsedSec)
        val distance = intent.getDoubleExtra("distanceMeters", distanceM)
        val calories = if (intent.hasExtra("calories")) intent.getDoubleExtra("calories", 0.0) else null
        val avgHeartRate = if (intent.hasExtra("avgHeartRate")) intent.getDoubleExtra("avgHeartRate", 0.0) else null

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildFinishedNotification(name, elapsed, distance, calories, avgHeartRate))
        stopForeground(STOP_FOREGROUND_DETACH)
        scheduleDismiss()
        stopSelf()
    }

    private fun scheduleDismiss() {
        val request = OneTimeWorkRequestBuilder<WorkoutCompleteDismissWorker>()
            .setInitialDelay(10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            DISMISS_WORK_NAME, ExistingWorkPolicy.REPLACE, request,
        )
    }

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

    private fun buildFinishedNotification(
        name: String,
        elapsedSeconds: Int,
        distanceMeters: Double,
        calories: Double?,
        avgHeartRate: Double?,
    ): Notification {
        val parts = mutableListOf(formatElapsed(elapsedSeconds))
        if (distanceMeters > 0) {
            parts += if (distanceMeters >= 1000) "%.1f km".format(distanceMeters / 1000) else "%.0f m".format(distanceMeters)
        }
        calories?.let { parts += "${it.toInt()} cal" }
        avgHeartRate?.let { parts += "avg ${it.toInt()} bpm" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$name complete")
            .setContentText(parts.joinToString(" · "))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

/** One-off dismissal of the "workout complete" card after its ~10-min lingering window. */
class WorkoutCompleteDismissWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        NotificationManagerCompat.from(applicationContext).cancel(WorkoutForegroundService.NOTIFICATION_ID)
        return Result.success()
    }
}

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
        /** Tag the "workout complete" card is posted under — the dismiss worker cancels by
         *  (tag, id) so it can never hit the ongoing tracker's tag-less card when a new workout
         *  starts inside the 10-min lingering window (second-pass finding #25). */
        const val COMPLETE_NOTIFICATION_TAG = "workout_complete"
        const val ACTION_STOP = "com.pulseloop.STOP_WORKOUT"
        const val ACTION_PAUSE = "com.pulseloop.PAUSE_WORKOUT"
        const val ACTION_RESUME = "com.pulseloop.RESUME_WORKOUT"
        const val ACTION_FINISH = "com.pulseloop.FINISH_WORKOUT"
        const val DISMISS_WORK_NAME = "workout_complete_dismiss"
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
            // The actions never owned workout logic — they used to mutate only this
            // notification's label while the workout kept recording untouched. Now they post
            // through the command bus and LiveWorkoutManager applies the real state change
            // (which then refreshes this card via the extras path). iOS routes its Live
            // Activity buttons through the App Group the same way.
            ACTION_STOP -> { WorkoutCommandBus.post(WorkoutCommandBus.COMMAND_FINISH); return START_STICKY }
            ACTION_PAUSE -> { WorkoutCommandBus.post(WorkoutCommandBus.COMMAND_PAUSE); return START_STICKY }
            ACTION_RESUME -> { WorkoutCommandBus.post(WorkoutCommandBus.COMMAND_RESUME); return START_STICKY }
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
        val sessionId = intent.getStringExtra("sessionId")

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(COMPLETE_NOTIFICATION_TAG, NOTIFICATION_ID,
            buildFinishedNotification(name, elapsed, distance, calories, avgHeartRate, sessionId))
        // REMOVE, not DETACH: the summary card is posted under its own tag, so the tag-less
        // ongoing tracker card is a *distinct* notification that DETACH would leave posted —
        // a stale "recording" card sitting next to the "complete" one (found in emulator
        // verification). REMOVE takes the ongoing card down with the service.
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        val units = com.pulseloop.settings.ApiKeyStore(this).resolvedUnitSystem
        val dist = formatDistance(distanceM, units) ?: "—"
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
            .setContentIntent(contentIntent(sessionId = null))
            .addAction(pauseResumeAction)
            .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_media_play, "Finish",
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
        sessionId: String?,
    ): Notification {
        val units = com.pulseloop.settings.ApiKeyStore(this).resolvedUnitSystem
        val parts = mutableListOf(formatElapsed(elapsedSeconds))
        formatDistance(distanceMeters, units)?.let { parts += it }
        calories?.let { parts += "${it.toInt()} cal" }
        avgHeartRate?.let { parts += "avg ${it.toInt()} bpm" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$name complete")
            .setContentText(parts.joinToString(" · "))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(sessionId))
            .build()
    }

    /** iOS hides sub-50m distances as "—" and renders 2 decimals (WorkoutActivityAttributes). */
    private fun formatDistance(distanceM: Double, units: com.pulseloop.settings.UnitSystem): String? {
        if (distanceM < 50) return null
        return "%.2f %s".format(
            com.pulseloop.settings.UnitConverter.distance(distanceM, units),
            com.pulseloop.settings.UnitConverter.distanceUnit(units),
        )
    }

    /** Tapping a card opens the app (iOS attaches `pulseloop://workout/<id>` to its Live
     *  Activity). The session id rides along for future deep-link routing to the workout
     *  summary; without a nav handler for it yet, this at least gives `autoCancel` a tap to
     *  fire on and brings the app forward (second-pass finding #35). */
    private fun contentIntent(sessionId: String?): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            sessionId?.let { putExtra("workoutSessionId", it) }
        } ?: Intent()
        // Distinct request codes per card: the two cards' PendingIntents must not share one
        // identity, or a re-post of the ongoing card would clobber the finished card's extras.
        val requestCode = if (sessionId != null) 5 else 4
        return PendingIntent.getActivity(
            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
        // Cancel by (tag, id): the summary card is tagged so a stale worker can never hit the
        // ongoing tracker's tag-less card if a new workout started inside the window.
        NotificationManagerCompat.from(applicationContext).cancel(
            WorkoutForegroundService.COMPLETE_NOTIFICATION_TAG,
            WorkoutForegroundService.NOTIFICATION_ID,
        )
        return Result.success()
    }
}

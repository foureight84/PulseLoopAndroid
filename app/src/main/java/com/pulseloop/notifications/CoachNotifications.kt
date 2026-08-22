package com.pulseloop.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.pulseloop.MainActivity
import com.pulseloop.settings.ApiKeyStore
import java.util.concurrent.TimeUnit

/**
 * Ported from CoachNotificationGenerator + CoachNotificationService in the iOS app.
 * Daily AI check-in notifications via WorkManager periodic task. Uses OpenAI to
 * generate personalized check-in text based on the user's ring data.
 */
object CoachNotifications {
    private const val CHANNEL_ID = "coach_checkins"
    private const val DAILY_WORK_NAME = "coach_daily_checkin"
    private const val SLEEP_RETRY_WORK_NAME = "coach_checkin_sleep_retry"
    private const val NOTIFICATION_ID = 2001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Coach Check-ins", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily AI-generated health insights based on your ring data"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Schedule a daily check-in notification. Requires POST_NOTIFICATIONS on Android 13+.
     *
     * The opt-in gate lives HERE, not at the call sites: MainActivity re-runs its
     * permission flows on every launch/resume and calls schedule() whenever they
     * succeed, so a caller-side check would have to be repeated everywhere and one
     * miss silently re-enqueues the daily worker the user turned off. Check-ins are
     * gated on BOTH the coach master toggle and the check-in opt-in (iOS #49).
     */
    fun schedule(context: Context) {
        val keyStore = ApiKeyStore(context)
        if (!keyStore.coachEnabled || !keyStore.notificationsEnabled) {
            // Also clears work a previous build may have left enqueued despite the opt-out.
            cancel(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val request = PeriodicWorkRequestBuilder<CoachNotificationWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(SLEEP_RETRY_WORK_NAME)
    }

    /**
     * One +45min one-off wake to retry a morning check-in that was skipped because
     * last night's sleep hadn't synced yet (iOS #65 submitSleepRetry). Best-effort;
     * the next periodic run also covers the case where this doesn't land.
     */
    fun scheduleSleepRetry(context: Context) {
        val request = OneTimeWorkRequestBuilder<CoachNotificationWorker>()
            .setInitialDelay(45, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SLEEP_RETRY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Show an immediate check-in notification. */
    fun showNow(context: Context, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

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

/**
 * WorkManager worker for the daily coach check-in notification — the Android
 * analog of the iOS BGTask (CoachNotificationScheduler). The entire due-slot
 * body (gates, freshness, generation, record, delivery) lives in
 * [CoachNotificationSlotRunner] — the SAME code the sync-completion data
 * trigger (iOS #94) calls, so the two entry points can never double-send.
 * This worker only re-checks the opt-in at fire time and maps the outcome to
 * a WorkManager result.
 */
class CoachNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CoachNotificationWorker"
    }

    override suspend fun doWork(): Result {
        // Re-check the opt-in at FIRE time, before any notification can be built:
        // periodic work can outlive a toggle-off, so a disabled feature must mean
        // no notification at all — not the generic fallback one.
        val keyStore = ApiKeyStore(applicationContext)
        if (!keyStore.coachEnabled || !keyStore.notificationsEnabled) {
            CoachNotifications.cancel(applicationContext)
            return Result.success()
        }

        // fallbackToForcedSlot: this is the 24h periodic, which fires wherever the cycle lands
        // (not inside a slot window, unlike iOS's scheduler). Fall back to a forced slot so it
        // still delivers the day's check-in; the data trigger (strict, in-window) covers the
        // other slot and the shared (dateKey, slotRaw) dedupe stops either from double-sending.
        val outcome = CoachNotificationSlotRunner.forContext(applicationContext)
            .runDueSlot(fallbackToForcedSlot = true)
        Log.i(TAG, "due slot -> $outcome")
        // Every outcome is a normal completion: the skipped ones are a gate deciding
        // "not now" (the data trigger, a +45min sleep retry, or the next periodic
        // run picks the slot up), not a failure worth retrying.
        return Result.success()
    }
}

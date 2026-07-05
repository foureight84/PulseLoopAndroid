package com.pulseloop.data

import android.content.Context
import com.pulseloop.util.TimeUtil

/**
 * One-time, prefs-gated data repairs run at app start, off the render path.
 * Mirrors the iOS pattern (`ActivityService.migrateInflatedActivityIfNeeded` /
 * `SleepService.migrateSplitSleepSessionsIfNeeded` in PulseServices.swift).
 */
object DataRepairs {
    private const val PREFS = "data_repairs"

    /**
     * Repair of `activity_daily` rows written by the old bucket handling, which routed history
     * buckets through the live max() ratchet and collapsed a past day's total to its single
     * largest bucket. Deletes past ring-written daily rows so they get recomputed cleanly from
     * `activity_buckets` on the next sync (the ring re-serves ~7 days). Today's row is kept —
     * the live cumulative total is correct and re-ratchets on the next update.
     *
     * No sleep counterpart is needed: sleep_sessions are cleared and rebuilt from the ring on
     * every connect (see EventPersistenceSubscriber's CONNECTED handling), so rows split across
     * midnight by the old start-of-day grouping disappear on the first sync after this update.
     */
    suspend fun runIfNeeded(context: Context, db: PulseLoopDatabase = PulseLoopDatabase.getInstance(context)) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "activityBucketRepair.v1"
        if (prefs.getBoolean(key, false)) return
        db.activityDailyDao().clearRingHistoryBefore(TimeUtil.startOfTodayLocal())
        prefs.edit().putBoolean(key, true).apply()
    }
}

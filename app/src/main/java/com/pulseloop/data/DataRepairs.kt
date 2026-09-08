package com.pulseloop.data

import android.content.Context
import com.pulseloop.service.asleepMinutes
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
     * largest bucket. Recomputes each past day that has synced buckets as the sum of its
     * distinct `activity_buckets` rows (the same aggregation the live sync path uses).
     *
     * Days WITHOUT buckets are left untouched: the ring only re-serves ~7 days of history,
     * so deleting older rows would permanently destroy months of step/distance history in
     * exchange for nothing — an undercounted total beats no total. Days inside the re-serve
     * window are additionally self-healed by `applyActivityBucket` on the next sync. Today's
     * row is out of scope — the live cumulative total re-ratchets on the next update.
     *
     * Complete ring sleep records independently replace their waking-day session and remove any
     * overlapping legacy midnight-split parents. Connection events must not clear real sleep:
     * replacement history is asynchronous and may be empty or consumed.
     */
    suspend fun runIfNeeded(context: Context, db: PulseLoopDatabase = PulseLoopDatabase.getInstance(context)) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "activityBucketRepair.v1"
        if (prefs.getBoolean(key, false)) return
        val today = TimeUtil.startOfTodayLocal()
        for (day in db.activityBucketDao().daysBefore(today)) {
            val buckets = db.activityBucketDao().byDay(day)
            if (buckets.isEmpty()) continue
            val existing = db.activityDailyDao().byDay(day) ?: continue
            db.activityDailyDao().upsert(existing.copy(
                steps = buckets.sumOf { it.steps },
                distanceMeters = buckets.sumOf { it.distanceMeters },
                source = "ring_history",
                updatedAt = System.currentTimeMillis(),
            ))
        }
        prefs.edit().putBoolean(key, true).apply()
    }

    /**
     * Restate every stored night's `totalMinutes` as time asleep rather than the span from its
     * start to its end (issue #63). Ring history only reaches back about a week, so a re-sync
     * would leave every older night reading the old way indefinitely — and the two numbers differ
     * by the awake stretches plus, on a night the ring split into two records, the gap between
     * them: 8 h 10 against 6 h 48 on the reporter's night.
     *
     * Recomputed from each session's own stage blocks, which are exact — they are a run-length
     * encoding of a per-minute stage list and are de-overlapped on merge. A session with no blocks
     * left is skipped rather than zeroed: there is nothing to recompute from, and a zero would
     * hide the night entirely (`byDay` and `earliestDay` both filter on `totalMinutes > 0`).
     * Demo rows are repaired too, so a seeded night and a real one report the same kind of number.
     */
    suspend fun repairSleepDurationsIfNeeded(
        context: Context,
        db: PulseLoopDatabase = PulseLoopDatabase.getInstance(context),
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "sleepAsleepMinutesRepair.v1"
        if (prefs.getBoolean(key, false)) return
        val now = System.currentTimeMillis()
        for (session in db.sleepSessionDao().all()) {
            val blocks = db.sleepStageBlockDao().forSession(session.id)
            if (blocks.isEmpty()) continue
            val asleep = asleepMinutes(blocks)
            if (asleep == session.totalMinutes) continue
            db.sleepSessionDao().upsert(
                session.copy(totalMinutes = asleep, updatedAt = now)
            )
        }
        prefs.edit().putBoolean(key, true).apply()
    }
}

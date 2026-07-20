package com.pulseloop.coach.config

/**
 * Ported from CoachNotificationService.sleepDataSynced and
 * CoachSummaryService.refreshSleepDayIfNeeded's sync gates (iOS #65).
 * Pure timing rules so a coach surface never leads with partial/absent sleep
 * data — decides whether last night's sleep is safe to summarize/mention yet.
 */
object CoachSleepSyncGate {
    /** An older session (nothing newer than this) shouldn't block today's check-in. */
    private const val STALE_NIGHT_WINDOW_MS = 36 * 3600_000L

    /**
     * Morning-notification gate: permissive by design (only blocks a *recent*,
     * *actively syncing* night). Passes when there's no session at all (nothing
     * to wait on), the session is stale (>36h old), or there's no device / full
     * -sync stamp (streaming devices that never run a paged history sync).
     */
    fun sleepDataSynced(sessionEndAt: Long?, lastFullSyncAt: Long?, now: Long): Boolean {
        if (sessionEndAt == null) return true
        if (sessionEndAt < now - STALE_NIGHT_WINDOW_MS) return true
        if (lastFullSyncAt == null) return true
        return lastFullSyncAt >= sessionEndAt
    }

    /**
     * Sleep-card generation gate: stricter, two-part. (1) the night must actually
     * be over (30min floor, giving the ring a beat to flush the tail). (2) either
     * a full sync completed at/after the night ended, or — for streaming devices
     * with no full-sync stamp — a 2h-after-wake floor.
     */
    fun sleepDaySafeToSummarize(sessionEndAt: Long, lastFullSyncAt: Long?, now: Long): Boolean {
        if (now < sessionEndAt + 30 * 60_000L) return false
        return if (lastFullSyncAt != null) lastFullSyncAt >= sessionEndAt
        else now >= sessionEndAt + 2 * 3600_000L
    }
}

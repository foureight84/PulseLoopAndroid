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
    fun sleepDaySafeToSummarize(
        sessionEndAt: Long,
        lastFullSyncAt: Long?,
        now: Long,
        nightInProgress: Boolean = false,
    ): Boolean {
        if (now < sessionEndAt + 30 * 60_000L) return false
        // #140: a ring hands a record over only once it closes, so a sync mid-night sees just the
        // first part and the card was written from it (a 1h03m "night" at 02:04). While the
        // wearer's Bedtime / quiet-hours window is still open the night isn't over, whatever the
        // stored end says. Capped so an all-day DND can't hold the card back forever.
        if (nightInProgress && now < sessionEndAt + NIGHT_IN_PROGRESS_CAP_MS) return false
        return if (lastFullSyncAt != null) lastFullSyncAt >= sessionEndAt
        else now >= sessionEndAt + 2 * 3600_000L
    }

    /** Longest a still-open Bedtime window may defer the card past the stored night's end. */
    const val NIGHT_IN_PROGRESS_CAP_MS = 8 * 3600_000L

    /**
     * May an existing sleep card be rewritten? Not when nothing changed; yes once the rate floor
     * has passed; and yes inside the floor when the night got longer (#140), since that is a
     * correction of a card written from a partial night, not churn. The first signature field is
     * the session's asleep minutes ([com.pulseloop.coach.summaries.CoachSummaryContextBuilder.sleepDay]).
     */
    fun sleepCardRewriteDue(
        existingSignature: String,
        existingUpdatedAt: Long,
        newSignature: String,
        now: Long,
        minIntervalMs: Long,
    ): Boolean {
        if (existingSignature == newSignature) return false
        if (now - existingUpdatedAt >= minIntervalMs) return true
        val before = existingSignature.substringBefore('|').toIntOrNull() ?: return false
        val after = newSignature.substringBefore('|').toIntOrNull() ?: return false
        return after > before
    }
}

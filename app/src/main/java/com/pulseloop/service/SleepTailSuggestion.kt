package com.pulseloop.service

import android.content.Context
import com.pulseloop.data.DemoDataPolicy
import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.settings.ZenWindow
import java.time.ZoneId

/**
 * A prompt to extend the ring's last sleep record forward to the end of Android Bedtime (#148).
 *
 * When a YCBT ring closes its sleep record early while Bedtime stays active and no steps were
 * logged before Bedtime ended, the wearer may have fallen back asleep rather than lain awake.
 * Because lying still looks identical to an idle ring sensor, this stays a confirmable prompt
 * that hands off to `SleepRecordEditDialog` (#106) pre-filled with [suggestedEndAt] rather than
 * editing the night silently.
 */
data class SleepTailSuggestion(
    val sessionId: String,
    val sessionDate: Long,
    val run: SleepRecordRun,
    val previousRun: SleepRecordRun?,
    val ringEndedAt: Long,
    val bedtimeEndedAt: Long,
    val suggestedEndAt: Long,
    val gapMinutes: Int,
)

/**
 * Pure detector for an unrecorded sleep tail bounded by Android Bedtime/DND (issue #148).
 *
 * All timing, sync, step-bucket, and record-edit limit rules live here so JVM unit tests exercise
 * the full decision matrix without Android or Room.
 */
object SleepTailSuggestionDetector {
    const val MIN_GAP_MINUTES = 15
    const val MAX_GAP_MINUTES = MAX_OUTWARD_EXTENSION_MINUTES

    /** Activity buckets span 15 minutes from [ActivityBucketEntity.startEpoch]; a bucket starting
     *  in the final 15 minutes before Bedtime turns off straddles waking up and ending Bedtime. */
    const val STEP_BUCKET_TAIL_MARGIN_MS = 15 * 60_000L

    private const val MINUTE_MS = 60_000L

    fun detect(
        daySessions: List<SleepSessionEntity>,
        dayBlocks: Map<String, List<SleepStageBlockEntity>>,
        bedtimeWindows: List<ZenWindow>?,
        stepBuckets: List<ActivityBucketEntity>,
        lastFullSyncAt: Long?,
        isDismissed: (sessionDate: Long, recordStartAt: Long) -> Boolean,
        isEdited: (sessionDate: Long, recordStartAt: Long) -> Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SleepTailSuggestion? {
        if (bedtimeWindows.isNullOrEmpty() || daySessions.isEmpty()) return null

        val realSessions = daySessions
            .filterNot { DemoDataPolicy.isDemo(it.sourceRaw) }
            .sortedBy { it.endAt }
        if (realSessions.isEmpty()) return null

        // Check from latest session backward (an afternoon nap won't match a morning Bedtime tail,
        // while an overlapping later morning session blocks extending an earlier one across it).
        for (session in realSessions.asReversed()) {
            val runs = sleepRecordRuns(dayBlocks[session.id].orEmpty())
            if (runs.isEmpty()) continue
            val lastRun = runs.last()
            val previousRun = runs.getOrNull(runs.lastIndex - 1)

            // Do not second-guess a record the user already extended/edited or explicitly dismissed.
            if (lastRun.addedMinutes > 0) continue
            if (isEdited(session.date, lastRun.recordStartAt)) continue
            if (isDismissed(session.date, lastRun.recordStartAt)) continue

            val matchingWindow = QuietHoursPrefs.nightWindows(bedtimeWindows, session.startAt, zone)
                .filter { w ->
                    val end = w.end
                    end != null && w.start <= lastRun.endAt && end > lastRun.endAt
                }
                .maxByOrNull { it.end!! }
                ?: continue
            val windowEnd = matchingWindow.end ?: continue

            // Never extend across another recorded session of the same day.
            val overlapsOtherSession = realSessions.any { other ->
                other.id != session.id && other.endAt > lastRun.endAt && other.startAt < windowEnd
            }
            if (overlapsOtherSession) continue

            // Wait for a full sync after Bedtime turned off so morning steps and any second ring
            // sleep record have had a chance to import.
            if (lastFullSyncAt != null && lastFullSyncAt < windowEnd) continue

            val rawGapMinutes = ((windowEnd - lastRun.endAt) / MINUTE_MS).toInt()
            if (rawGapMinutes < MIN_GAP_MINUTES || rawGapMinutes > MAX_GAP_MINUTES) continue

            // Any step bucket wholly inside (lastRun.endAt .. windowEnd] shows real wakefulness.
            // Buckets starting before lastRun.endAt belong to the brief get-up when the ring
            // stopped; buckets straddling windowEnd capture turning Bedtime off.
            val hasStepsInGap = stepBuckets.any { b ->
                b.steps > 0 &&
                    b.startEpoch >= lastRun.endAt &&
                    b.startEpoch + STEP_BUCKET_TAIL_MARGIN_MS <= windowEnd
            }
            if (hasStepsInGap) continue

            val limits = recordEditLimits(
                run = lastRun,
                previous = previousRun,
                next = null,
                wakingDay = session.date,
                zone = zone,
            )
            val suggestedEndAt = clampEndPick(windowEnd, limits).at
            if (suggestedEndAt <= lastRun.endAt) continue

            val suggestedGapMinutes = ((suggestedEndAt - lastRun.endAt) / MINUTE_MS).toInt()
            if (suggestedGapMinutes < MIN_GAP_MINUTES) continue

            return SleepTailSuggestion(
                sessionId = session.id,
                sessionDate = session.date,
                run = lastRun,
                previousRun = previousRun,
                ringEndedAt = lastRun.endAt,
                bedtimeEndedAt = windowEnd,
                suggestedEndAt = suggestedEndAt,
                gapMinutes = suggestedGapMinutes,
            )
        }
        return null
    }
}

/**
 * Per-record dismissal persistence for [SleepTailSuggestion] (issue #148).
 *
 * Keys by `sessionDate_recordStartAt` so tapping "No" hides the prompt for that ring record only,
 * while later nights (or another morning record) remain eligible.
 */
object SleepTailSuggestionStore {
    private const val PREFS = "sleep_tail_suggestions"
    private const val KEY_DISMISSED = "dismissed_keys"
    internal const val RETENTION_DAYS = 45L

    private fun key(sessionDate: Long, recordStartAt: Long): String = "${sessionDate}_$recordStartAt"

    fun isDismissed(context: Context, sessionDate: Long, recordStartAt: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = prefs.getStringSet(KEY_DISMISSED, null) ?: return false
        return key(sessionDate, recordStartAt) in keys
    }

    fun dismiss(
        context: Context,
        sessionDate: Long,
        recordStartAt: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Never mutate the StringSet returned by getStringSet; rebuild via pruneKeys first.
        val current = prefs.getStringSet(KEY_DISMISSED, null).orEmpty()
        val updated = pruneKeys(current + key(sessionDate, recordStartAt), nowMillis)
        prefs.edit().putStringSet(KEY_DISMISSED, updated).apply()
    }

    internal fun pruneKeys(
        keys: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Set<String> {
        val cutoff = nowMillis - RETENTION_DAYS * 86_400_000L
        return keys.filterTo(HashSet()) { entry ->
            val datePart = entry.substringBefore('_').toLongOrNull()
            datePart != null && datePart >= cutoff
        }
    }
}

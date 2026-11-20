package com.pulseloop.service

import android.content.Context
import com.pulseloop.data.DemoDataPolicy
import com.pulseloop.data.entity.ActivityBucketEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.settings.ZenWindow
import java.time.ZoneId
import kotlin.math.roundToInt

/** A single recorded ring heart-rate sample inside the suggested extension gap (#148). */
data class TailHeartRateReading(
    val timestamp: Long,
    val bpm: Int,
)

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
    val gapHeartRates: List<TailHeartRateReading> = emptyList(),
    val nightAvgHeartRate: Int? = null,
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

    /** Default 15-minute bucket size (YCBT, Colmi, RWfit JieLi); JRing emits 1-minute buckets. */
    const val DEFAULT_STEP_BUCKET_DURATION_MS = 15 * 60_000L

    /** Activity buckets span 15 minutes from [ActivityBucketEntity.startEpoch]; a bucket starting
     *  in the final 15 minutes before Bedtime turns off straddles waking up and ending Bedtime. */
    const val STEP_BUCKET_TAIL_MARGIN_MS = DEFAULT_STEP_BUCKET_DURATION_MS

    private const val MINUTE_MS = 60_000L

    fun stepBucketDurationMsFor(deviceType: RingDeviceType?): Long =
        if (deviceType == RingDeviceType.JRING) MINUTE_MS else DEFAULT_STEP_BUCKET_DURATION_MS

    fun supportsIntradayStepBuckets(deviceType: RingDeviceType?): Boolean =
        deviceType != RingDeviceType.CRP && deviceType != RingDeviceType.LUCK_RING

    fun detect(
        daySessions: List<SleepSessionEntity>,
        dayBlocks: Map<String, List<SleepStageBlockEntity>>,
        bedtimeWindows: List<ZenWindow>?,
        stepBuckets: List<ActivityBucketEntity>,
        lastFullSyncAt: Long?,
        isDismissed: (sessionDate: Long, recordStartAt: Long) -> Boolean,
        isEdited: (sessionDate: Long, recordStartAt: Long) -> Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        hasIntradayStepBuckets: Boolean = true,
        stepBucketDurationMs: Long = DEFAULT_STEP_BUCKET_DURATION_MS,
        hasDeletedRecordInGap: (sessionDate: Long, gapStart: Long, gapEnd: Long) -> Boolean = { _, _, _ -> false },
    ): SleepTailSuggestion? {
        if (!hasIntradayStepBuckets || bedtimeWindows.isNullOrEmpty() || daySessions.isEmpty()) return null

        val realSessions = daySessions
            .filterNot { DemoDataPolicy.isDemo(it.sourceRaw) }
        if (realSessions.isEmpty()) return null

        val bucketMs = stepBucketDurationMs.coerceAtLeast(MINUTE_MS)
        // Prefer the longest/primary session on days that also hold a short afternoon nap.
        val candidates = realSessions.sortedWith(
            compareByDescending<SleepSessionEntity> { it.totalMinutes }.thenByDescending { it.endAt },
        )

        for (session in candidates) {
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

            // Respect a user's earlier deletion of a trailing phantom record inside Bedtime.
            if (hasDeletedRecordInGap(session.date, lastRun.endAt, windowEnd)) continue

            // Require a completed full sync after Bedtime turned off so morning step buckets and
            // any second sleep record have had a chance to import.
            if (lastFullSyncAt == null || lastFullSyncAt < windowEnd) continue

            val rawGapMinutes = ((windowEnd - lastRun.endAt) / MINUTE_MS).toInt()
            if (rawGapMinutes < MIN_GAP_MINUTES || rawGapMinutes > MAX_GAP_MINUTES) continue

            // At least one full aligned bucket must sit inside [lastRun.endAt, windowEnd] so the
            // prompt never claims "with no steps" across a gap too short/misaligned to observe any.
            val firstAlignedBucket = Math.floorDiv(lastRun.endAt + bucketMs - 1L, bucketMs) * bucketMs
            if (firstAlignedBucket + bucketMs > windowEnd) continue

            // Any non-zero step bucket wholly inside [lastRun.endAt, windowEnd] shows wakefulness;
            // buckets starting before lastRun.endAt belong to the brief get-up when the ring stopped
            // and buckets straddling windowEnd capture turning Bedtime off.
            val hasStepsInGap = stepBuckets.any { b ->
                b.steps > 0 &&
                    b.startEpoch >= lastRun.endAt &&
                    b.startEpoch + bucketMs <= windowEnd
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

            // Never extend across another recorded session of the same day (including up to
            // +59s grid rounding past windowEnd).
            val extensionEnd = maxOf(windowEnd, suggestedEndAt)
            val overlapsOtherSession = realSessions.any { other ->
                other.id != session.id && other.endAt > lastRun.endAt && other.startAt < extensionEnd
            }
            if (overlapsOtherSession) continue

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

    private const val MIN_VALID_BPM = 30
    private const val MAX_VALID_BPM = 220

    /**
     * Enrich [suggestion] with plausible non-demo heart-rate readings strictly before Bedtime ended
     * (`ringEndedAt < ts < min(suggestedEndAt, bedtimeEndedAt)`) and the minute-deduplicated mean
     * HR across `sessionStartAt..ringEndedAt` (#148).
     */
    fun withHeartRateContext(
        suggestion: SleepTailSuggestion,
        sessionStartAt: Long,
        hrSamples: List<MeasurementEntity>,
    ): SleepTailSuggestion {
        val plausible = hrSamples.mapNotNull { sample ->
            if (DemoDataPolicy.isDemo(sample.sourceRaw)) return@mapNotNull null
            if (!sample.value.isFinite()) return@mapNotNull null
            val bpm = sample.value.roundToInt()
            if (bpm !in MIN_VALID_BPM..MAX_VALID_BPM) return@mapNotNull null
            sample.timestamp to bpm
        }

        val gapEnd = minOf(suggestion.suggestedEndAt, suggestion.bedtimeEndedAt)
        val gapReadings = plausible
            .asSequence()
            .filter { (ts, _) -> ts > suggestion.ringEndedAt && ts < gapEnd }
            .sortedBy { (ts, _) -> ts }
            .associateBy { (ts, _) -> ts / MINUTE_MS }
            .values
            .map { (ts, bpm) -> TailHeartRateReading(timestamp = ts, bpm = bpm) }
            .sortedBy { it.timestamp }

        // Deduplicate per minute before averaging so 1 Hz live HR streams do not outweigh hourly ring samples.
        val nightValues = plausible
            .asSequence()
            .filter { (ts, _) -> ts in sessionStartAt..suggestion.ringEndedAt }
            .sortedBy { (ts, _) -> ts }
            .associateBy { (ts, _) -> ts / MINUTE_MS }
            .values
            .map { (_, bpm) -> bpm }
        val nightAvg = if (nightValues.isEmpty()) null else nightValues.average().roundToInt()

        return suggestion.copy(
            gapHeartRates = gapReadings,
            nightAvgHeartRate = nightAvg,
        )
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

package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import com.pulseloop.util.TimeUtil
import java.time.Instant
import java.time.ZoneId

/**
 * Editing a ring sleep record's bounds, including **extending** it over time the ring missed
 * (issue #106). Pure: the edit path (`SleepRecordDeletion.edit`) and the import path
 * (`EventPersistenceSubscriber`, which re-applies a stored edit on every re-sync of the night) both
 * map a record through [editedRecordBlocks], so a re-sync produces exactly the blocks the edit did.
 *
 * The reporter's ring recorded 12:51–5:44 and 6:56–8:07, but they were asleep again from about
 * 5:46; the app counted the hole as 1 h 12 awake (#81). Editing (#82) could only shorten. Minutes an
 * extension adds are stored as [SleepStage.ADDED] blocks — **asleep but unstaged**: in time asleep,
 * out of the stage breakdown and the score, drawn as their own band on the hypnogram. They carry
 * the ring record's `recordStartAt`, so they belong to that record for `sleepRecordRuns`, and the
 * gap they cover stops counting as a between-record waking with no further change.
 */

/** How far a record may be extended beyond the ring's own bounds where no neighbour limits it. */
const val MAX_OUTWARD_EXTENSION_MINUTES = 120

private const val MINUTE_MS = 60_000L

/**
 * The first point on the record's minute grid at or after [t]. A ring record's stages sit on a
 * grid anchored at its declared start (`buildStageBlocks`), and its blocks' `startAt` values are
 * the identity tombstones key on, so an edit snaps to that grid rather than to wall-clock minutes.
 */
internal fun gridCeil(t: Long, origin: Long): Long =
    origin + Math.floorDiv(t - origin + MINUTE_MS - 1, MINUTE_MS) * MINUTE_MS

/** The last point on the record's minute grid at or before [t]. See [gridCeil]. */
internal fun gridFloor(t: Long, origin: Long): Long =
    origin + Math.floorDiv(t - origin, MINUTE_MS) * MINUTE_MS

/**
 * One record's blocks after an edit to [editStart]..[editEnd] — the single function both paths
 * use (issue #106).
 *
 * [blocks] are the record's blocks: the import passes the ring's freshly built ones, the edit path
 * the stored ones. Any [SleepStage.ADDED] block among them is ignored — a previous extension is
 * re-derived from the new bounds, never kept — so editing back inside the ring's bounds removes the
 * added minutes, and re-applying the same edit to its own output is a no-op.
 *
 * The bounds snap inwards onto the grid anchored at [gridOrigin] (the record's declared start):
 * the start up, the end down, so the result never reaches past a limit the caller clamped to.
 * Measured blocks are trimmed to the bounds exactly as a shortening always did; time outside the
 * ring's own measured span becomes one ADDED block on each side that needs one.
 *
 * Null when the bounds keep none of the ring's own minutes — a record made only of added time has
 * nothing to anchor it to, so the edit is refused rather than inventing a night.
 */
fun editedRecordBlocks(
    blocks: List<SleepStageBlockEntity>,
    gridOrigin: Long,
    editStart: Long,
    editEnd: Long,
): List<SleepStageBlockEntity>? {
    val measured = blocks.filterNot { it.stageRaw == SleepStage.ADDED.name }.sortedBy { it.startAt }
    if (measured.isEmpty()) return null
    val start = gridCeil(editStart, gridOrigin)
    val end = gridFloor(editEnd, gridOrigin)
    if (start >= end) return null

    val kept = measured.mapNotNull { b ->
        val keepStart = maxOf(b.startAt, start)
        val keepEnd = minOf(b.startAt + b.durationMinutes * MINUTE_MS, end)
        val minutes = ((keepEnd - keepStart) / MINUTE_MS).toInt()
        if (minutes > 0) b.copy(startAt = keepStart, durationMinutes = minutes) else null
    }
    if (kept.isEmpty()) return null

    val template = measured.first()
    val measuredStart = template.startAt
    val measuredEnd = measured.maxOf { it.startAt + it.durationMinutes * MINUTE_MS }
    fun added(from: Long, until: Long): SleepStageBlockEntity? {
        val minutes = ((until - from) / MINUTE_MS).toInt()
        if (minutes <= 0) return null
        return SleepStageBlockEntity(
            sessionId = template.sessionId,
            startAt = from,
            startMinute = 0, // re-keyed against the session start by whoever writes the row
            durationMinutes = minutes,
            stageRaw = SleepStage.ADDED.name,
            recordStartAt = template.recordStartAt,
        )
    }
    return listOfNotNull(if (start < measuredStart) added(start, measuredStart) else null) +
        kept +
        listOfNotNull(if (end > measuredEnd) added(measuredEnd, end) else null)
}

/** Why an edit bound stops where it does — what the dialog names when a pick goes past it. */
enum class RecordEditLimit {
    /** The previous record of the same night ends here. */
    PREVIOUS_RECORD,
    /** The next record of the same night starts here. */
    NEXT_RECORD,
    /** [MAX_OUTWARD_EXTENSION_MINUTES] past the ring's own bounds, before the night's first record
     *  or after its last. */
    OUTWARD_CAP,
    /** The waking day's 7 PM turnover: further would move the record into another night. */
    NIGHT_BOUNDARY,
    /** The record must keep at least one minute the ring measured. */
    RING_MINUTES,
}

/**
 * How far one record's bounds may move (issue #106). The start may go back to the previous
 * record's end (no cap: that is the gap the ring missed, however long the Bedtime bridge let it
 * be) or, for the night's first record, [MAX_OUTWARD_EXTENSION_MINUTES] before the ring's own
 * start; the end likewise forward. Never across the waking day's 7 PM turnover. Shortening is
 * limited only by keeping one measured minute.
 */
data class RecordEditLimits(
    val gridOrigin: Long,
    val earliestStart: Long,
    val earliestStartLimit: RecordEditLimit,
    val latestEnd: Long,
    val latestEndLimit: RecordEditLimit,
    /** The ring's own measured span inside the record, which a shortening must keep a minute of. */
    val measuredStart: Long,
    val measuredEnd: Long,
)

fun recordEditLimits(
    run: SleepRecordRun,
    previous: SleepRecordRun?,
    next: SleepRecordRun?,
    wakingDay: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): RecordEditLimits {
    val origin = run.recordStartAt
    val measuredStart = run.measuredStartAt
    val measuredEnd = run.measuredEndAt
    val cap = MAX_OUTWARD_EXTENSION_MINUTES * MINUTE_MS
    val (nightStart, nightEnd) = nightWindow(wakingDay, zone)

    val startCandidates = listOf(
        if (previous != null) gridCeil(previous.endAt, origin) to RecordEditLimit.PREVIOUS_RECORD
        else gridCeil(measuredStart - cap, origin) to RecordEditLimit.OUTWARD_CAP,
        gridCeil(nightStart, origin) to RecordEditLimit.NIGHT_BOUNDARY,
    )
    val endCandidates = listOf(
        if (next != null) gridFloor(next.startAt, origin) to RecordEditLimit.NEXT_RECORD
        else gridFloor(measuredEnd + cap, origin) to RecordEditLimit.OUTWARD_CAP,
        gridFloor(nightEnd, origin) to RecordEditLimit.NIGHT_BOUNDARY,
    )
    // A limit never cuts into the record as it already stands (overlapping legacy rows).
    val (earliest, earliestWhy) = startCandidates.maxBy { it.first }
    val (latest, latestWhy) = endCandidates.minBy { it.first }
    return RecordEditLimits(
        gridOrigin = origin,
        earliestStart = minOf(earliest, run.startAt),
        earliestStartLimit = earliestWhy,
        latestEnd = maxOf(latest, run.endAt),
        latestEndLimit = latestWhy,
        measuredStart = measuredStart,
        measuredEnd = measuredEnd,
    )
}

/** A picked bound after the limits: [at] is what to use, [limit] is set when the pick went past one. */
data class ClampedEditPick(val at: Long, val limit: RecordEditLimit?)

/**
 * A start picked in the dialog (a whole minute) placed on the record's grid and held inside
 * [limits]. The grid point inside the picked minute is the one the button then shows, so picking
 * the minute already displayed is never a change and never a message (issue #106, #116).
 */
fun clampStartPick(picked: Long, limits: RecordEditLimits): ClampedEditPick {
    val at = gridCeil(picked - picked % MINUTE_MS, limits.gridOrigin)
    val lastStart = gridFloor(limits.measuredEnd - MINUTE_MS, limits.gridOrigin)
    return when {
        at < limits.earliestStart -> ClampedEditPick(limits.earliestStart, limits.earliestStartLimit)
        at > lastStart -> ClampedEditPick(lastStart, RecordEditLimit.RING_MINUTES)
        else -> ClampedEditPick(at, null)
    }
}

/** The end-side twin of [clampStartPick]. */
fun clampEndPick(picked: Long, limits: RecordEditLimits): ClampedEditPick {
    val at = gridCeil(picked - picked % MINUTE_MS, limits.gridOrigin)
    val firstEnd = gridCeil(limits.measuredStart + MINUTE_MS, limits.gridOrigin)
    return when {
        at > limits.latestEnd -> ClampedEditPick(limits.latestEnd, limits.latestEndLimit)
        at < firstEnd -> ClampedEditPick(firstEnd, RecordEditLimit.RING_MINUTES)
        else -> ClampedEditPick(at, null)
    }
}

/**
 * The span of waking day [wakingDay] (local midnight): from 7 PM the evening before to 7 PM on the
 * day — `TimeUtil.wakingDayLocal`'s turnover, so a record extended inside it keeps its date.
 */
internal fun nightWindow(wakingDay: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val day = Instant.ofEpochMilli(wakingDay).atZone(zone).toLocalDate()
    val hour = TimeUtil.SLEEP_EVENING_BOUNDARY_HOUR
    val start = day.minusDays(1).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    val end = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    return start to end
}

/**
 * Hold edit bounds clear of every block that isn't this record's — the import's guard, and the edit
 * path's against records in *other* sessions of the day, which the dialog doesn't see. The ring's
 * measurement always beats an extension: whichever arrives first, the added minutes give way
 * (`replaceOverlappingSleepBlocks` trims them when a neighbour arrives later).
 */
internal fun clampEditToNeighbours(
    editStart: Long,
    editEnd: Long,
    measuredStart: Long,
    measuredEnd: Long,
    others: List<SleepStageBlockEntity>,
    nightStart: Long,
    nightEnd: Long,
): Pair<Long, Long> {
    val before = others.map { it.startAt + it.durationMinutes * MINUTE_MS }.filter { it <= measuredStart }
    val after = others.map { it.startAt }.filter { it >= measuredEnd }
    val lo = maxOf(before.maxOrNull() ?: Long.MIN_VALUE, nightStart)
    val hi = minOf(after.minOrNull() ?: Long.MAX_VALUE, nightEnd)
    return maxOf(editStart, minOf(lo, measuredStart)) to minOf(editEnd, maxOf(hi, measuredEnd))
}

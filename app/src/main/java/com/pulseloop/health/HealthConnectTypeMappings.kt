package com.pulseloop.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.SleepSessionRecord
import com.pulseloop.ring.SleepStage
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure identity + mapping helpers for the Health Connect export — the Android port of the iOS
 * [HealthKitTypeMappings] sync-identifier scheme (docs/health-connect-integration.md §3
 * "`clientRecordId` scheme"). Deliberately free of [androidx.health.connect.client.HealthConnectClient]
 * so the identity rules — the part that must stay stable or the upsert silently duplicates — are
 * trivially unit-testable.
 *
 * Identity traps this module encodes (plan §3):
 *  - key instantaneous vitals on kind + sample instant, never on [com.pulseloop.data.entity.MeasurementEntity.id]
 *    (random UUID for live rows), so a reading that arrives once live and once via history
 *    collapses onto one record;
 *  - key heart-rate buckets on the local hour start, so a late sample re-upserts the whole hour
 *    instead of appending a second record.
 */
object HealthConnectTypeMappings {

    /** [com.pulseloop.data.entity.MeasurementEntity.sourceRaw] values that must never reach
     *  Health Connect (mirrors iOS, which never exports demo/mock data). */
    val EXCLUDED_SOURCES = setOf("demo", "mock")

    const val HOUR_MS = 3_600_000L

    /** Gadgetbridge: cap a heart-rate series record at 1 000 samples — Google's guidance is to
     *  "avoid creating single, long-duration records; structure data into smaller records". */
    const val MAX_SAMPLES_PER_HR_RECORD = 1000

    /** Gadgetbridge: a gap longer than 15 minutes starts a new series record. */
    const val MAX_HR_GAP_MS = 15 * 60_000L

    // ── clientRecordId builders (plan §3, ported from HealthKitTypeMappings.swift:100-139) ──

    /** `pl-m-<kind>-<epochMs>` — instantaneous vitals. Millisecond precision: live bursts can
     *  emit two readings inside the same second, and a whole-second id would collapse them.
     *  Immutable sample → version is always 1. */
    fun vitalsRecordId(kindKey: String, epochMs: Long): String = "pl-m-$kindKey-$epochMs"

    /**
     * `pl-hr-<hourStartEpochMs>` for a heart-rate hour bucket — or
     * `pl-hr-<hourStartEpochMs>-<segmentIndex>` when the hour's samples split into several series
     * records (see [splitHrSegments]). Segments are rebuilt from scratch on every pass, so the
     * index is deterministic for a given dataset and a re-run upserts the same ids.
     *
     * Known edge (accepted): an hour that starts life as a single segment (plain id) and later
     * gains a >15-min gap re-keys to suffixed ids, leaving the old single record in Health
     * Connect. Write-only means we cannot delete it, and its content is fully re-exported under
     * the new ids, so nothing is lost or double-counted — one superseded record per hour that
     * ever splits (rare: needs a >15-min ring gap inside a local hour).
     */
    fun hrRecordId(hourStartEpochMs: Long, segmentIndex: Int? = null): String =
        if (segmentIndex == null) "pl-hr-$hourStartEpochMs" else "pl-hr-$hourStartEpochMs-$segmentIndex"

    // ── time helpers ──

    /** Local-midnight start of the hour containing [epochMs] (epoch millis). */
    fun hourStartOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone)
            .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            .toInstant().toEpochMilli()

    /** Zone offset at an instant — every record carries the offset it was written in. */
    fun zoneOffsetAt(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): ZoneOffset =
        zone.rules.getOffset(instant)

    // ── plausibility guards (platform validation rejects the insert otherwise) ──

    /** Health Connect enforces 1..300 bpm; 0 means "not measured" on the rings. */
    fun isPlausibleHr(value: Double): Boolean = value in 1.0..300.0

    /** 0 = not measured; below 20 is a contact artifact, not a reading. */
    fun isPlausibleSpO2(value: Double): Boolean = value in 20.0..100.0

    /** Health Connect's own bounds for RMSSD (ms). */
    fun isPlausibleHrvRmssd(value: Double): Boolean = value in 1.0..200.0

    /** Core body temperature, °C — ring sensor range with artifact margin. */
    fun isPlausibleBodyTemperature(value: Double): Boolean = value in 30.0..42.0

    // ── daily activity (Phase 3) ──

    /**
     * `pl-act-<metric>-<dayEpochMs>` — one day-spanning aggregate per metric, where metric is
     * [ACT_STEPS] / [ACT_ENERGY] / [ACT_DIST] (the same three tokens iOS uses in
     * `HealthKitTypeMappings.activitySyncID`). `clientRecordVersion` = the row's `updatedAt`, so a
     * day that gains steps later re-upserts the same record instead of adding a second one.
     */
    fun activityRecordId(metric: String, dayEpochMs: Long): String = "pl-act-$metric-$dayEpochMs"

    const val ACT_STEPS = "steps"
    const val ACT_ENERGY = "energy"
    const val ACT_DIST = "dist"

    /**
     * End instant for a day-spanning record: the last millisecond of the local day, clamped to
     * [nowMs] so "today" never ends in the future (iOS `HealthSyncService.swift:271-274`). Returns
     * `null` when the clamped end is not strictly after [dayStartMs] — every Health Connect
     * `IntervalRecord` constructor rejects `startTime >= endTime`.
     *
     * The next day is computed with [java.time.ZonedDateTime.plusDays], not `+ 86_400_000`, so a
     * DST transition day is 23 or 25 hours as the calendar sees it.
     */
    fun activityDayEndMs(dayStartMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val nextDay = Instant.ofEpochMilli(dayStartMs).atZone(zone).plusDays(1).toInstant().toEpochMilli()
        val end = minOf(nextDay - 1L, nowMs)
        return if (end > dayStartMs) end else null
    }

    // The three guards below take the UNION of the two validators a record passes through: the
    // Jetpack constructor's own `require`s below Android 14, and the platform's `requireInRange`
    // from 14 (U) up (androidx `StepsRecord.kt:44-52` branches on SDK level). Where they disagree
    // — steps floor 1 in Jetpack, 0 on the platform — the stricter bound wins. Following
    // Gadgetbridge, an out-of-range value is DROPPED, never clamped, so one bad row cannot sink
    // its whole 200-record chunk. NaN and infinity fall out of every comparison and are dropped
    // for free. This also honours the write-data guide's "handle zero values" rule: omit the
    // record rather than assert a zero we cannot vouch for.

    /**
     * Steps per record. The platform's range is `1..1_000_000`, but the ceiling here is this
     * app's own corruption threshold: `EventPersistenceSubscriber.kt:374` treats a stored day
     * above 200 000 steps as garbage from the old little-endian live-activity decode and
     * self-heals it on the next sync. Between the bad write and that heal, an export would push
     * a six-figure step day into a store we can never retract it from, so the guard stops where
     * the app's own trust in the number stops.
     */
    fun isPlausibleSteps(count: Long): Boolean = count in 1L..MAX_TRUSTED_DAILY_STEPS

    /** `EventPersistenceSubscriber`'s stale-value threshold, reused as the export ceiling. */
    const val MAX_TRUSTED_DAILY_STEPS = 200_000L

    /**
     * A daily total minus what the workout records carry for the same day. Pure so the subtraction
     * — the part that can silently under-report — is testable without Room.
     *
     * Returns the leftover even when it goes negative; the caller's plausibility guard drops it.
     *
     * **Stale-record decision (Phase 4): a ≤ 0 leftover is dropped, and the resulting stale
     * window is ACCEPTED — no floor-value record is written.** The alternative — overwriting the
     * stale un-netted record with a floor value — cannot be a reliable repair: the overwrite only
     * happens when the day is re-selected (`updatedAt` above the activity watermark), and the
     * stale scenario is precisely a day that stops updating around the netting flip, so the floor
     * would mostly just fabricate a 1 kcal / 1 m value in a store we can never retract, against
     * the write-data guide's own rule to omit zero values. What is left is bounded and one-time:
     * at most the workout's own energy or distance, on a day whose stored daily total a single
     * finished session's figure exceeds, dropped while its pre-netting record is still live. A
     * consumer summing the daily record plus the workout siblings over-counts by exactly that
     * amount for that day, and it cannot grow — the workout record that causes it is versioned
     * (`session.updatedAt`) and upserts in place.
     *
     * Pre-flip staleness (daily records written by a Phase 3 build, before any workout sibling
     * existed) is NOT this window and is repaired properly: the one-time netting-flip reset in
     * [HealthConnectExporter.run] re-exports every day on the first netting-live pass, so each
     * stale un-netted record is overwritten with its netted value under the same clientRecordId.
     * (What that reset cannot repair is exactly the ≤ 0 case: a day that nets to nothing has no
     * record to overwrite the stale one with — write-only, so it stays. Accepted.)
     */
    fun activityLeftover(total: Double, netted: Double): Double = total - netted

    /** Active energy, kcal: platform range `0..1_000_000`; a zero day is not worth a record. */
    fun isPlausibleActiveCalories(kcal: Double): Boolean = kcal > 0.0 && kcal <= 1_000_000.0

    /** Distance, metres: platform range `0..1_000_000` (1 000 km per record). */
    fun isPlausibleDistanceMeters(meters: Double): Boolean = meters > 0.0 && meters <= 1_000_000.0

    /**
     * One finished workout reduced to what daily-aggregate netting needs.
     * [dayStartMs] is the local start-of-day of the session's **start** (iOS keys netting on
     * `startedAt`, so a workout crossing midnight nets entirely against the day it began).
     * [startedAtMs] / [endedAtMs] / [totalPauseSeconds] feed [creditedActiveMinutes] — the
     * credit-eligibility check must see exactly the numbers [com.pulseloop.service.ActivityRollup.credit]
     * sees, or netting subtracts what was never credited.
     */
    data class NettableSession(
        val dayStartMs: Long,
        val calories: Double?,
        val distanceMeters: Double?,
        val useGps: Boolean,
        val startedAtMs: Long,
        val endedAtMs: Long?,
        val totalPauseSeconds: Double,
    )

    /** Per-day workout kcal / metres to subtract from the daily aggregates. */
    data class WorkoutNetting(
        val kcalByDay: Map<Long, Double>,
        val metersByDay: Map<Long, Double>,
    ) {
        fun kcal(dayStartMs: Long): Double = kcalByDay[dayStartMs] ?: 0.0
        fun meters(dayStartMs: Long): Double = metersByDay[dayStartMs] ?: 0.0

        companion object { val EMPTY = WorkoutNetting(emptyMap(), emptyMap()) }
    }

    /**
     * Port of iOS `HealthSyncService.workoutNetting` (`HealthSyncService.swift:315-331`): the
     * per-day finished-workout totals that Phase 4 will write as their own records, so a Health
     * Connect consumer summing the day aggregate + the workout does not count them twice.
     *
     * Two deliberate Android differences, both forced by this app's data model rather than taste:
     *
     *  - **Energy is netted even though `ActivityRollup.credit` never adds workout kcal to the
     *    daily row.** What makes netting correct here is the *ring*: when `activity_daily.calories`
     *    holds a device-reported figure it is the ring's own all-day active energy, which already
     *    covers the minutes the workout was running. This is exactly iOS's reason, and iOS's rule
     *    (all finished sessions) ports unchanged. Narrower than it looks: the app itself only
     *    treats that column as device-reported when `source != "ring_history" && calories > 0`
     *    (`DailyCalorieEstimator.deviceReportedCalories`), and the estimated figure it falls back
     *    to for other days already has workout energy folded in — which is why the exporter writes
     *    the raw column (iOS parity) rather than `effectiveActiveCalories`.
     *  - **Distance netting drops iOS's walk/run type filter.** iOS needs it because HealthKit
     *    splits distance across `.distanceWalkingRunning` / `.distanceCycling`, so netting a ride
     *    out of the walking total would under-count. Health Connect has a single `DistanceRecord`
     *    type that every workout's distance lands in, so restricting to walk/run here would leave
     *    a GPS ride's metres counted twice. The netting set is therefore
     *    [NettableSession.useGps] sessions of any type — the set `ActivityRollup.credit` folds
     *    into the daily row (`ActivityRollup.kt:20-32`).
     *
     * **Keeping "netted set == credited set" exact (the Phase 3 imperfections, resolved):**
     *  1. `ActivityRollup.credit` early-returns for a session with no full active minute — its
     *     exact condition is [creditedActiveMinutes] — and a session `credit` never folded into
     *     the daily row must not be subtracted either. [workoutNetting] therefore skips the same
     *     sessions.
     *  2. `EventPersistenceSubscriber.applyActivityBucketAtomic` *overwrites* a past day's
     *     `distanceMeters` with the ring's bucket sum (the ratchet only applies to today),
     *     discarding the credited GPS metres. On the export side that is self-healing: the
     *     overwrite stamps `updatedAt`, so the day is re-selected and re-exported with the
     *     ring-only leftover, and the workout's distance sibling restores the credited metres —
     *     a consumer summing daily + workout lands on the ring's own day total, the correct
     *     reading for a past day. The only residual is a leftover that nets to ≤ 0, which is the
     *     accepted stale window documented on [activityLeftover].
     *
     * Callers pass only sessions that are `finished` with a non-null `endedAt`, mirroring iOS.
     */
    fun workoutNetting(sessions: List<NettableSession>): WorkoutNetting {
        val kcal = HashMap<Long, Double>()
        val meters = HashMap<Long, Double>()
        for (s in sessions) {
            // ActivityRollup.credit skips this session (no full active minute) → its energy and
            // metres were never credited into the daily row → netting must not subtract them.
            if (creditedActiveMinutes(s.startedAtMs, s.endedAtMs, s.totalPauseSeconds) <= 0) continue
            val k = s.calories
            if (k != null && k > 0.0) kcal[s.dayStartMs] = (kcal[s.dayStartMs] ?: 0.0) + k
            val m = s.distanceMeters
            if (s.useGps && m != null && m > 0.0) meters[s.dayStartMs] = (meters[s.dayStartMs] ?: 0.0) + m
        }
        return WorkoutNetting(kcal, meters)
    }

    /**
     * Port of `ActivityRollup.minutesFor` — the same arithmetic and the same `minutes <= 0`
     * early-return that decide whether [com.pulseloop.service.ActivityRollup.credit] folds a
     * session into the daily row. Netting subtracts exactly what `credit` added, so the two must
     * agree on credit eligibility (Phase 3 imperfection #1, resolved).
     */
    fun creditedActiveMinutes(startedAtMs: Long, endedAtMs: Long?, totalPauseSeconds: Double): Int {
        val ended = endedAtMs ?: return 0
        return maxOf(0, (((ended - startedAtMs) / 1000.0) - totalPauseSeconds).toInt()) / 60
    }

    // ── workouts (Phase 4; plan §3 identity table + Phase 4 spec) ──

    /** Sibling-record tokens for [workoutChildRecordId] (plan §3 identity table). */
    const val WK_ENERGY = "energy"
    const val WK_DIST = "dist"

    /**
     * `pl-wk-<sessionId>` — the session's [ExerciseSessionRecord]. The session id is the stable
     * Room primary key: unlike sleep blocks, a workout row is never replaced wholesale (an edit
     * or a post-finish vitals backfill bumps `updatedAt` in place), so it is a safe upsert key
     * with no suffix scheme. Version = `session.updatedAt`, set by the exporter.
     */
    fun workoutRecordId(sessionId: String): String = "pl-wk-$sessionId"

    /**
     * `pl-wk-<sessionId>-<kind>` — a sibling energy/distance record over the session window, for
     * [WK_ENERGY] / [WK_DIST] (plan §3 identity table). Same version as the session record.
     */
    fun workoutChildRecordId(sessionId: String, kind: String): String = "pl-wk-$sessionId-$kind"

    /**
     * PulseLoop activity type → [ExerciseSessionRecord] constant (plan Phase 4 exercise-type map,
     * the shape of `strava/StravaSportMapping`). Unknown types degrade to OTHER_WORKOUT — the
     * session is real effort, just unclassified (same fallback rule as [sleepStageType]).
     */
    fun exerciseType(type: String): Int = when (type) {
        "walk" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "run" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "cycle" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "gym" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        "squash" -> ExerciseSessionRecord.EXERCISE_TYPE_SQUASH
        "yoga" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        "dance" -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        "hike" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        "sport" -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    /** What one finished session may export to (Phase 4 guards; the DAO already filters to
     *  `statusRaw = 'finished' AND endedAt IS NOT NULL`). */
    enum class WorkoutSelection { EXPORT, /** zero/negative duration — can never become exportable. */ INVALID,
        /** `endedAt` in the future (clock skew) — retry next run, never leapfrog. */ FUTURE }

    /**
     * Per-session export decision (plan Phase 4 guards: `endedAt > startedAt`, not future),
     * mirroring iOS `exportWorkouts`: a zero/negative duration can never become exportable, while
     * a future-dated end is transient clock skew — the pass must stop at it and retry later
     * instead of skipping it (the watermark may not leapfrog a session whose end has not happened).
     */
    fun selectWorkoutSession(startedAtMs: Long, endedAtMs: Long?, nowMs: Long): WorkoutSelection = when {
        endedAtMs == null || endedAtMs <= startedAtMs -> WorkoutSelection.INVALID
        endedAtMs > nowMs -> WorkoutSelection.FUTURE
        else -> WorkoutSelection.EXPORT
    }

    /**
     * One GPS fix reduced to what an `ExerciseRoute.Location` needs. Primitives on purpose: this
     * file stays Room-free and unit-testable — the caller maps
     * [com.pulseloop.data.entity.ActivityGpsPointEntity] → this, already filtered to `accepted`.
     */
    data class GpsRoutePoint(
        val timeMs: Long,
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracyMeters: Double? = null,
        val altitudeMeters: Double? = null,
    )

    /**
     * Route sanitisation (plan Phase 4; Gadgetbridge `buildSanitisedRoute`): drop points outside
     * the session window [sessionStartMs, sessionEndMs] (inclusive), points with non-finite or
     * out-of-range coordinates, and duplicate timestamps — Health Connect rejects a route whose
     * points repeat a timestamp, and the first point of a duplicate keeps its place. The result
     * is sorted by time. A route needs ≥ 2 points to exist; the caller treats a shorter result
     * as "no route" (the session still writes).
     */
    fun sanitizeRoutePoints(
        sessionStartMs: Long,
        sessionEndMs: Long,
        raw: List<GpsRoutePoint>,
    ): List<GpsRoutePoint> {
        if (sessionEndMs < sessionStartMs) return emptyList()
        val seen = HashSet<Long>()
        val kept = mutableListOf<GpsRoutePoint>()
        for (p in raw.sortedBy { it.timeMs }) {
            if (p.timeMs < sessionStartMs || p.timeMs > sessionEndMs) continue
            if (!p.latitude.isFinite() || !p.longitude.isFinite()) continue
            if (p.latitude !in -90.0..90.0 || p.longitude !in -180.0..180.0) continue
            if (!seen.add(p.timeMs)) continue // duplicate timestamp — HC rejects it
            kept += p
        }
        return kept
    }

    /** Matches "...single record size limit: 1000000, was: 1700644" from the HC platform
     *  (Gadgetbridge's production format for the 1 MB single-record limit). */
    private val RECORD_SIZE_REGEX =
        Regex("single record size limit:\\s*(\\d+),\\s*was:\\s*(\\d+)")

    /**
     * The 1 MB per-record platform limit, parsed out of the insert exception message (plan §3
     * robustness constants; no API exposes it). Returns the `(limit, was)` pair, or null when the
     * message doesn't carry it — the caller then falls back to its normal retry.
     */
    fun parseRecordSizeLimit(message: String?): Pair<Long, Long>? {
        val m = RECORD_SIZE_REGEX.find(message ?: "") ?: return null
        val limit = m.groupValues[1].toLongOrNull() ?: return null
        val was = m.groupValues[2].toLongOrNull() ?: return null
        if (limit <= 0L || was <= limit) return null
        return limit to was
    }

    /** Gadgetbridge: aim for 90 % of the limit to leave room for per-point overhead the size
     *  model doesn't capture. */
    const val ROUTE_SHRINK_MARGIN = 0.9

    /**
     * Uniformly decimates [points] down to [target] points (clamped to ≥ 2), preserving first and
     * last — Gadgetbridge's `decimateRoute`. The uniform stride keeps the shape of the route;
     * `step` is always ≥ 1 here (target < size), so the integer indices are strictly increasing
     * and no timestamp is ever duplicated (which HC would reject).
     */
    fun <T> decimateToSize(points: List<T>, target: Int): List<T> {
        val t = target.coerceAtLeast(2)
        if (points.size <= t) return points
        val lastIndex = points.size - 1
        val step = lastIndex.toDouble() / (t - 1).toDouble()
        val kept = ArrayList<T>(t)
        var idx = 0.0
        repeat(t - 1) {
            kept += points[idx.toInt().coerceAtMost(lastIndex - 1)]
            idx += step
        }
        kept += points.last()
        return kept
    }

    // ── heart-rate series segmentation ──

    /** One sorted sample: sample instant + whole bpm (HeartRateRecord.Sample is a Long). */
    data class HrSample(val timeMs: Long, val bpm: Long)

    /**
     * Splits a sorted, plausibility-filtered hour's samples into series records, copying
     * Gadgetbridge's rules: a new segment on a local-date change, on a gap longer than
     * [MAX_HR_GAP_MS], and when a segment holds [MAX_SAMPLES_PER_HR_RECORD] samples.
     * Pure — unit-testable without a database or a client.
     */
    fun splitHrSegments(samples: List<HrSample>, zone: ZoneId = ZoneId.systemDefault()): List<List<HrSample>> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.timeMs }
        val segments = mutableListOf<List<HrSample>>()
        var current = mutableListOf(sorted.first())
        var prevDay = Instant.ofEpochMilli(sorted.first().timeMs).atZone(zone).toLocalDate()
        for (i in 1 until sorted.size) {
            val sample = sorted[i]
            val prev = sorted[i - 1]
            val day = Instant.ofEpochMilli(sample.timeMs).atZone(zone).toLocalDate()
            val newDay = day != prevDay
            val gapTooLong = sample.timeMs - prev.timeMs > MAX_HR_GAP_MS
            val full = current.size >= MAX_SAMPLES_PER_HR_RECORD
            if (newDay || gapTooLong || full) {
                segments.add(current)
                current = mutableListOf(sample)
            } else {
                current.add(sample)
            }
            prevDay = day
        }
        segments.add(current)
        return segments
    }

    /**
     * [HeartRateRecord] requires a positive duration: a single-sample segment gets its end bumped
     * by 1 s (Gadgetbridge does the same).
     */
    fun seriesEndMs(startMs: Long, endMs: Long): Long = if (endMs <= startMs) endMs + 1L else endMs

    // ── sleep (Phase 2; plan §3 identity table + Phase 2 spec) ──

    /** One session's shape for a day's clientRecordId selection — the two fields of a
     *  [com.pulseloop.data.entity.SleepSessionEntity] that determine which session is the day's
     *  main sleep. Primitives on purpose: this file stays Room-free and unit-testable. */
    data class SleepDaySession(val startAtMs: Long, val totalMinutes: Long)

    /**
     * `pl-sleep-<dayEpochMs>` — the clientRecordId for a waking day's [SleepSessionRecord], keyed
     * on the session's `date` (the waking day's local midnight, epoch ms). NEVER keyed on
     * [com.pulseloop.data.entity.SleepStageBlockEntity.id] — a fresh random UUID on every re-sync,
     * because upsertSleepSessionAtomic replaces the blocks — or on the session UUID (plan §3,
     * identity trap #1). A re-synced night must upsert the SAME Health Connect record in place,
     * and `date` is stable across re-syncs. Millisecond-epoch form of the iOS
     * `pl-sleep-<dayEpoch>` identifier; the version (session.updatedAt) is set by the exporter.
     *
     * A waking day can hold more than one session (a main night plus a daytime nap, split by
     * SleepSegmentation), and Health Connect resolves one clientRecordId to one record per app —
     * two sessions sharing the plain id would silently replace each other. Disambiguation:
     * the day's main session ([mainSleepIndex]) keeps the plain id; the others take a
     * deterministic suffix, `pl-sleep-<dayEpochMs>-<i>` ([sleepSessionSuffix]).
     *
     * Known edge (accepted, mirrors the HR hour-split edge): if a nap later joins or leaves the
     * day the suffixed ids shift, leaving one superseded record in Health Connect. Write-only
     * means we cannot delete it; its content is re-exported under the new ids on the same pass,
     * so nothing is lost or double-counted.
     */
    fun sleepSessionRecordId(dayEpochMs: Long, suffix: Int? = null): String =
        if (suffix == null) "pl-sleep-$dayEpochMs" else "pl-sleep-$dayEpochMs-$suffix"

    /**
     * The index of a waking day's main session: the longest ([SleepDaySession.totalMinutes]),
     * ties to the earliest start — the same main sleep [com.pulseloop.data.dao.SleepSessionDao.byDay]
     * surfaces to the single-session callers. null for an empty day.
     */
    fun mainSleepIndex(sessions: List<SleepDaySession>): Int? {
        if (sessions.isEmpty()) return null
        var best = 0
        for (i in 1 until sessions.size) {
            val cur = sessions[i]
            val prev = sessions[best]
            if (cur.totalMinutes > prev.totalMinutes ||
                (cur.totalMinutes == prev.totalMinutes && cur.startAtMs < prev.startAtMs)
            ) best = i
        }
        return best
    }

    /**
     * The deterministic suffix for session [index] of a multi-session waking day: its 1-based
     * position in startAt order among the day's non-main sessions; null when [index] IS the day's
     * main session (it keeps the plain id). Purely a function of the day's session set, so a
     * re-run computes the same ids.
     */
    fun sleepSessionSuffix(sessions: List<SleepDaySession>, index: Int): Int? {
        val main = mainSleepIndex(sessions) ?: return null
        if (index == main) return null
        val nonMain = sessions.indices.filter { it != main }.sortedBy { sessions[it].startAtMs }
        return nonMain.indexOf(index) + 1
    }

    /** A proposed stage span (epoch millis) for [normalizeSleepStages]. [stageType] is already
     *  mapped through [sleepStageType] — the client's constants, never a raw int. */
    data class SleepStageSpan(val startMs: Long, val endMs: Long, val stageType: Int)

    /**
     * [com.pulseloop.data.entity.SleepStageBlockEntity.stageRaw] (a [SleepStage] name) → the
     * client's [SleepSessionRecord] stage-type constant (plan Phase 2). Anything unrecognized
     * degrades to [SleepSessionRecord.STAGE_TYPE_UNKNOWN] rather than being dropped — the block
     * is real sleep time, just unclassified.
     */
    fun sleepStageType(stageRaw: String): Int = when (stageRaw) {
        SleepStage.DEEP.name -> SleepSessionRecord.STAGE_TYPE_DEEP
        SleepStage.LIGHT.name -> SleepSessionRecord.STAGE_TYPE_LIGHT
        SleepStage.REM.name -> SleepSessionRecord.STAGE_TYPE_REM
        SleepStage.AWAKE.name -> SleepSessionRecord.STAGE_TYPE_AWAKE
        SleepStage.UNKNOWN.name -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
        else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
    }

    /**
     * Normalizes raw stage blocks into a stage list the [SleepSessionRecord] constructor accepts
     * (plan Phase 2): sort by start, clamp each span to [sessionStartMs, sessionEndMs], drop
     * overlaps (keep the earlier, truncate the later to start where the earlier ends), and drop
     * zero/negative-length stages. The result is sorted, non-overlapping (touching is allowed —
     * the record's validation rejects a stage ending after the NEXT stage's start), and inside
     * the session bounds.
     */
    fun normalizeSleepStages(
        sessionStartMs: Long,
        sessionEndMs: Long,
        raw: List<SleepStageSpan>,
    ): List<SleepStageSpan> {
        if (sessionEndMs <= sessionStartMs) return emptyList()
        val result = mutableListOf<SleepStageSpan>()
        for (span in raw.sortedBy { it.startMs }) {
            var start = span.startMs.coerceAtLeast(sessionStartMs)
            val end = span.endMs.coerceAtMost(sessionEndMs)
            if (end <= start) continue // zero/negative length after the session-bound clamp
            if (result.isNotEmpty() && start < result.last().endMs) {
                start = result.last().endMs // keep the earlier stage, truncate this one
                if (end <= start) continue
            }
            result += SleepStageSpan(start, end, span.stageType)
        }
        return result
    }

    // ── Phase 5: beyond-iOS vitals + nutrition (plan §3 Phase-5 table, §4 Phase 5) ──

    /**
     * `pl-m-bp-<epochMs>` — one `BloodPressureRecord` paired from a systolic + a diastolic
     * [com.pulseloop.data.entity.MeasurementEntity] row that share the same sample instant. The
     * pair is immutable (a taken reading is never re-written), so `clientRecordVersion` = 1.
     * Millisecond precision, same as [vitalsRecordId]. Both source rows collapse onto ONE record,
     * so the id is keyed on the shared timestamp — never on either row's random live UUID, so a
     * reading that arrives once live and once via history still lands on the same record.
     */
    fun bloodPressureRecordId(timestampMs: Long): String = "pl-m-bp-$timestampMs"

    /**
     * `pl-resting-hr` — the single `RestingHeartRateRecord` for the user's learned resting-HR
     * baseline ([com.pulseloop.data.entity.UserProfileEntity.hrRestingBaseline]). A constant id:
     * there is exactly one baseline, so a re-learned value re-upserts the SAME record in place
     * rather than accumulating one per re-learn. `clientRecordVersion` = the baseline's
     * `hrRestingBaselineUpdatedAt` — a re-learn always advances it, so the newer value wins the
     * upsert (and it is never the metric value itself).
     */
    const val RESTING_HR_RECORD_ID = "pl-resting-hr"

    /**
     * `pl-meal-<mealId>` — one `NutritionRecord` per [com.pulseloop.data.entity.MealEntryEntity].
     * The meal's Room primary key is a stable UUID: a logged meal is insert-once (never churned on
     * re-sync the way sleep blocks are), so it is a safe upsert key. `clientRecordVersion` = the
     * meal's `createdAt`.
     */
    fun nutritionRecordId(mealId: String): String = "pl-meal-$mealId"

    // ── Phase 5 plausibility guards ──
    //
    // Each guard is the intersection of (a) the platform bound Health Connect enforces on insert
    // (a violation throws and would sink the whole 200-record chunk) and (b) the app's own
    // data-quality range (RingEventBridge's plausibility windows), so a 0 sentinel or a
    // misdecoded live value never reaches a write-only store. Following Gadgetbridge and the Phase
    // 1 style, an out-of-range value is DROPPED, never clamped; NaN / ±∞ fall out of every
    // comparison and are dropped for free.

    /**
     * Systolic mmHg — the app's own floor (RingEventBridge.systolicRange 60) intersected with the
     * platform ceiling (⚠️ 200, from the BloodPressureRecord clinit; 201+ would throw and sink the
     * whole chunk). The app's decode range is 60..250 but the platform only accepts 20..200.
     */
    fun isPlausibleSystolic(mmHg: Double): Boolean = mmHg.isFinite() && mmHg in 60.0..MAX_SYSTOLIC_MMHG

    /**
     * Diastolic mmHg — the app's own range (RingEventBridge.diastolicRange 30..150). Unlike the
     * systolic ceiling, the app's diastolic ceiling (150) is TIGHTER than the platform's (180), so
     * the app range is the binding constraint; a 151..180 reading is within the platform but
     * implausible by the app's own bar and is dropped for consistency.
     */
    fun isPlausibleDiastolic(mmHg: Double): Boolean = mmHg.isFinite() && mmHg in 30.0..150.0

    /** Health Connect's systolic ceiling, mmHg (BloodPressureRecord clinit; binds over the app's 250). */
    const val MAX_SYSTOLIC_MMHG = 200.0

    /** One side of a blood-pressure reading, reduced to what [pairBloodPressure] needs. */
    data class BpSide(val timestampMs: Long, val value: Double, val createdAt: Long)

    /** A matched, plausible blood-pressure pair ready to become one [BloodPressureRecord]. */
    data class BpPair(val timestampMs: Long, val systolic: Double, val diastolic: Double, val highWater: Long)

    /**
     * The outcome of [pairBloodPressure]: the exportable [pairs] plus the drop counts, so the
     * exporter can report dropped readings as skipped (consistent with the other groups' skipped
     * counters). [unpaired] = a timestamp present on only one side (a decode/storage anomaly);
     * [outOfRange] = a matched pair with an implausible systolic or diastolic.
     */
    data class BpPairingResult(val pairs: List<BpPair>, val unpaired: Int, val outOfRange: Int) {
        val dropped: Int get() = unpaired + outOfRange
    }

    /**
     * Pairs systolic + diastolic rows by EXACT timestamp equality into [BpPair]s (plan Phase 5).
     * The app always writes the pair with one `event.timestamp`, so exact equality is correct - a
     * tolerance would risk cross-pairing two nearby readings. A timestamp present on only one side
     * (an unpaired reading - a decode/storage anomaly) and a pair with an out-of-range value are
     * dropped, never clamped. [highWater] is the max of the pair's two `createdAt`s, so the
     * exporter can advance the group watermark only past a value whose rows BOTH reached Health
     * Connect. Pure (no DB) so the pairing rules - the part that decides what a BP reading exports
     * as - are unit-testable.
     */
    fun pairBloodPressure(sys: List<BpSide>, dia: List<BpSide>): BpPairingResult {
        val sysByTs = HashMap<Long, BpSide>()
        for (s in sys) sysByTs[s.timestampMs] = s
        val diaByTs = HashMap<Long, BpSide>()
        for (d in dia) diaByTs[d.timestampMs] = d
        val out = mutableListOf<BpPair>()
        var unpaired = 0
        var outOfRange = 0
        for (ts in (sysByTs.keys + diaByTs.keys).toSet().sorted()) {
            val s = sysByTs[ts]
            val d = diaByTs[ts]
            if (s == null || d == null) { unpaired++; continue } // a reading on only one side
            if (!isPlausibleSystolic(s.value) || !isPlausibleDiastolic(d.value)) { outOfRange++; continue }
            out += BpPair(ts, s.value, d.value, maxOf(s.createdAt, d.createdAt))
        }
        return BpPairingResult(out, unpaired, outOfRange)
    }

    /**
     * Blood glucose, mg/dL. The floor is the app's own range (20, RingEventBridge.bloodSugarRange)
     * so a 0 sentinel / artifact is dropped; the ceiling is the platform's hard cap — ⚠️ 900.0
     * mg/dL, NOT the plan's 900.91. Verified against the 1.1.0 bytecode: MAX_BLOOD_GLUCOSE_LEVEL
     * is 50 mmol/L and the client's mg/dL→mmol/L factor is exactly 1/18, so 50 mmol/L = 900.0
     * mg/dL (900.0 maps to 50.0, accepted; 900.01 maps to 50.0006 and THROWS). Gadgetbridge's
     * 900.91 guard is looser than the platform and would let (900.0, 900.91] through to a throwing
     * constructor - the whole chunk would sink. The app's own bridge range is 20..600
     * (RingEventBridge), so (600, 900] is unreachable from a ring; the guard still uses the
     * platform ceiling (900.0) rather than 600 so a legitimately high value (if ever stored) would
     * still export instead of being silently dropped.
     */
    fun isPlausibleBloodGlucose(mgDl: Double): Boolean = mgDl.isFinite() && mgDl in 20.0..MAX_BLOOD_GLUCOSE_MGDL

    /** Health Connect's hard glucose ceiling, mg/dL (= 50 mmol/L at the client's 1/18 factor). */
    const val MAX_BLOOD_GLUCOSE_MGDL = 900.0

    /** Respiratory rate, breaths/min — the app's own range (RingEventBridge 5..60), inside the platform 0..1000. */
    fun isPlausibleRespRate(breathsPerMin: Double): Boolean = breathsPerMin.isFinite() && breathsPerMin in 5.0..60.0

    /** VO2max, mL/kg/min — the app's own range (RingEventBridge 1..100); the platform bound is 0..100. */
    fun isPlausibleVo2Max(mlPerKgMin: Double): Boolean = mlPerKgMin.isFinite() && mlPerKgMin in 1.0..100.0

    /** Resting HR, bpm — the platform bound (⚠️ 1..300; the platform rejects 0, unlike the client). */
    fun isPlausibleRestingHr(bpm: Double): Boolean = bpm.isFinite() && bpm in 1.0..300.0

    // ── Phase 5 nutrition (NutritionRecord) ──
    //
    // NutritionRecord's clinit bounds (verified from the 1.1.0 AAR): energy 0..1e8 cal; the macro
    // masses (protein/carbs/fat/fiber/sugar) 0..100,000 g; sodium is a micronutrient capped at
    // 100 g = 100,000 mg (and is built with Mass.milligrams, not grams). A meal comes only from the
    // manual "Log Meal" dialog and is normally tiny, but a typo must not sink the whole 200-record
    // chunk, so an out-of-range meal is DROPPED (never clamped), like every other guard here.

    /**
     * Meal energy, kcal. The platform cap is `Energy.calories(100_000_000)` (verified from the
     * 1.1.0 clinit) and [androidx.health.connect.client.units.Energy.calories] is SMALL calories,
     * so that is 100,000,000 cal = **100,000 kcal** - NOT 1e8 kcal. A 0-calorie meal carries no
     * energy field; a value above 100,000 kcal would throw from the ctor (sink the chunk), so the
     * guard drops it. (An earlier 1e8 *kcal* cap was 1000x too loose - caught in review.)
     */
    fun isPlausibleNutritionEnergyKcal(kcal: Double): Boolean = kcal.isFinite() && kcal > 0.0 && kcal <= MAX_NUTRITION_ENERGY_KCAL

    /** A macro mass in grams (or sodium in mg — same numeric cap), 0..100,000. */
    fun isPlausibleNutritionMass(value: Double): Boolean = value.isFinite() && value > 0.0 && value <= MAX_NUTRITION_MASS_G

    const val MAX_NUTRITION_ENERGY_KCAL = 100_000.0
    const val MAX_NUTRITION_MASS_G = 100_000.0

    /**
     * [com.pulseloop.data.entity.MealEntryEntity.mealTypeRaw] (one of breakfast/lunch/dinner/snack,
     * the app's own four) → the client's [MealType] int. Anything unrecognized degrades to
     * MEAL_TYPE_UNKNOWN (the meal is real, just unclassified).
     */
    fun nutritionMealType(mealTypeRaw: String): Int = when (mealTypeRaw) {
        "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
        "lunch" -> MealType.MEAL_TYPE_LUNCH
        "dinner" -> MealType.MEAL_TYPE_DINNER
        "snack" -> MealType.MEAL_TYPE_SNACK
        else -> MealType.MEAL_TYPE_UNKNOWN
    }
}

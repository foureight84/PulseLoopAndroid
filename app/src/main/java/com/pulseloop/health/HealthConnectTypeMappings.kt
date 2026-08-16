package com.pulseloop.health

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
     * A daily total minus what Phase 4's workout records will carry for the same day. Pure so the
     * subtraction — the part that can silently under-report — is testable without Room.
     *
     * Returns the leftover even when it goes negative; the caller's plausibility guard drops it.
     * **Known gap for Phase 4:** dropping it leaves any previously exported, un-netted record for
     * that day live in Health Connect, since a write-only export cannot delete or zero it. That
     * cannot happen while [HealthConnectExporter.WORKOUTS_EXPORTED] is false (netting is off, so
     * nothing is subtracted); Phase 4 must decide between writing a floor-value record to
     * overwrite the stale one and accepting the window.
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
     */
    data class NettableSession(
        val dayStartMs: Long,
        val calories: Double?,
        val distanceMeters: Double?,
        val useGps: Boolean,
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
     * **Two ways that "netted set == credited set" equality is known to be imperfect**, both
     * dormant while [HealthConnectExporter.WORKOUTS_EXPORTED] is false and both for Phase 4 to
     * resolve:
     *  1. `EventPersistenceSubscriber.applyActivityBucketAtomic` *overwrites* a past day's
     *     `distanceMeters` with the ring's bucket sum (the ratchet only applies to today), so a
     *     history re-sync discards the credited GPS metres while netting would still subtract them.
     *  2. `ActivityRollup.credit` early-returns for sessions under a minute, which are netted here
     *     but were never credited.
     *
     * Callers pass only sessions that are `finished` with a non-null `endedAt`, mirroring iOS.
     */
    fun workoutNetting(sessions: List<NettableSession>): WorkoutNetting {
        val kcal = HashMap<Long, Double>()
        val meters = HashMap<Long, Double>()
        for (s in sessions) {
            val k = s.calories
            if (k != null && k > 0.0) kcal[s.dayStartMs] = (kcal[s.dayStartMs] ?: 0.0) + k
            val m = s.distanceMeters
            if (s.useGps && m != null && m > 0.0) meters[s.dayStartMs] = (meters[s.dayStartMs] ?: 0.0) + m
        }
        return WorkoutNetting(kcal, meters)
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
}

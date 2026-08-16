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

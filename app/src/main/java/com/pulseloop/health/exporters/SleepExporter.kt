package com.pulseloop.health.exporters

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.health.HealthConnectTypeMappings
import com.pulseloop.health.HealthConnectTypeMappings.EXCLUDED_SOURCES
import com.pulseloop.health.HealthConnectTypeMappings.SleepDaySession
import com.pulseloop.health.HealthConnectTypeMappings.SleepStageSpan
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the Phase 2 sleep records (docs/health-connect-integration.md Phase 2): one
 * `SleepSessionRecord` per [SleepSessionEntity], its stages from the session's
 * [com.pulseloop.data.entity.SleepStageBlockEntity] rows — sorted, clamped to the session
 * bounds, overlaps dropped (keep the earlier, truncate the later), zero-length stages dropped
 * ([HealthConnectTypeMappings.normalizeSleepStages]) — with the client's stage-type constants
 * (never raw ints).
 *
 * Identity (plan §3, identity trap #1): `clientRecordId = pl-sleep-<dayEpochMs>` from the
 * session's `date` — NEVER the block id (a fresh random UUID on every re-sync) nor the session
 * UUID — so a re-synced night upserts the SAME record in place. `clientRecordVersion` = the
 * session's `updatedAt`, so the later, fuller re-sync always wins the upsert.
 *
 * The selection is watermark-driven on `updatedAt` (sleep is a mutable group: re-synced nights
 * must re-export), and demo rows are excluded (mirrors iOS). Pure DB → records: no client, no
 * inserts — [com.pulseloop.health.HealthConnectExporter] owns the write path.
 */
class SleepExporter(private val db: PulseLoopDatabase) {

    /**
     * Pending sleep records with the parallel [highWaters] list: entry i is the source session's
     * `updatedAt` that record i represents, so the exporter can advance the sleep watermark only
     * to a value whose sessions all reached Health Connect. [skippedSessions] counts sessions
     * selected by the watermark but not written (invalid span, or no stages after normalization).
     */
    data class PendingSleep(
        val records: List<Record>,
        val highWaters: List<Long>,
        val skippedSessions: Int,
    )

    /**
     * Builds the pending records. Sessions with `updatedAt <= [watermark]` were already
     * exported; `null` means export everything (first-enable backfill).
     */
    suspend fun build(watermark: Long?, device: Device): PendingSleep {
        val wm = watermark ?: 0L
        val zone = ZoneId.systemDefault()
        val dao = db.sleepSessionDao()
        val sessions = dao.updatedSince(wm).filter { it.sourceRaw !in EXCLUDED_SOURCES }
        if (sessions.isEmpty()) return PendingSleep(emptyList(), emptyList(), 0)

        // All blocks for the pending sessions in one query, grouped by session.
        val blocksBySession = db.sleepStageBlockDao()
            .forSessions(sessions.map { it.id })
            .groupBy { it.sessionId }

        // Main-session selection needs each day's FULL non-demo session set — the pending list
        // alone cannot tell a night from the nap that shares its waking day (plan §3: the plain
        // id belongs to the day's main sleep).
        val dayCache = HashMap<Long, List<SleepSessionEntity>>()

        val records = mutableListOf<Record>()
        val highWaters = mutableListOf<Long>()
        var skipped = 0

        for (session in sessions) {
            if (session.endAt <= session.startAt) {
                skipped++
                continue // the record constructor would reject it
            }
            val day = dayCache.getOrPut(session.date) { dao.ringAllByDay(session.date) }
            val index = day.indexOfFirst { it.id == session.id }
            if (index < 0) {
                skipped++
                continue // cannot happen: the query filters to the same sourceRaw set
            }
            val recordId = HealthConnectTypeMappings.sleepSessionRecordId(
                session.date,
                HealthConnectTypeMappings.sleepSessionSuffix(
                    day.map { SleepDaySession(it.startAt, it.totalMinutes.toLong()) },
                    index,
                ),
            )

            val spans = blocksBySession[session.id].orEmpty().map {
                SleepStageSpan(
                    it.startAt,
                    it.startAt + it.durationMinutes * 60_000L,
                    HealthConnectTypeMappings.sleepStageType(it.stageRaw),
                )
            }
            val stages = HealthConnectTypeMappings.normalizeSleepStages(session.startAt, session.endAt, spans)
            if (stages.isEmpty()) {
                skipped++
                continue // Gadgetbridge parity: a session with no valid stages is not written;
                // a later re-sync that adds stages bumps updatedAt and re-selects it
            }

            val start = Instant.ofEpochMilli(session.startAt)
            val end = Instant.ofEpochMilli(session.endAt)
            records += SleepSessionRecord(
                startTime = start,
                startZoneOffset = HealthConnectTypeMappings.zoneOffsetAt(start, zone),
                endTime = end,
                endZoneOffset = HealthConnectTypeMappings.zoneOffsetAt(end, zone),
                metadata = Metadata.autoRecorded(device, recordId, session.updatedAt),
                title = null,
                notes = null,
                stages = stages.map {
                    SleepSessionRecord.Stage(
                        Instant.ofEpochMilli(it.startMs),
                        Instant.ofEpochMilli(it.endMs),
                        it.stageType,
                    )
                },
            )
            highWaters += session.updatedAt
        }
        return PendingSleep(records, highWaters, skipped)
    }
}

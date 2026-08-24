package com.pulseloop.health.exporters

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.health.HealthConnectTypeMappings
import com.pulseloop.health.HealthConnectTypeMappings.EXCLUDED_SOURCES
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
 * Identity: the bounded v2 `clientRecordId` is derived only from the stable
 * [SleepSessionEntity.id], never from waking-day main/nap rank. `clientRecordVersion` = the
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
        /**
         * Max `updatedAt` among sessions that were selected but produced no record. Without it a
         * dropped session sitting above every exportable one pins the SLEEP watermark, and every
         * later pass re-selects and re-upserts the whole tail behind it. Safe to advance past:
         * every repair (a re-sync that adds stages, or fixes the span) bumps `updatedAt`, which
         * re-selects the row regardless of where the watermark stands. Applied only on a fully
         * completed pass — see [com.pulseloop.health.watermarkAdvance].
         */
        val droppedHighWater: Long? = null,
    )

    /**
     * Builds the pending records. Sessions with `updatedAt <= [watermark]` were already
     * exported; `null` means export everything (first-enable backfill).
     */
    suspend fun build(watermark: Long?, device: Device): PendingSleep {
        val wm = watermark ?: 0L
        val zone = ZoneId.systemDefault()
        val dao = db.sleepSessionDao()
        val selected = dao.updatedSince(wm)
        val sessions = selected.filter { it.sourceRaw !in EXCLUDED_SOURCES }
        // Demo/mock rows are permanently unexportable (a row's source never changes), so they
        // count as drops — otherwise a seeded demo night newer than every real one pins the
        // watermark forever.
        var droppedHigh: Long? = selected.filter { it.sourceRaw in EXCLUDED_SOURCES }
            .maxOfOrNull { it.updatedAt }
        if (sessions.isEmpty()) return PendingSleep(emptyList(), emptyList(), 0, droppedHigh)
        fun drop(updatedAt: Long) { droppedHigh = maxOf(droppedHigh ?: Long.MIN_VALUE, updatedAt) }

        // All blocks for the pending sessions in one query, grouped by session.
        val blocksBySession = db.sleepStageBlockDao()
            .forSessions(sessions.map { it.id })
            .groupBy { it.sessionId }

        val records = mutableListOf<Record>()
        val highWaters = mutableListOf<Long>()
        var skipped = 0

        for (session in sessions) {
            if (session.endAt <= session.startAt) {
                skipped++
                drop(session.updatedAt)
                continue // the record constructor would reject it
            }
            val recordId = HealthConnectTypeMappings.sleepSessionRecordIdV2(session.id)

            val record = buildSleepSessionRecord(
                session,
                blocksBySession[session.id].orEmpty(),
                recordId,
                device,
                zone,
            )
            if (record == null) {
                skipped++
                drop(session.updatedAt)
                continue // Gadgetbridge parity: a session with no valid stages is not written;
                // a later re-sync that adds stages bumps updatedAt and re-selects it
            }

            records += record
            highWaters += session.updatedAt
        }
        return PendingSleep(records, highWaters, skipped, droppedHigh)
    }
}

internal fun buildSleepSessionRecord(
    session: SleepSessionEntity,
    blocks: List<SleepStageBlockEntity>,
    recordId: String,
    device: Device,
    zone: ZoneId,
): SleepSessionRecord? {
    if (session.endAt <= session.startAt) return null
    val spans = blocks.map {
        SleepStageSpan(
            it.startAt,
            it.startAt + it.durationMinutes * 60_000L,
            HealthConnectTypeMappings.sleepStageType(it.stageRaw),
        )
    }
    val stages = HealthConnectTypeMappings.normalizeSleepStages(session.startAt, session.endAt, spans)
    if (stages.isEmpty()) return null

    val start = Instant.ofEpochMilli(session.startAt)
    val end = Instant.ofEpochMilli(session.endAt)
    return SleepSessionRecord(
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
}

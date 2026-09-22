package com.pulseloop.data

import androidx.room.withTransaction
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage

/**
 * Deleting one ring sleep record (issue #78) — the sleep sibling of [ActivityBucketDeletion].
 *
 * A night can arrive as several ring records minutes apart, merged into one stored session because
 * the merge is the night (#63). Mostly that merge is right — but the ring opens a record on a still
 * wrist, so an evening on the sofa can land as a full phantom "session" before the wearer is even
 * in bed, and the night's total runs an hour high. The wearer knows which run is wrong; the sensor
 * cannot. This is the escape hatch.
 *
 * Three rules, mirroring the activity deletion's, and all of them live here rather than in any
 * caller:
 *
 *  * **Tombstone the blocks, not the session row.** Sleep's write path never upserts a session by
 *    id — `EventPersistenceSubscriber` re-derives the whole waking day from the raw stage blocks
 *    the ring re-sends, so deleting the row alone would let the next sync of that night rebuild it
 *    exactly. A block is minute-grid RLE over the record's stages, so the same record re-sent
 *    reproduces its blocks with the same `startAt` values; `MeasurementDeletionDao.sleepBlockId`
 *    keys on the waking day plus block start — the same stable-identity role the activity
 *    tombstone's `startEpoch` plays. The re-derive path must consult those tombstones before
 *    writing (it does — see `upsertSleepSessionAtomic`).
 *  * **Re-derive, never hand-patch.** The surviving session's bounds, asleep minutes and score are
 *    recomputed from the blocks that remain — the same numbers `reconcileWakingDay` produces — so
 *    what the UI shows after a delete is what the next sync would agree with. If nothing remains,
 *    the session row goes entirely and the day reads as unslept rather than reverting to the ring's
 *    figure on the next pass.
 *  * **The merge reseals itself.** The survivors are still distinct records with their own gap, so
 *    `sleepRecordRuns` re-splits the remaining blocks and the RECORDS card shows the shorter list
 *    with no stored "these were one record" state to maintain.
 */
object SleepRecordDeletion {

    /**
     * Delete the ring record whose blocks carry [recordStartAt] from session [sessionId], remember
     * the blocks, and restate the session from what remains.
     *
     * Returns true when a record was actually removed.
     */
    suspend fun delete(
        db: PulseLoopDatabase,
        sessionId: String,
        recordStartAt: Long,
    ): Boolean = db.withTransaction {
        val session = db.sleepSessionDao().byId(sessionId) ?: return@withTransaction false
        val blocks = db.sleepStageBlockDao().forSession(sessionId)
        // The run the user pointed at is the stretch of blocks stamped with that record's declared
        // start (issue #68). `0` means a pre-#68 row with no stamp: fall back to "everything from
        // the run boundary until the next stamped start", the same fallback `sleepRecordRuns` uses
        // to split those nights for display.
        val targets = if (recordStartAt == 0L) {
            emptyList()
        } else {
            blocks.filter { it.recordStartAt == recordStartAt }.ifEmpty {
                blocks.filter { it.recordStartAt == 0L && it.startAt >= recordStartAt }
                    .takeWhile { it.startAt < nextStampedStart(blocks, recordStartAt) }
            }
        }
        if (targets.isEmpty()) return@withTransaction false

        // The waking day the tombstone keys under is this session's own date: every record the day
        // reconciles is assigned that same `date`, so a re-send of the record lands on the same key.
        val day = session.date
        db.measurementDeletionDao().recordSleepBlocks(day, targets.map { it.startAt })
        targets.forEach { db.sleepStageBlockDao().deleteByStart(sessionId, it.startAt) }

        restateSession(db, session, blocks - targets.toSet())
        true
    }

    /** The next stamped record boundary after [after], or end-of-night — bounds the legacy fallback. */
    private fun nextStampedStart(blocks: List<SleepStageBlockEntity>, after: Long): Long =
        blocks.map { it.recordStartAt }.filter { it > after }.minOrNull() ?: Long.MAX_VALUE

    /**
     * Rewrite [session] from the blocks it still has — bounds, asleep minutes and score, the same
     * formulas `reconcileWakingDay` applies (blocks re-keyed to the new bounds the same way). Empty
     * means gone: the row is deleted and the day reads as unslept.
     */
    private suspend fun restateSession(
        db: PulseLoopDatabase,
        session: SleepSessionEntity,
        remaining: List<SleepStageBlockEntity>,
    ) {
        if (remaining.isEmpty()) {
            db.sleepSessionDao().deleteById(session.id)
            return
        }
        val ordered = remaining.sortedBy { it.startAt }
        val startAt = ordered.first().startAt
        val endAt = ordered.maxOf { it.startAt + it.durationMinutes * 60_000L }
        val totalMin = com.pulseloop.service.asleepMinutes(ordered)
        val deepMin = ordered.filter { it.stageRaw == SleepStage.DEEP.name }
            .sumOf { it.durationMinutes }
        val now = System.currentTimeMillis()
        db.sleepSessionDao().upsert(
            session.copy(
                startAt = startAt,
                endAt = endAt,
                totalMinutes = totalMin,
                score = com.pulseloop.service.sleepStageScore(deepMin, totalMin),                updatedAt = now,
            ),
        )
        // Re-key the surviving blocks to the restated bounds: `startMinute` is relative to the
        // session start, so it moves with the new first block.
        db.sleepStageBlockDao().deleteBySession(session.id)
        ordered.forEach {
            db.sleepStageBlockDao().insert(
                it.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = session.id,
                    startMinute = ((it.startAt - startAt) / 60_000L).toInt().coerceAtLeast(0),
                ),
            )
        }
    }
}

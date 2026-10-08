package com.pulseloop.data

import androidx.room.withTransaction
import com.pulseloop.data.entity.SleepStageBlockEntity

/**
 * Removes the sleep a CRP ring's evening syncs stored one day early, before `CRPDecoder.decodeSleep`
 * learned that the ring's sleep day turns over at 20:00 (see AGENTS.md, "A CRP sleep day turns over
 * at 20:00, not midnight").
 *
 * Every sync after 8 PM filed that morning's night 24 h early. On a ring's first night that made a
 * whole night on a day the ring wasn't worn; on every later one the copy landed on the night before,
 * and the next morning's re-sync replaced only the real record's own span — so each night kept the
 * next night's earlier start and later end, and read long. The decoder fix stops new copies; this
 * removes the ones already stored, once.
 *
 * **What identifies a copy.** A block remembers the start of the ring record it came from
 * (`recordStartAt`, issue #68), and a copy keeps its source's, moved back by a day. So record R is a
 * copy of record G when G started one calendar day after R — 24 h, or 23/25 h across a DST change,
 * since the decoder anchors on local midnight — and **every** block of R lies inside a block of G
 * with the same stage once moved forward by that same amount. The re-sync that trimmed a copy only
 * ever cut it shorter, so a surviving fragment still fits inside its source. All or nothing per
 * record: two genuine nights that merely start at the same minute have different stage sequences and
 * are left alone. The earlier record of a pair is the copy, never the later one.
 *
 * **No tombstones.** With the decoder fixed the copies can't come back, and a tombstone keyed on
 * these block starts would sit waiting to suppress whatever genuine sleep later lands on them.
 */
object SleepDayEarlyCopies {

    private const val HOUR_MS = 3_600_000L

    /** One calendar day between a copy and its source: 24 h, or 23/25 h across a DST change. */
    private val DAY_SHIFTS = listOf(24 * HOUR_MS, 23 * HOUR_MS, 25 * HOUR_MS)

    /**
     * Find and remove every stored copy, restating each session it touched from what remains.
     * Returns the number of blocks removed. Idempotent: a second run finds nothing.
     */
    suspend fun repair(db: PulseLoopDatabase): Int = db.withTransaction {
        val sessions = db.sleepSessionDao().all().filter { it.sourceRaw !in DEMO_SOURCES }
        if (sessions.isEmpty()) return@withTransaction 0
        val blocks = db.sleepStageBlockDao().forSessions(sessions.map { it.id })
        val strays = strayBlocks(blocks)
        if (strays.isEmpty()) return@withTransaction 0
        val bySession = blocks.groupBy { it.sessionId }
        for (session in sessions) {
            val own = bySession[session.id] ?: continue
            if (own.none { it in strays }) continue
            SleepRecordDeletion.restateSession(db, session, own.filterNot { it in strays })
        }
        strays.size
    }

    /** The blocks that belong to a record copied a day early. Pure; see the class KDoc for the rule. */
    internal fun strayBlocks(blocks: List<SleepStageBlockEntity>): Set<SleepStageBlockEntity> {
        // `recordStartAt == 0` is a pre-#68 row with no record identity, so it can't be matched.
        val records = blocks.filter { it.recordStartAt > 0L }.groupBy { it.recordStartAt }
        val strays = mutableSetOf<SleepStageBlockEntity>()
        for ((recordStart, recordBlocks) in records) {
            val isCopy = DAY_SHIFTS.any { shift ->
                val source = records[recordStart + shift] ?: return@any false
                recordBlocks.all { block -> source.any { it.contains(block, shift) } }
            }
            if (isCopy) strays += recordBlocks
        }
        return strays
    }

    /** Whether [block], moved forward by [shift], lies inside this block with the same stage. */
    private fun SleepStageBlockEntity.contains(block: SleepStageBlockEntity, shift: Long): Boolean {
        val start = block.startAt + shift
        return stageRaw == block.stageRaw &&
            startAt <= start &&
            startAt + durationMinutes * 60_000L >= start + block.durationMinutes * 60_000L
    }

    private val DEMO_SOURCES = setOf("demo", "mock")
}

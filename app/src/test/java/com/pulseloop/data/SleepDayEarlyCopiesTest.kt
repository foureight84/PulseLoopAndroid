package com.pulseloop.data

import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import com.pulseloop.service.asleepMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule [SleepDayEarlyCopies] uses to find sleep a CRP evening sync stored one day early, on
 * blocks shaped like the reporter's database: a first night copied onto a day before the ring was
 * worn, and later nights padded with the following night's head and tail.
 */
class SleepDayEarlyCopiesTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** Sun 2026-09-27 00:00 UTC. Times below are minutes from here; negative is Saturday evening. */
    private val sunday = 1_790_467_200_000L

    private var nextId = 0

    private fun block(
        startMin: Int,
        minutes: Int,
        stage: SleepStage,
        recordStartMin: Int,
        sessionId: String = "s",
        dayOffset: Long = 0L,
    ) = SleepStageBlockEntity(
        id = "b${nextId++}",
        sessionId = sessionId,
        startAt = sunday + dayOffset + startMin * minute,
        startMinute = 0,
        durationMinutes = minutes,
        stageRaw = stage.name,
        recordStartAt = sunday + dayOffset + recordStartMin * minute,
    )

    /** A staged night recorded from [recordStartMin]: light, deep, light, rem. */
    private fun night(recordStartMin: Int, sessionId: String, dayOffset: Long = 0L) = listOf(
        block(recordStartMin, 30, SleepStage.LIGHT, recordStartMin, sessionId, dayOffset),
        block(recordStartMin + 30, 60, SleepStage.DEEP, recordStartMin, sessionId, dayOffset),
        block(recordStartMin + 90, 120, SleepStage.LIGHT, recordStartMin, sessionId, dayOffset),
        block(recordStartMin + 210, 40, SleepStage.REM, recordStartMin, sessionId, dayOffset),
    )

    private fun copyOf(record: List<SleepStageBlockEntity>, sessionId: String, shift: Long = day) =
        record.map { it.copy(id = "b${nextId++}", sessionId = sessionId, startAt = it.startAt - shift, recordStartAt = it.recordStartAt - shift) }

    @Test
    fun `a whole night copied onto an empty day is found, and its session goes`() {
        val sundayNight = night(98, "sun")              // 01:38
        val saturdayCopy = copyOf(sundayNight, "sat")   // Sat 01:38, nothing real beneath it

        val strays = SleepDayEarlyCopies.strayBlocks(sundayNight + saturdayCopy)

        assertEquals(saturdayCopy.toSet(), strays)
        val sat = SleepSessionEntity(id = "sat", date = sunday - day, startAt = saturdayCopy.first().startAt, endAt = saturdayCopy.last().startAt + 40 * minute, totalMinutes = 250)
        assertTrue(SleepRecordDeletion.planRestate(sat, saturdayCopy - strays).isEmpty())
    }

    @Test
    fun `a copied one-block doze is found`() {
        val sundayDoze = listOf(block(1358, 27, SleepStage.LIGHT, 1358, "sunDoze"))    // Sun 22:38
        val copy = copyOf(sundayDoze, "satDoze")
        assertEquals(copy.toSet(), SleepDayEarlyCopies.strayBlocks(sundayDoze + copy))
    }

    /**
     * Monday's night (01:03) copied onto Sunday's (01:38), then trimmed by the next morning's re-sync
     * of Sunday's own record: only the copy's head before 01:38 and its tail after Sunday's end
     * survive. They go; Sunday's own record comes back to exactly its own bounds and minutes.
     */
    @Test
    fun `padding left on the night before is removed and the night restored`() {
        val sundayNight = night(98, "sun")                                // 01:38 → 05:48
        val mondayNight = night(63, "mon", dayOffset = day) +             // Mon 01:03 → 05:13 ...
            block(313, 120, SleepStage.LIGHT, 63, "mon", dayOffset = day)  // ... → 07:13
        val sundayEnd = sundayNight.last().let { it.startAt + it.durationMinutes * minute }
        val padding = copyOf(mondayNight, "sun").mapNotNull { b ->
            val end = b.startAt + b.durationMinutes * minute
            when {
                end <= sundayNight.first().startAt -> b                                   // head
                b.startAt >= sundayEnd -> b                                               // tail
                b.startAt < sundayEnd && end > sundayEnd ->                               // trimmed tail
                    b.copy(startAt = sundayEnd, durationMinutes = ((end - sundayEnd) / minute).toInt())
                b.startAt < sundayNight.first().startAt ->                                // trimmed head
                    b.copy(durationMinutes = ((sundayNight.first().startAt - b.startAt) / minute).toInt())
                else -> null
            }
        }
        assertTrue("the scenario needs padding on both sides", padding.size >= 2)

        val strays = SleepDayEarlyCopies.strayBlocks(sundayNight + padding + mondayNight)
        assertEquals(padding.toSet(), strays)

        val session = SleepSessionEntity(id = "sun", date = sunday, startAt = padding.minOf { it.startAt }, endAt = padding.maxOf { it.startAt + it.durationMinutes * minute }, totalMinutes = 0)
        val restated = SleepRecordDeletion.planRestate(session, sundayNight + padding - strays).single()
        assertEquals("sun", restated.id)
        assertEquals(sundayNight.first().startAt, restated.startAt)
        assertEquals(sundayEnd, restated.endAt)
        assertEquals(asleepMinutes(sundayNight), asleepMinutes(restated.blocks))
    }

    @Test
    fun `two real nights starting at the same minute are left alone`() {
        val sundayNight = night(98, "sun")
        val mondayNight = listOf(
            block(98, 45, SleepStage.LIGHT, 98, "mon", dayOffset = day),
            block(143, 90, SleepStage.REM, 98, "mon", dayOffset = day),
            block(233, 60, SleepStage.DEEP, 98, "mon", dayOffset = day),
        )
        assertTrue(SleepDayEarlyCopies.strayBlocks(sundayNight + mondayNight).isEmpty())
    }

    @Test
    fun `a record only partly inside the next day's is left alone`() {
        val mondayNight = night(98, "mon", dayOffset = day)
        // Sunday's record matches Monday's for 250 minutes, then runs on for 30 more of its own.
        val sundayNight = copyOf(mondayNight, "sun") + block(98 + 250, 30, SleepStage.LIGHT, 98, "sun")
        assertTrue(SleepDayEarlyCopies.strayBlocks(mondayNight + sundayNight).isEmpty())
    }

    @Test
    fun `copies shifted across a DST change are found`() {
        for (shift in listOf(23 * hour, 25 * hour)) {
            val later = night(98, "later", dayOffset = shift)
            val copy = copyOf(later, "earlier", shift)
            assertEquals("shift ${shift / hour} h", copy.toSet(), SleepDayEarlyCopies.strayBlocks(later + copy))
        }
    }

    @Test
    fun `a shift that isn't one calendar day is not a copy`() {
        for (shift in listOf(22 * hour, 26 * hour, 48 * hour)) {
            val later = night(98, "later", dayOffset = shift)
            val copy = copyOf(later, "earlier", shift)
            assertTrue("shift ${shift / hour} h", SleepDayEarlyCopies.strayBlocks(later + copy).isEmpty())
        }
    }

    @Test
    fun `blocks without a record start are never matched`() {
        val sundayNight = night(98, "sun").map { it.copy(recordStartAt = 0L) }
        val copy = copyOf(night(98, "x"), "sat").map { it.copy(recordStartAt = 0L) }
        assertTrue(SleepDayEarlyCopies.strayBlocks(sundayNight + copy).isEmpty())
    }

    @Test
    fun `only the earlier record of a pair is the copy`() {
        val sundayNight = night(98, "sun")
        val copy = copyOf(sundayNight, "sat")
        val strays = SleepDayEarlyCopies.strayBlocks(sundayNight + copy)
        assertTrue(sundayNight.none { it in strays })
    }
}

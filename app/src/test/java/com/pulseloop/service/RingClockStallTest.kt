package com.pulseloop.service

import com.pulseloop.ring.YCBTEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Issue #142: spotting a stopped ring clock from where the ring stamps its own copy of a reading. */
class RingClockStallTest {
    private fun at(h: Int, m: Int, s: Int): Long =
        LocalDateTime.of(2026, 11, 10, h, m, s).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Reporter, after the freeze: connection 00:38:21–00:39:48, ring's copy stamped 00:38:25. */
    private val frozenOurs = at(0, 39, 44)
    private val frozenRing = at(0, 38, 25)

    /** Reporter, before the freeze: connection 08:43:21–08:44:48, copy stamped 08:44:44. */
    private val healthyOurs = at(8, 44, 45)
    private val healthyRing = at(8, 44, 44)

    @Test
    fun `one frozen stamp is evidence but not yet a warning`() {
        val s = RingClockStall.onCopyPaired(RingClockStall.State(), frozenOurs, frozenRing, frozenOurs)
        assertEquals(1, s.evidence.size)
        assertNull(s.suspectedAt)
    }

    @Test
    fun `two frozen stamps raise the warning`() {
        var s = RingClockStall.onCopyPaired(RingClockStall.State(), frozenOurs, frozenRing, frozenOurs)
        val next = frozenOurs + 30 * 60_000L
        s = RingClockStall.onCopyPaired(s, next, frozenRing + 30 * 60_000L, next)
        assertEquals(next, s.suspectedAt)
    }

    @Test
    fun `a healthy stamp clears the warning`() {
        val raised = RingClockStall.State(evidence = listOf(1L, 2L), suspectedAt = 2L)
        val s = RingClockStall.onCopyPaired(raised, healthyOurs, healthyRing, healthyOurs)
        assertNull(s.suspectedAt)
        assertEquals(emptyList<Long>(), s.evidence)
    }

    @Test
    fun `the same reading is judged once`() {
        var s = RingClockStall.onCopyPaired(RingClockStall.State(), frozenOurs, frozenRing, frozenOurs)
        s = RingClockStall.onCopyPaired(s, frozenOurs, frozenRing, frozenOurs + 1)
        assertEquals(1, s.evidence.size)
        assertNull(s.suspectedAt)
    }

    @Test
    fun `a minute-aligned stamp a minute early is not evidence`() {
        // A ring stamping whole minutes can read up to 60 s before our settled time.
        val ours = at(9, 0, 58)
        val s = RingClockStall.onCopyPaired(RingClockStall.State(), ours, at(9, 0, 0), ours)
        assertEquals(0, s.evidence.size)
    }

    @Test
    fun `readings with no copy after a sync are evidence, once each`() {
        val now = at(12, 0, 0)
        val unmatched = listOf(at(10, 0, 0), at(11, 0, 0), at(11, 55, 0))
        val s = RingClockStall.onSyncFinished(RingClockStall.State(), unmatched, now)
        // The 11:55 reading is too recent to judge.
        assertEquals(2, s.evidence.size)
        assertNotNull(s.suspectedAt)
        val again = RingClockStall.onSyncFinished(s, unmatched, now + 60_000L)
        assertEquals(2, again.evidence.size)
    }

    @Test
    fun `old evidence ages out of the window`() {
        val first = RingClockStall.onCopyPaired(RingClockStall.State(), frozenOurs, frozenRing, frozenOurs)
        val dayLater = frozenOurs + RingClockStall.EVIDENCE_WINDOW_MS + 60_000L
        val s = RingClockStall.onCopyPaired(first, dayLater, dayLater - 80_000L, dayLater)
        assertEquals(1, s.evidence.size)
        assertNull(s.suspectedAt)
    }

    @Test
    fun `shut down is 03 16 01 like the vendor's appShutDown(1)`() {
        assertArrayEquals(byteArrayOf(0x03, 0x16, 0x01), YCBTEncoder().shutDown())
    }
}

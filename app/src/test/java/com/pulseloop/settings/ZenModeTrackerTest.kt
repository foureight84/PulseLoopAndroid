package com.pulseloop.settings

import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [ZenModeTracker] quiet windows lifecycle, filter classification and serialization (issue #83).
 */
class ZenModeTrackerTest {

    @Test
    fun `isZenModeActive classifies interruption filters correctly`() {
        // Active quiet filters
        assertTrue(ZenModeTracker.isZenModeActive(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(ZenModeTracker.isZenModeActive(NotificationManager.INTERRUPTION_FILTER_NONE))
        assertTrue(ZenModeTracker.isZenModeActive(NotificationManager.INTERRUPTION_FILTER_ALARMS))

        // Inactive / normal filter
        assertFalse(ZenModeTracker.isZenModeActive(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(ZenModeTracker.isZenModeActive(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }

    @Test
    fun `updateWindows opens a new window when active`() {
        val initial = emptyList<ZenWindow>()
        val updated = ZenModeTracker.updateWindows(initial, active = true, timestamp = 1000L)

        assertEquals(1, updated.size)
        assertEquals(1000L, updated[0].start)
        assertNull(updated[0].end)
    }

    @Test
    fun `updateWindows does not create duplicate window if already active`() {
        val open = listOf(ZenWindow(start = 1000L, end = null))
        val updated = ZenModeTracker.updateWindows(open, active = true, timestamp = 2000L)

        assertEquals(1, updated.size)
        assertEquals(1000L, updated[0].start)
        assertNull(updated[0].end)
    }

    @Test
    fun `updateWindows closes open window when inactive`() {
        val open = listOf(ZenWindow(start = 1000L, end = null))
        val updated = ZenModeTracker.updateWindows(open, active = false, timestamp = 3500L)

        assertEquals(1, updated.size)
        assertEquals(1000L, updated[0].start)
        assertEquals(3500L, updated[0].end)
    }

    @Test
    fun `updateWindows does nothing when inactive and no window is open`() {
        val closed = listOf(ZenWindow(start = 1000L, end = 2000L))
        val updated = ZenModeTracker.updateWindows(closed, active = false, timestamp = 3000L)

        assertEquals(1, updated.size)
        assertEquals(1000L, updated[0].start)
        assertEquals(2000L, updated[0].end)
    }

    @Test
    fun `serialization round trips windows accurately`() {
        val windows = listOf(
            ZenWindow(start = 1000L, end = 2000L),
            ZenWindow(start = 3000L, end = null),
        )
        val serialized = ZenModeTracker.serializeWindows(windows)
        assertEquals("1000,2000;3000,", serialized)

        val parsed = ZenModeTracker.parseWindows(serialized)
        assertEquals(windows, parsed)
    }

    @Test
    fun `parseWindows handles empty or malformed strings gracefully`() {
        assertTrue(ZenModeTracker.parseWindows(null).isEmpty())
        assertTrue(ZenModeTracker.parseWindows("").isEmpty())
        assertTrue(ZenModeTracker.parseWindows("  ").isEmpty())
        assertTrue(ZenModeTracker.parseWindows("invalid,garbage").isEmpty())
        // Corrupted end should be discarded rather than treated as an open window
        assertEquals(
            listOf(ZenWindow(start = 2000L, end = 3000L)),
            ZenModeTracker.parseWindows("1000,corrupted;2000,3000"),
        )
    }

    @Test
    fun `updateWindows caps open window when closed after exceeding max duration`() {
        val start = 1_000_000L
        val open = listOf(ZenWindow(start = start, end = null))
        val farFuture = start + ZenModeTracker.MAX_WINDOW_DURATION_MS + 3_600_000L
        val updated = ZenModeTracker.updateWindows(open, active = false, timestamp = farFuture)

        assertEquals(1, updated.size)
        assertEquals(start, updated[0].start)
        assertEquals(start + ZenModeTracker.MAX_WINDOW_DURATION_MS, updated[0].end)
    }

    @Test
    fun `updateWindows starts new window if active and open window exceeded max duration`() {
        val start = 1_000_000L
        val open = listOf(ZenWindow(start = start, end = null))
        val farFuture = start + ZenModeTracker.MAX_WINDOW_DURATION_MS + 3_600_000L
        val updated = ZenModeTracker.updateWindows(open, active = true, timestamp = farFuture)

        assertEquals(2, updated.size)
        assertEquals(start, updated[0].start)
        assertEquals(start + ZenModeTracker.MAX_WINDOW_DURATION_MS, updated[0].end)
        assertEquals(farFuture, updated[1].start)
        assertNull(updated[1].end)
    }

    @Test
    fun `pruneWindows drops windows older than retention limit`() {
        val now = 100_000_000L
        val maxRetention = 10_000L // 10s retention
        val windows = listOf(
            ZenWindow(start = now - 20_000L, end = now - 15_000L), // old -> dropped
            ZenWindow(start = now - 5_000L, end = now - 1_000L),   // recent -> kept
            ZenWindow(start = now - 2_000L, end = null),            // active -> kept
            ZenWindow(start = now - 50_000L, end = null),           // stale open window -> dropped
        )
        val pruned = ZenModeTracker.pruneWindows(windows, now = now, maxRetentionMs = maxRetention)

        assertEquals(2, pruned.size)
        assertEquals(now - 5_000L, pruned[0].start)
        assertEquals(now - 2_000L, pruned[1].start)
    }
}

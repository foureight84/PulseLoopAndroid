package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ManualActivityService.validate` — the pure guard `create` runs before touching the DB.
 * Ported from `ManualActivityServiceTests.swift` (iOS #57d). The DB-side recompute/rollup-credit
 * behavior has no Android unit-test equivalent (same gap noted in `ActivityAggregatesTest`), so
 * that half is covered by runtime verification instead.
 */
class ManualActivityServiceTest {
    private val now = 1_800_000_000_000L
    private val start = now - 90 * 60_000L // 90 min ago

    @Test
    fun `valid past session accepted`() {
        assertNull(ManualActivityService.validate("cycle", 60.0, start, now))
    }

    @Test
    fun `invalid activity type rejected`() {
        assertEquals(
            ManualActivityError.InvalidActivityType,
            ManualActivityService.validate("cycling", 60.0, start, now),
        )
    }

    @Test
    fun `zero duration rejected`() {
        assertEquals(
            ManualActivityError.InvalidDuration,
            ManualActivityService.validate("run", 0.0, now - 3600_000L, now),
        )
    }

    @Test
    fun `session ending in the future rejected`() {
        assertEquals(
            ManualActivityError.EndsInFuture,
            ManualActivityService.validate("run", 30.0, now - 10 * 60_000L, now),
        )
    }
}

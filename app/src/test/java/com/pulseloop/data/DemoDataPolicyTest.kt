package com.pulseloop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo/real predicate the readers share now that a connect deletes nothing (PR #52).
 *
 * The regression this pins: `MetricsService` compared `source == "mock"` — the iOS spelling —
 * while `DemoDataSeeder` writes `"demo"`, so `TodaySummary.isDemo` was false for every seeded row
 * and demo steps/HR were reported as live readings.
 */
class DemoDataPolicyTest {
    @Test
    fun `the seeder's own spelling counts as demo`() {
        assertTrue(DemoDataPolicy.isDemo("demo"))
    }

    @Test
    fun `the iOS spelling counts as demo`() {
        assertTrue(DemoDataPolicy.isDemo("mock"))
    }

    @Test
    fun `synced and unknown sources are not demo`() {
        assertFalse(DemoDataPolicy.isDemo("ring"))
        assertFalse(DemoDataPolicy.isDemo("manual"))
        assertFalse(DemoDataPolicy.isDemo(null))
        assertFalse(DemoDataPolicy.isDemo(""))
    }
}

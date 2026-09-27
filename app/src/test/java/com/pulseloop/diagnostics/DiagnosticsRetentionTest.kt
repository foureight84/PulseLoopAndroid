package com.pulseloop.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsRetentionTest {
    private class Recorder {
        val trims = mutableListOf<Int>()
        fun retention(limit: Int, every: Int) = DiagnosticsRetention(limit, every) { trims += it }
    }

    @Test fun `first insert trims so a table that grew before retention is cut at startup`() = runTest {
        val r = Recorder()
        r.retention(limit = 1_000, every = 50).inserted()
        assertEquals(listOf(1_000), r.trims)
    }

    @Test fun `trims once per interval, not on every insert`() = runTest {
        val r = Recorder()
        val retention = r.retention(limit = 2_000, every = 50)
        repeat(1 + 49) { retention.inserted() }
        assertEquals(1, r.trims.size)
        retention.inserted()
        assertEquals(listOf(2_000, 2_000), r.trims)
        repeat(49) { retention.inserted() }
        assertEquals(2, r.trims.size)
    }

    @Test fun `limits match the documented capture sizes`() {
        assertEquals(1_000, DiagnosticsRetention.RAW_PACKET_LIMIT)
        assertEquals(2_000, DiagnosticsRetention.WEARABLE_LOG_LIMIT)
    }
}

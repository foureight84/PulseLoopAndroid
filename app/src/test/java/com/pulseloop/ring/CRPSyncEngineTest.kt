package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [CRPSyncEngine] — the connect handshake and interactive commands enqueue the
 * right CRP frames. Mirrors the vendor's connect flow (set clock, then user info).
 */
class CRPSyncEngineTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { sent.add(command) }
        /** (group, cmd) of each written frame. */
        fun opcodes(): List<Pair<Int, Int>> = sent.map { it[4].toInt() to it[5].toInt() }
    }

    /** The all-day history pull appended to every runStartup (the poll pass). All group 2: the
     *  "timing" vital timelines HR/SpO2/HRV/stress (cmd 15/17/16/47), then temp (48) + sleep (14) —
     *  the opcodes the ring actually answers (issue #29). See CRPProtocol.queryTiming/queryHistory. */
    private val historyQueries = listOf(2 to 15, 2 to 17, 2 to 16, 2 to 47, 2 to 48, 2 to 14)

    /** The all-day monitor enables sent on connect (default ALL_ON): HR, HRV, stress, SpO2, temp —
     *  see CRPSyncEngine.applyTimingSettings. Without these a fresh R11 records no history. */
    private val timingEnables = listOf(1 to 6, 1 to 7, 1 to 39, 1 to 8, 1 to 13)

    @Test
    fun `runStartup sends set-time, firmware query, user info, default monitor enables, then the history pull`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.runStartup()
        // set-time, firmware query, default-on monitor enables, then the history pull.
        assertEquals(listOf(1 to 1, 7 to 1) + timingEnables + historyQueries, w.opcodes())

        w.sent.clear()
        engine.setUserProfile(
            UserProfileValues(metric = true, gender = 1u, age = 30u, heightCm = 180u, weightKg = 75u),
        )
        engine.runStartup()
        // set-time, firmware query, set-user-info, monitor enables, then the history pull.
        assertEquals(listOf(1 to 1, 7 to 1, 1 to 0) + timingEnables + historyQueries, w.opcodes())
    }

    @Test
    fun `runStartup honours a saved config, disabling the vitals the user turned off`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.setMeasurementSettings(
            MeasurementSettings(
                hrEnabled = true, hrIntervalMinutes = 10,
                spo2Enabled = false, stressEnabled = false, hrvEnabled = true, temperatureEnabled = false,
            ),
        )
        engine.runStartup()
        val opcodes = w.opcodes()
        // Every monitor is addressed on connect (enable or disable), never skipped.
        for (op in timingEnables) assertTrue("missing monitor op $op", opcodes.contains(op))
        // HR (interval 10) and HRV are enabled; SpO2/stress/temp are disabled (payload byte 0).
        fun payloadOf(group: Int, cmd: Int) = w.sent.first { it[4].toInt() == group && it[5].toInt() == cmd }[6].toInt()
        assertEquals(10, payloadOf(1, 6))   // HR enabled at the saved interval
        assertEquals(0, payloadOf(1, 8))    // SpO2 disabled
        assertEquals(0, payloadOf(1, 39))   // stress disabled
        assertEquals(0, payloadOf(1, 13))   // temp disabled
    }

    @Test
    fun `heart rate start and stop enqueue group1 cmd9`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.startHeartRate()
        engine.stopHeartRate()
        assertEquals(listOf(1 to 9, 1 to 9), w.opcodes())
        assertEquals(1, w.sent[0][6].toInt()) // enable
        assertEquals(0, w.sent[1][6].toInt()) // disable
    }

    @Test
    fun `findDevice and factoryReset enqueue their commands`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.findDevice()
        engine.factoryReset()
        assertEquals(listOf(9 to 2, 3 to 0), w.opcodes())
    }

    @Test
    fun `a timing-history frame drives the next-frame pull until the vital's terminal frame`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        // Clear the send buffer of the startup queries so we only see follow-ups.
        engine.runStartup(); w.sent.clear()

        // HR frame 0 → request HR frame 1 (its terminal frame); frame 1 → nothing further.
        engine.handle(RingDecodedEvent.TimingHistoryFrame(CRPCommands.CMD_QUERY_TIMING_HR, day = 0, frameIndex = 0))
        assertEquals(listOf(2 to 15), w.opcodes())
        assertEquals(1, w.sent[0][7].toInt()) // payload[1] = requested frame index 1
        w.sent.clear()
        engine.handle(RingDecodedEvent.TimingHistoryFrame(CRPCommands.CMD_QUERY_TIMING_HR, day = 0, frameIndex = 1))
        assertTrue("terminal HR frame must not request another", w.sent.isEmpty())
    }

    @Test
    fun `HRV walks four frames, others two`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.runStartup(); w.sent.clear()
        // HRV finalizes at frame 3: frames 0,1,2 each pull the next; frame 3 stops.
        for (idx in 0..2) {
            engine.handle(RingDecodedEvent.TimingHistoryFrame(CRPCommands.CMD_QUERY_TIMING_HRV, 0, idx))
            assertEquals(idx + 1, w.sent.last()[7].toInt())
        }
        w.sent.clear()
        engine.handle(RingDecodedEvent.TimingHistoryFrame(CRPCommands.CMD_QUERY_TIMING_HRV, 0, 3))
        assertTrue(w.sent.isEmpty())
    }

    @Test
    fun `a repeated frame does not spam duplicate follow-up requests`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.runStartup(); w.sent.clear()
        // The ring re-sends HR frame 0 several times in one poll pass — only one frame-1 pull fires.
        repeat(5) { engine.handle(RingDecodedEvent.TimingHistoryFrame(CRPCommands.CMD_QUERY_TIMING_HR, 0, 0)) }
        assertEquals(1, w.sent.size)
        // A fresh poll pass clears the guard, so the next sync re-pulls frame 1.
        engine.runStartup(); w.sent.clear()
        engine.handle(RingDecodedEvent.TimingHistoryFrame(CRPCommands.CMD_QUERY_TIMING_HR, 0, 0))
        assertEquals(1, w.sent.size)
    }

    @Test
    fun `applyUserProfile pushes user info immediately`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.applyUserProfile(
            UserProfileValues(metric = true, gender = 0u, age = 25u, heightCm = 165u, weightKg = 60u),
        )
        assertEquals(listOf(1 to 0), w.opcodes())
        // height passes through; stride is estimated as ~0.43*height.
        assertEquals(165.toByte(), w.sent[0][6])
        assertEquals((165 * 0.43).toInt().toByte(), w.sent[0][10])
    }
}

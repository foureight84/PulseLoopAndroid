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

    /** The all-day history pull appended to every runStartup (the poll pass). Group 7 for
     *  HR/SpO2/HRV/stress, group 2 for temp/sleep — see CRPProtocol.queryHistory*. */
    private val historyQueries = listOf(7 to 4, 7 to 7, 7 to 6, 7 to 5, 2 to 48, 2 to 14)

    @Test
    fun `runStartup sends set-time, firmware query, user info, then the history pull`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.runStartup()
        // set-time, firmware query, then the history pull (no profile / no settings yet).
        assertEquals(listOf(1 to 1, 7 to 1) + historyQueries, w.opcodes())

        w.sent.clear()
        engine.setUserProfile(
            UserProfileValues(metric = true, gender = 1u, age = 30u, heightCm = 180u, weightKg = 75u),
        )
        engine.runStartup()
        // set-time, firmware query, set-user-info, then the history pull.
        assertEquals(listOf(1 to 1, 7 to 1, 1 to 0) + historyQueries, w.opcodes())
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

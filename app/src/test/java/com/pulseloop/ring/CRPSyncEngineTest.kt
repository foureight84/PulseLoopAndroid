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

    @Test
    fun `runStartup sends set-time, firmware query, and user info once a profile is stored`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.runStartup()
        assertEquals(listOf(1 to 1, 7 to 1), w.opcodes()) // set-time then firmware query, no profile yet

        w.sent.clear()
        engine.setUserProfile(
            UserProfileValues(metric = true, gender = 1u, age = 30u, heightCm = 180u, weightKg = 75u),
        )
        engine.runStartup()
        assertEquals(listOf(1 to 1, 7 to 1, 1 to 0), w.opcodes()) // set-time, firmware query, set-user-info
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

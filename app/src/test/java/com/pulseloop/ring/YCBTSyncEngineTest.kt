package com.pulseloop.ring

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class YCBTSyncEngineTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) {
            sent += command.copyOf()
        }
    }

    private fun engine(writer: FakeWriter): YCBTSyncEngine {
        val transfer = YCBTHistoryTransfer(writer)
        return YCBTSyncEngine(writer, transfer)
    }

    @Test
    fun `full refresh begins with sport history`() {
        val writer = FakeWriter()
        engine(writer).syncHistory()
        assertArrayEquals(byteArrayOf(0x05, 0x02), writer.sent.single())
    }

    @Test
    fun `post workout refresh begins with recent heart history`() {
        val writer = FakeWriter()
        engine(writer).syncVitalsHistory()
        assertArrayEquals(byteArrayOf(0x05, 0x06), writer.sent.single())
    }

    @Test
    fun `sleep refresh requests only sleep history first`() {
        val writer = FakeWriter()
        engine(writer).syncSleepNow()
        assertArrayEquals(byteArrayOf(0x05, 0x04), writer.sent.single())
    }
}

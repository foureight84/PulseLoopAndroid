package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Test

class ExistingFamilyRefreshContractTest {
    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) {
            sent += command.copyOf()
        }
    }

    @Test
    fun `Jring refresh and query sleep retain startup behavior`() {
        val startup = capture { JringSyncEngine(it).runStartup() }
        val refresh = capture { JringSyncEngine(it).refresh() }
        val sleep = capture { JringSyncEngine(it).querySleep() }

        assertEquals(startup, refresh)
        assertEquals(startup, sleep)
    }

    @Test
    fun `Colmi refresh and query sleep retain startup behavior`() {
        val startup = captureColmi { it.runStartup() }
        val refresh = captureColmi { it.refresh() }
        val sleep = captureColmi { it.querySleep() }

        assertEquals(startup, refresh)
        assertEquals(startup, sleep)
    }

    private fun capture(action: (FakeWriter) -> Unit): List<List<Byte>> {
        val writer = FakeWriter()
        action(writer)
        return writer.sent.map(ByteArray::toList)
    }

    private fun captureColmi(action: (ColmiSyncEngine) -> Unit): List<List<Byte>> {
        val writer = FakeWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        action(engine)
        val commands = writer.sent.map(ByteArray::toList)
        engine.destroy()
        return commands
    }
}

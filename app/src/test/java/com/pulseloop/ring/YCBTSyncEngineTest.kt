package com.pulseloop.ring

import org.junit.Assert.*
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

    private fun drainNoData(writer: FakeWriter, transfer: YCBTHistoryTransfer): List<Int> {
        val requested = mutableListOf<Int>()
        while (true) {
            val query = writer.sent.firstOrNull {
                it.size == 2 && it[0] == YCBTGroup.HEALTH.toByte()
            } ?: break
            writer.sent.clear()
            val key = query[1].toInt() and 0xFF
            requested += key
            transfer.handle(cmd = key, payload = byteArrayOf(0x00))
        }
        return requested
    }

    @Test
    fun `full refresh requests live status before sport history`() {
        val writer = FakeWriter()
        engine(writer).refresh()
        assertEquals(2, writer.sent.size)
        assertArrayEquals(byteArrayOf(0x03, 0x09, 0x01, 0x00, 0x02), writer.sent[0])
        assertArrayEquals(byteArrayOf(0x05, 0x02), writer.sent[1])
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

    @Test
    fun `legacy query sleep action is history only for YCBT`() {
        val writer = FakeWriter()
        engine(writer).querySleep()
        assertArrayEquals(byteArrayOf(0x05, 0x04), writer.sent.single())
    }

    @Test
    fun `startup excludes the immediate name and time handshake`() {
        val writer = FakeWriter()
        engine(writer).runStartup()
        val startup = writer.sent.filter { it.size >= 2 && it[0] != YCBTGroup.HEALTH.toByte() }
        assertFalse(startup.any { it[0] == YCBTGroup.SETTING.toByte() && it[1] == YCBTSettingKey.SET_TIME.toByte() })
        assertFalse(startup.any { it[0] == YCBTGroup.GET.toByte() && it[1] == YCBTCommand.GET_DEVICE_NAME.toByte() })
        assertEquals(
            listOf(YCBTSettingKey.HEART_MONITOR, YCBTSettingKey.BLOOD_OXYGEN_MONITOR),
            startup.filter { it[0] == YCBTGroup.SETTING.toByte() }
                .map { it[1].toInt() and 0xFF }
                .filter { it in setOf(0x0c, 0x1c, 0x20, 0x26, 0x45) },
        )
    }

    @Test
    fun `startup requests current activity again when history finishes`() {
        val writer = FakeWriter()
        val engine = engine(writer)

        engine.runStartup()
        writer.sent.clear()
        engine.handle(RingDecodedEvent.HistorySyncFinished)

        assertArrayEquals(byteArrayOf(0x03, 0x09, 0x01, 0x00, 0x02), writer.sent.single())

        writer.sent.clear()
        engine.handle(RingDecodedEvent.HistorySyncFinished)
        assertTrue(writer.sent.isEmpty())
    }

    @Test
    fun `support bitmap appends only declared optional history`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer)
        val engine = YCBTSyncEngine(writer, transfer)

        engine.runStartup()
        engine.handle(
            RingDecodedEvent.SupportFunctions(
                setOf(WearableCapability.BLOOD_PRESSURE, WearableCapability.TEMPERATURE),
            ),
        )

        val optionalMonitors = writer.sent.filter {
            it.size >= 2 && it[0] == YCBTGroup.SETTING.toByte()
        }.map { it[1].toInt() and 0xFF }
        assertTrue(optionalMonitors.contains(YCBTSettingKey.TEMPERATURE_MONITOR))
        assertFalse(optionalMonitors.contains(YCBTSettingKey.BLOOD_PRESSURE_MONITOR))
        assertFalse(optionalMonitors.contains(YCBTSettingKey.HRV_MONITOR))

        assertEquals(
            listOf(0x02, 0x04, 0x06, 0x09, 0x08, 0x1e),
            drainNoData(writer, transfer),
        )
    }

    @Test
    fun `repeated support bitmap does not duplicate optional history`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer)
        val engine = YCBTSyncEngine(writer, transfer)
        val support = RingDecodedEvent.SupportFunctions(setOf(WearableCapability.BLOOD_PRESSURE))

        engine.runStartup()
        engine.handle(support)
        engine.handle(support)

        assertEquals(
            listOf(0x02, 0x04, 0x06, 0x09, 0x08),
            drainNoData(writer, transfer),
        )
    }

    @Test
    fun `late optional capability requeues the combined history source`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer)
        val engine = YCBTSyncEngine(writer, transfer)

        engine.runStartup()
        drainNoData(writer, transfer)
        writer.sent.clear()

        engine.handle(RingDecodedEvent.SupportFunctions(setOf(WearableCapability.HRV)))

        assertEquals(listOf(0x09), drainNoData(writer, transfer))
    }

    @Test
    fun `refresh uses the complete capability-filtered history catalog`() {
        val writer = FakeWriter()
        val transfer = YCBTHistoryTransfer(writer)
        val engine = YCBTSyncEngine(writer, transfer)

        engine.runStartup()
        engine.handle(
            RingDecodedEvent.SupportFunctions(
                setOf(
                    WearableCapability.BLOOD_PRESSURE,
                    WearableCapability.TEMPERATURE,
                    WearableCapability.BLOOD_SUGAR,
                    WearableCapability.STRESS,
                ),
            ),
        )
        drainNoData(writer, transfer)

        writer.sent.clear()
        engine.refresh()
        assertArrayEquals(byteArrayOf(0x03, 0x09, 0x01, 0x00, 0x02), writer.sent.first())
        assertEquals(
            listOf(0x02, 0x04, 0x06, 0x08, 0x09, 0x1e, 0x2f, 0x33),
            drainNoData(writer, transfer),
        )
    }
}

package com.pulseloop.ring

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-parity regression tests for the QRing-decompile audit fixes: HR/stress paging and
 * terminal detection, the interval-temperature (action 119) path, multi-day SpO2 blocks,
 * nap/lunch sleep (action 62), big-data reassembly hardening, and the corrected request frames.
 * Byte layouts verified against the decompiled official app (QCDataParser strips the opcode
 * before rsp classes run, so rsp `bArr[N]` = full-frame byte N+1).
 */
class ColmiQringParityTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
    }

    private fun emptyDayFrame(op: UByte): ByteArray = byteArrayOf(op.toByte(), 0xFF.toByte())

    private fun bigData(type: UByte, payload: ByteArray): ByteArray = byteArrayOf(
        0xBC.toByte(), type.toByte(),
        (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte(),
        0, 0,
    ) + payload

    // MARK: Request frames

    @Test
    fun `syncStress carries the day index like QRing PressureReq`() {
        assertArrayEquals(byteArrayOf(0x37, 0x00), ColmiEncoder.syncStress(0))
        assertArrayEquals(byteArrayOf(0x37, 0x04), ColmiEncoder.syncStress(4))
    }

    @Test
    fun `heart rate request sends the calendar date at midnight UTC`() {
        // QRing sends localMidnightEpoch + tzOffset — i.e. the calendar date at 00:00 UTC —
        // because the ring indexes daily logs on "local wall clock read as UTC". A true
        // local-midnight epoch lands inside the ring's previous day in GMT+ timezones.
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_ACTIVITY)) }

        val hrRequest = writer.commands.last()
        assertEquals(ColmiCommandID.SYNC_HEART_RATE.toByte(), hrRequest[0])
        val sent = (hrRequest[1].toLong() and 0xFF) or ((hrRequest[2].toLong() and 0xFF) shl 8) or
            ((hrRequest[3].toLong() and 0xFF) shl 16) or ((hrRequest[4].toLong() and 0xFF) shl 24)
        val expected = LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
        assertEquals(expected, sent)
        engine.destroy()
    }

    @Test
    fun `big data temperature request asks for six days with a valid CRC`() {
        // addHeader(37, [6]): CRC16/MODBUS([0x06]) = 0x423F → LE 3f 42.
        assertArrayEquals(
            byteArrayOf(0xBC.toByte(), 0x25, 0x01, 0x00, 0x3F, 0x42, 0x06),
            ColmiEncoder.bigDataTemperature())
    }

    @Test
    fun `interval temperature request frames dayIndex and packetIndex`() {
        val frame = ColmiEncoder.bigDataIntervalTemperature(daysAgo = 3, packetIndex = 2)
        assertEquals(0xBC.toByte(), frame[0])
        assertEquals(0x77.toByte(), frame[1])                    // action 119
        assertArrayEquals(byteArrayOf(0x02, 0x00), frame.copyOfRange(2, 4))  // len = 2 LE
        assertArrayEquals(byteArrayOf(0x03, 0x02), frame.copyOfRange(6, 8))  // payload
    }

    @Test
    fun `manual spo2 start uses QRing's BCD-25 action byte`() {
        assertArrayEquals(byteArrayOf(0x69, 0x03, 0x25), ColmiEncoder.manualSpO2(enable = true))
    }

    @Test
    fun `manual heart rate stop reports the final reading`() {
        assertArrayEquals(
            byteArrayOf(0x6A, 0x01, 72, 0x00),
            ColmiEncoder.manualHeartRate(enable = false, lastBpm = 72))
    }

    @Test
    fun `setDateTime carries the language byte`() {
        val frame = ColmiEncoder.setDateTime()
        assertEquals(8, frame.size)
        val expected: Byte = if (java.util.Locale.getDefault().language == "zh") 0x00 else 0x01
        assertEquals(expected, frame[7])
    }

    @Test
    fun `user preferences carry the 120-90 BP reference like QRing`() {
        val frame = ColmiEncoder.userPreferences()
        assertEquals(0x78.toByte(), frame[8])   // sbp 120
        assertEquals(0x5A.toByte(), frame[9])   // dbp 90
    }

    // MARK: HR history terminal detection (packet-0 size)

    @Test
    fun `hr day with data completes at packet size-1 and advances to the next day`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_ACTIVITY)) }
        // Stage = HEART_RATE, daysAgo = 0; one HR request enqueued.
        val countAtHrDay0 = writer.commands.size

        // Packet 0 announces size=3 (packets 0,1,2) at v[2] — QRing ReadHeartRateRsp.
        engine.handleHistoryFrame(ColmiPacket.frame(byteArrayOf(0x15, 0x00, 0x03, 0x05)))
        assertEquals("packet 0 must not advance the day", countAtHrDay0, writer.commands.size)
        // Packet 1 (time echo + samples) is not terminal either.
        engine.handleHistoryFrame(ColmiPacket.frame(byteArrayOf(0x15, 0x01, 0, 0, 0, 0, 70, 71)))
        assertEquals("packet 1 must not advance the day", countAtHrDay0, writer.commands.size)
        // Packet 2 == size-1: terminal → the engine must request the NEXT day. Pre-fix, an HR
        // day with data never completed and the watchdog skipped the whole stage.
        engine.handleHistoryFrame(ColmiPacket.frame(byteArrayOf(0x15, 0x02, 72, 73, 74, 0, 0)))
        assertEquals(countAtHrDay0 + 1, writer.commands.size)
        assertEquals(ColmiCommandID.SYNC_HEART_RATE.toByte(), writer.commands.last()[0])
        engine.destroy()
    }

    @Test
    fun `a garbage HR echo does not future-date samples`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_ACTIVITY)) }
        // Stage = HEART_RATE, day 0. Packet 0 announces the size.
        engine.handleHistoryFrame(ColmiPacket.frame(byteArrayOf(0x15, 0x00, 0x03, 0x05)))
        // Packet 1 with an all-0xFF time echo (≈ year 2106) and one HR sample. The echo is
        // out of the ±2-day window, so it must be rejected and the sample stays on today.
        val events = engine.handleHistoryFrame(ColmiPacket.frame(
            byteArrayOf(0x15, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 70)))
        val hr = events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
            .first { it.kind_field == MeasurementKind.HEART_RATE }
        val sysZone = ZoneId.systemDefault()
        val cutoff = LocalDate.now(sysZone).plusDays(2).atStartOfDay(sysZone).toInstant()
        assertTrue("garbage echo must not push HR samples into the future",
            hr._timestamp.isBefore(cutoff))
        engine.destroy()
    }

    // MARK: Stress 7-day paging

    @Test
    fun `stress pages over seven days before advancing to spo2`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_ACTIVITY)) }
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_HEART_RATE)) }
        // Stage = STRESS day 0. Two empty days → the third request must be [0x37, 0x02].
        engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_STRESS))
        engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_STRESS))
        assertArrayEquals(byteArrayOf(0x37, 0x02), writer.commands.last())
        // Five more empty days exhaust 0..6 → SpO2 big-data request.
        repeat(5) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_STRESS)) }
        val spo2 = writer.commands.last()
        assertEquals(0xBC.toByte(), spo2[0])
        assertEquals(ColmiCommandID.BIG_DATA_SPO2.toByte(), spo2[1])
        engine.destroy()
    }

    // MARK: Interval temperature (action 119)

    /** Drive an interval-temp-capable engine to the TEMPERATURE stage: the first action-119
     *  request (day 0, packet 0) is now the last enqueued command. */
    private fun driveToIntervalTemperature(writer: RecordingWriter): ColmiSyncEngine {
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        // 0x3C reply with supportIntervalTemp (full-frame byte 9, bit 0x80).
        engine.handleRawNotify(ColmiPacket.frame(
            byteArrayOf(0x3C, 0, 0, 0, 0, 0, 0, 0, 0, 0x80.toByte())))
        engine.runStartup()
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_ACTIVITY)) }
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_HEART_RATE)) }
        repeat(7) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_STRESS)) }
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SPO2)
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)
        repeat(7) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_HRV)) }
        return engine
    }

    @Test
    fun `interval-temp ring pages temperature per day with packet continuation`() {
        val writer = RecordingWriter()
        val engine = driveToIntervalTemperature(writer)

        // Stage = TEMPERATURE: the request must be action 119 for (day 0, packet 0).
        var request = writer.commands.last()
        assertEquals(0x77.toByte(), request[1])
        assertArrayEquals(byteArrayOf(0x00, 0x00), request.copyOfRange(6, 8))

        // Reply says day 0 has 2 packets, this was packet 0 → continuation (day 0, packet 1).
        engine.handleBigDataComplete(
            ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
            bigData(ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
                byteArrayOf(0x00, 30, 0x02, 0x00)))
        request = writer.commands.last()
        assertArrayEquals(byteArrayOf(0x00, 0x01), request.copyOfRange(6, 8))

        // Last packet of day 0 → next day (day 1, packet 0).
        engine.handleBigDataComplete(
            ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
            bigData(ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
                byteArrayOf(0x00, 30, 0x02, 0x01)))
        request = writer.commands.last()
        assertArrayEquals(byteArrayOf(0x01, 0x00), request.copyOfRange(6, 8))

        // Days 1..6 each single-packet → after day 6 the sync must finish (no new requests).
        repeat(6) { day ->
            engine.handleBigDataComplete(
                ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
                bigData(ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
                    byteArrayOf((day + 1).toByte(), 30, 0x01, 0x00)))
        }
        val countAtEnd = writer.commands.size
        assertArrayEquals("last request should have been day 6 packet 0",
            byteArrayOf(0x06, 0x00), writer.commands.last().copyOfRange(6, 8))
        // A stray re-emitted frame after DONE must be ignored (no request storm).
        engine.handleBigDataComplete(
            ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
            bigData(ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE, byteArrayOf(0x06, 30, 0x01, 0x00)))
        assertEquals(countAtEnd, writer.commands.size)
        engine.destroy()
    }

    @Test
    fun `interval-temp continuation does not loop when firmware stalls the packet index`() {
        val writer = RecordingWriter()
        val engine = driveToIntervalTemperature(writer)
        // day 0 packet 0 requested. Ring: count 3, index 0 → legitimately request (day0, packet1).
        engine.handleBigDataComplete(
            ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
            bigData(ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE, byteArrayOf(0x00, 30, 0x03, 0x00)))
        assertArrayEquals("first reply advances within day 0",
            byteArrayOf(0x00, 0x01), writer.commands.last().copyOfRange(6, 8))

        // Ring stalls: replies packetIndex 0 again instead of 1. Must NOT re-request the same
        // (day0, packet1) — that reply would re-arm the watchdog and loop forever. Drop to the
        // next day (day1, packet0) instead.
        engine.handleBigDataComplete(
            ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE,
            bigData(ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE, byteArrayOf(0x00, 30, 0x03, 0x00)))
        assertArrayEquals("a stalled index drops to the next day, not a re-request",
            byteArrayOf(0x01, 0x00), writer.commands.last().copyOfRange(6, 8))
        engine.destroy()
    }

    @Test
    fun `interval temperature samples decode as centi-celsius on the interval grid`() {
        // Header: day 1, interval 60 min, packetCount 1, packetIndex 0; samples 3626 → 36.26 °C
        // and 0 → skipped.
        val frame = bigData(ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE, byteArrayOf(
            0x01, 60, 0x01, 0x00,
            (3626 and 0xFF).toByte(), (3626 shr 8).toByte(),
            0x00, 0x00,
        ))
        val events = ColmiDecoder.decodeIntervalTemperature(frame, sampleOffset = 0, zone = zone)
        assertEquals(1, events.size)
        val sample = events.single() as RingDecodedEvent.TemperatureSample
        assertEquals(36.26, sample.celsius, 0.001)
        val expectedDay = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant()
        assertEquals(expectedDay, sample._timestamp)

        // A later packet of the same day continues the slot grid via sampleOffset.
        val offsetEvents = ColmiDecoder.decodeIntervalTemperature(frame, sampleOffset = 2, zone = zone)
        val offsetSample = offsetEvents.single() as RingDecodedEvent.TemperatureSample
        assertEquals(expectedDay.plusSeconds(2 * 60 * 60), offsetSample._timestamp)
    }

    // MARK: Multi-day SpO2 blocks

    @Test
    fun `spo2 decodes every 49-byte day block even when today comes first`() {
        val day0 = ByteArray(49).also { it[0] = 0; it[1] = 96; it[2] = 98 }       // hour 0
        val day1 = ByteArray(49).also { it[0] = 1; it[3] = 90; it[4] = 94 }       // hour 1
        val events = ColmiDecoder.decodeBigData(
            bigData(ColmiCommandID.BIG_DATA_SPO2, day0 + day1), zone = zone)
        val spo2 = events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
            .filter { it.kind_field == MeasurementKind.SPO2 }
        // The old loop stopped at the first daysAgo==0 block — day 1 was silently dropped.
        assertEquals(2, spo2.size)
        assertEquals(97.0, spo2[0].value, 0.01)
        assertEquals(92.0, spo2[1].value, 0.01)
    }

    // MARK: Nap/lunch sleep (action 62)

    @Test
    fun `lunch sleep frames decode like regular sleep`() {
        val start = 780; val end = 840   // 13:00 → 14:00 nap
        val payload = byteArrayOf(
            0x01, 0x00, 0x08,
            (start and 0xFF).toByte(), ((start shr 8) and 0xFF).toByte(),
            (end and 0xFF).toByte(), ((end shr 8) and 0xFF).toByte(),
            ColmiCommandID.SLEEP_LIGHT.toByte(), 40,
            ColmiCommandID.SLEEP_DEEP.toByte(), 20,
        )
        val events = ColmiDecoder.decodeBigData(
            bigData(ColmiCommandID.BIG_DATA_SLEEP_LUNCH, payload), zone = zone)
        val nap = events.single() as RingDecodedEvent.SleepTimeline
        assertEquals(60, nap.stages.size)
        assertEquals(40, nap.stages.count { it == SleepStage.LIGHT })
        assertEquals(20, nap.stages.count { it == SleepStage.DEEP })
    }

    @Test
    fun `lunch sleep completion does not advance the history pipeline`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()
        val count = writer.commands.size
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP_LUNCH)
        assertEquals(count, writer.commands.size)
        engine.destroy()
    }

    // MARK: Reassembly hardening

    @Test
    fun `continuation chunk starting with 0xBC does not restart the transfer`() {
        val driver = ColmiDriver(RecordingWriter())
        // Sleep frame whose payload contains 0xBC exactly at the chunk boundary:
        // start = 0x05BC (1468 → wraps to 28 min past midnight via the >1440 branch).
        val payload = byteArrayOf(
            0x01, 0x00, 0x08,
            0xBC.toByte(), 0x05,     // start u16 LE = 0x05BC
            0x3C, 0x00,              // end = 60
            ColmiCommandID.SLEEP_DEEP.toByte(), 30,
            ColmiCommandID.SLEEP_REM.toByte(), 30,
        )
        val full = bigData(ColmiCommandID.BIG_DATA_SLEEP, payload)
        val chunk1 = full.copyOfRange(0, 9)      // ends right before the 0xBC payload byte
        val chunk2 = full.copyOfRange(9, full.size)  // starts with 0xBC — must be APPENDED
        assertEquals(0xBC.toByte(), chunk2[0])

        assertTrue(driver.ingest(chunk1, ColmiUUIDs.NOTIFY_V2).isEmpty())
        val events = driver.ingest(chunk2, ColmiUUIDs.NOTIFY_V2)
        val sleep = events.filterIsInstance<RingDecodedEvent.SleepTimeline>()
        assertEquals("the split frame must reassemble into one sleep timeline", 1, sleep.size)
        assertEquals(60, sleep.single().stages.size)
    }
}

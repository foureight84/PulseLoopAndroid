package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Ported from [ColmiDecoderTests] in ColmiDecoderTests.swift.
 * Colmi R02 decoder/encoder parity tests. Pure — no hardware.
 */
class ColmiDecoderTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun `HRV history remains distinct from a live HRV sample`() {
        val frame = ColmiPacket.frame(byteArrayOf(
            ColmiCommandID.SYNC_HRV.toByte(), 0x01, 0x1e, 42,
        ))

        val events = ColmiDecoder.decodeHistory(
            frame,
            day = LocalDate.of(2026, 7, 17),
            zone = zone,
        )

        val sample = events.single() as RingDecodedEvent.HistoryMeasurement
        assertEquals(MeasurementKind.HRV, sample.kind_field)
        assertEquals(42.0, sample.value, 0.0)
    }

    // MARK: Framing / checksum

    @Test
    fun `frame appends checksum and is 16 bytes`() {
        val framed = ColmiPacket.frame(byteArrayOf(0x03))
        assertEquals(16, framed.size)
        assertEquals(0x03.toByte(), framed[15])
    }

    @Test
    fun `validating rejects bad checksum`() {
        val bytes = ColmiPacket.frame(byteArrayOf(0x03))
        bytes[15] = (bytes[15] + 1).toByte()
        assertNull(ColmiPacket.validating(bytes))
    }

    @Test
    fun `validating rejects wrong length`() {
        assertNull(ColmiPacket.validating(byteArrayOf(0x03, 0x00)))
    }

    @Test
    fun `bad checksum decodes as unknown`() {
        val bytes = ColmiPacket.frame(byteArrayOf(0x03, 0x55))
        bytes[15] = (bytes[15] + 1).toByte()
        val events = ColmiDecoder.decodeNormal(bytes)
        assertTrue(events.first() is RingDecodedEvent.Unknown)
    }

    // MARK: Normal-channel decode

    @Test
    fun `decode battery`() {
        val frame = ColmiPacket.frame(byteArrayOf(0x03, 84, 1))
        val events = ColmiDecoder.decodeNormal(frame)
        val battery = events.first() as RingDecodedEvent.Battery
        assertEquals(84, battery.percent)
    }

    @Test
    fun `decode realtime heart rate`() {
        val frame = ColmiPacket.frame(byteArrayOf(0x1E.toByte(), 72))
        val events = ColmiDecoder.decodeNormal(frame)
        val hr = events.first() as RingDecodedEvent.HeartRateSample
        assertEquals(72, hr.bpm)
    }

    @Test
    fun `realtime heart rate zero dropped`() {
        val frame = ColmiPacket.frame(byteArrayOf(0x1E.toByte(), 0))
        assertTrue(ColmiDecoder.decodeNormal(frame).isEmpty())
    }

    @Test
    fun `manual heart rate error gives completion`() {
        val frame = ColmiPacket.frame(byteArrayOf(0x69, 0, 1, 80))
        val events = ColmiDecoder.decodeNormal(frame)
        assertTrue(events.first() is RingDecodedEvent.HeartRateComplete)
    }

    @Test
    fun `manual heart rate ok`() {
        val frame = ColmiPacket.frame(byteArrayOf(0x69, 0, 0, 77))
        val events = ColmiDecoder.decodeNormal(frame)
        val hr = events.first() as RingDecodedEvent.HeartRateSample
        assertEquals(77, hr.bpm)
    }

    @Test
    fun `battery notification`() {
        val frame = ColmiPacket.frame(byteArrayOf(0x73, 0x0C, 65, 0))
        val events = ColmiDecoder.decodeNormal(frame)
        val battery = events.first() as RingDecodedEvent.Battery
        assertEquals(65, battery.percent)
    }

    @Test
    fun `live activity notification`() {
        // Fields are BIG-endian u24 (verified against on-ring frames; the iOS decoder still
        // reads little-endian): steps=500 (0x00 0x01 0xF4), calories raw=123000 → 123 kcal
        // (0x01 0xE0 0x78), distance=300 m (0x00 0x01 0x2C).
        val frame = ColmiPacket.frame(byteArrayOf(
            0x73, 0x12,
            0x00, 0x01, 0xF4.toByte(),
            0x01, 0xE0.toByte(), 0x78,
            0x00, 0x01, 0x2C,
        ))
        val events = ColmiDecoder.decodeNormal(frame)
        val activity = events.first() as RingDecodedEvent.ActivityUpdate
        assertEquals(500, activity.steps)
        assertEquals(300, activity.distanceMeters)
        assertEquals(123, activity.calories)
    }

    @Test
    fun `live activity with implausible step count decodes as ack`() {
        // A little-endian (or corrupt) frame inflates steps past the plausibility guard;
        // the decoder must not let one bad packet poison the day's total.
        val frame = ColmiPacket.frame(byteArrayOf(
            0x73, 0x12,
            0xF4.toByte(), 0x01, 0x00,   // BE read = 15,991,040 steps
            0x00, 0x00, 0x00,
            0x00, 0x00, 0x00,
        ))
        val events = ColmiDecoder.decodeNormal(frame)
        assertTrue(events.first() is RingDecodedEvent.CommandAck)
    }

    // MARK: Big-data

    private fun bigData(type: UByte, payload: ByteArray): ByteArray {
        val len = payload.size
        return byteArrayOf(
            0xBC.toByte(), type.toByte(),
            (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
            0, 0
        ) + payload
    }

    @Test
    fun `temperature big data`() {
        var payload = byteArrayOf(0x00, 0x1E.toByte())
        payload += byteArrayOf(150.toByte(), 0) // hour 0: t00 = 150 → 35.0°C, t30 empty
        payload += ByteArray(46) // pad to a full 24h of pairs so length ≥ 50
        val frame = bigData(ColmiCommandID.BIG_DATA_TEMPERATURE, payload)
        val events = ColmiDecoder.decodeBigData(frame, zone = zone)
        val temps = events.filterIsInstance<RingDecodedEvent.TemperatureSample>()
        assertEquals(35.0, temps.first().celsius, 0.01)
    }

    @Test
    fun `spo2 big data`() {
        var payload = byteArrayOf(0x00, 96, 98)
        payload += ByteArray(46)
        val frame = bigData(ColmiCommandID.BIG_DATA_SPO2, payload)
        val events = ColmiDecoder.decodeBigData(frame, zone = zone)
        val spo2s = events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
            .filter { it.kind_field == MeasurementKind.SPO2 }
        assertEquals(97.0, spo2s.first().value, 0.01)
    }

    @Test
    fun `sleep big data maps stages`() {
        val start = 480; val end = 540
        val payload = byteArrayOf(
            0x01, 0x00, 0x08,
            (start and 0xFF).toByte(), ((start shr 8) and 0xFF).toByte(),
            (end and 0xFF).toByte(), ((end shr 8) and 0xFF).toByte(),
            ColmiCommandID.SLEEP_DEEP.toByte(), 30,
            ColmiCommandID.SLEEP_REM.toByte(), 30,
        )
        val len = payload.size
        val bytes = byteArrayOf(
            0xBC.toByte(), ColmiCommandID.BIG_DATA_SLEEP.toByte(),
            (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
            0, 0
        ) + payload
        val events = ColmiDecoder.decodeBigData(bytes, zone = zone)
        val sleep = events.first() as RingDecodedEvent.SleepTimeline
        assertTrue(sleep.stages.contains(SleepStage.DEEP))
        assertTrue(sleep.stages.contains(SleepStage.REM))
        assertEquals(30, sleep.stages.count { it == SleepStage.DEEP })
        assertEquals(30, sleep.stages.count { it == SleepStage.REM })
    }

    // MARK: Reassembly

    @Test
    fun `big data reassembly across packets`() {
        val writer = NullWriter()
        val driver = ColmiDriver(writer)
        val notifyV2 = ColmiUUIDs.NOTIFY_V2

        var payload = byteArrayOf(0x00, 0x1E.toByte(), 150.toByte(), 0)
        payload += ByteArray(46)
        val full = bigData(ColmiCommandID.BIG_DATA_TEMPERATURE, payload)
        val firstHalf = full.copyOfRange(0, 10)
        val secondHalf = full.copyOfRange(10, full.size)

        val firstEvents = driver.ingest(firstHalf, notifyV2)
        assertTrue(firstEvents.isEmpty())

        val secondEvents = driver.ingest(secondHalf, notifyV2)
        val temps = secondEvents.filterIsInstance<RingDecodedEvent.TemperatureSample>()
        assertFalse(temps.isEmpty())
    }

    @Test
    fun `sequential big data transfers each decode and reset the buffer`() {
        // Real rings stream one big-data reply at a time (QRing can't handle interleaving, so
        // firmware doesn't produce it). Verify the reassembly buffer resets cleanly between
        // transfers: a split SpO2 transfer completes, then a whole sleep frame decodes without
        // being contaminated by the previous transfer.
        val driver = ColmiDriver(NullWriter())
        val notifyV2 = ColmiUUIDs.NOTIFY_V2

        var spo2Payload = byteArrayOf(0x00, 96, 98)
        spo2Payload += ByteArray(46)
        val spo2Full = bigData(ColmiCommandID.BIG_DATA_SPO2, spo2Payload)
        driver.ingest(spo2Full.copyOfRange(0, 12), notifyV2)
        val spo2Events = driver.ingest(spo2Full.copyOfRange(12, spo2Full.size), notifyV2)
        val spo2Values = spo2Events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
            .filter { it.kind_field == MeasurementKind.SPO2 }
        assertEquals(97.0, spo2Values.first().value, 0.01)

        val start = 480; val end = 540
        val sleepPayload = byteArrayOf(
            0x01, 0x00, 0x08,
            (start and 0xFF).toByte(), ((start shr 8) and 0xFF).toByte(),
            (end and 0xFF).toByte(), ((end shr 8) and 0xFF).toByte(),
            ColmiCommandID.SLEEP_DEEP.toByte(), 30,
            ColmiCommandID.SLEEP_REM.toByte(), 30,
        )
        val sleepFull = bigData(ColmiCommandID.BIG_DATA_SLEEP, sleepPayload)
        val sleepEvents = driver.ingest(sleepFull, notifyV2)
        assertTrue(sleepEvents.any { it is RingDecodedEvent.SleepTimeline })
    }

    // MARK: Real R11 captures

    private val realActivityBuckets = listOf(
        "432606174c06072d05e800a9000000a2",
        "43260617480507a32a8406670500009d",
        "4326061744040776133b037a02000018",
        "432606174003074714290393020000ec",
        "432606173c02077f231f06860400001c",
        "432606172c010757000f000b0000002b",
        "4326061728000798001b00130000007b",
    )

    @Test
    fun `real activity buckets decode without calories`() {
        val now = Instant.parse("2026-06-18T00:00:00Z")
        var totalSteps = 0
        for (hex in realActivityBuckets) {
            val data = hexToBytes(hex)
            val events = ColmiDecoder.decodeHistory(
                data, day = LocalDate.of(2026, 6, 18), now = now, zone = zone
            )
            assertEquals(1, events.size)
            val bucket = events.first() as RingDecodedEvent.ActivityBucket
            totalSteps += bucket.steps
        }
        assertEquals(5145, totalSteps)
    }

    @Test
    fun `activity buckets keep 15-minute slice starts`() {
        // v[4] is a quarter-of-day slot index (0..95): slot 77 (0x4D) = 19:15, not 19:00.
        // Collapsing to the hour would make same-hour slices share a startEpoch and
        // upsert-replace each other in activity_buckets.
        val frame = ColmiPacket.frame(byteArrayOf(
            0x43, 0x26, 0x06, 0x17,      // 2026-06-17
            0x4D,                        // slot 77 → 19:15
            0x00, 0x01,                  // packet 0 of 1
            0x00, 0x00,                  // calories (unused)
            0x64, 0x00,                  // steps = 100
            0x50, 0x00,                  // distance = 80 m
        ))
        val now = Instant.parse("2026-06-18T00:00:00Z")
        val events = ColmiDecoder.decodeHistory(
            frame, day = LocalDate.of(2026, 6, 17), now = now, zone = zone
        )
        val bucket = events.first() as RingDecodedEvent.ActivityBucket
        assertEquals(Instant.parse("2026-06-17T19:15:00Z"), bucket._timestamp)
        assertEquals(100, bucket.steps)
        assertEquals(80, bucket.distanceMeters)
    }

    @Test
    fun `same-hour activity slices decode to distinct timestamps`() {
        val now = Instant.parse("2026-06-18T00:00:00Z")
        val timestamps = (76..79).map { slot ->   // 19:00, 19:15, 19:30, 19:45
            val frame = ColmiPacket.frame(byteArrayOf(
                0x43, 0x26, 0x06, 0x17, slot.toByte(),
                0x00, 0x01, 0x00, 0x00, 0x64, 0x00, 0x50, 0x00,
            ))
            val events = ColmiDecoder.decodeHistory(
                frame, day = LocalDate.of(2026, 6, 17), now = now, zone = zone
            )
            (events.first() as RingDecodedEvent.ActivityBucket)._timestamp
        }
        assertEquals(4, timestamps.distinct().size)
        assertEquals(Instant.parse("2026-06-17T19:45:00Z"), timestamps.last())
    }

    // MARK: Pref read replies

    @Test
    fun `auto HR pref read reply decodes enabled flag and interval`() {
        // [0x16, READ, flag(0x01=on/0x02=off), interval minutes]
        val on = ColmiDecoder.decodeAutoHRPrefRead(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x01, 30)))!!
        assertTrue(on.enabled)
        assertEquals(30, on.intervalMinutes)

        val off = ColmiDecoder.decodeAutoHRPrefRead(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x02, 60)))!!
        assertFalse(off.enabled)
        assertEquals(60, off.intervalMinutes)
    }

    @Test
    fun `auto HR pref write ack is not a read reply`() {
        assertNull(ColmiDecoder.decodeAutoHRPrefRead(ColmiPacket.frame(byteArrayOf(0x16, 0x02, 0x01, 5))))
        assertNull(ColmiDecoder.decodeAutoHRPrefRead(ColmiPacket.frame(byteArrayOf(0x03, 84, 1))))
    }

    @Test
    fun `temp pref read reply decodes enabled flag`() {
        // [0x3A, 0x03, READ, enabled] — mirrors writeTempPref's extra 0x03 framing byte.
        assertEquals(true, ColmiDecoder.decodeTempPrefRead(ColmiPacket.frame(byteArrayOf(0x3A, 0x03, 0x01, 0x01))))
        assertEquals(false, ColmiDecoder.decodeTempPrefRead(ColmiPacket.frame(byteArrayOf(0x3A, 0x03, 0x01, 0x00))))
        assertNull(ColmiDecoder.decodeTempPrefRead(ColmiPacket.frame(byteArrayOf(0x3A, 0x03, 0x02, 0x01))))
    }

    @Test
    fun `realtime heart rate no reading reply`() {
        val frame = hexToBytes("9eee000000000000000000000000008c")
        val events = ColmiDecoder.decodeNormal(frame)
        assertFalse(events.any { it is RingDecodedEvent.HeartRateSample })
    }

    @Test
    fun `manual heart rate warm-up emits nothing`() {
        val warmUp = ColmiPacket.frame(byteArrayOf(0x69, 0x02, 0x00, 0x00))
        assertTrue(ColmiDecoder.decodeNormal(warmUp).isEmpty())

        val reading = ColmiPacket.frame(byteArrayOf(0x69, 0x02, 0x00, 0x4F))
        assertTrue(ColmiDecoder.decodeNormal(reading).any {
            it is RingDecodedEvent.HeartRateSample && it.bpm == 79
        })

        val errReply = ColmiPacket.frame(byteArrayOf(0x69, 0x02, 0x01, 0x4F))
        assertTrue(ColmiDecoder.decodeNormal(errReply).any { it is RingDecodedEvent.HeartRateComplete })
    }

    // MARK: Command-channel routing

    @Test
    fun `colmi routes big data to command channel`() {
        val driver = ColmiDriver(NullWriter())
        assertTrue(driver.usesCommandChannel(byteArrayOf(0xBC.toByte(), ColmiCommandID.BIG_DATA_SLEEP.toByte())))
        assertFalse(driver.usesCommandChannel(ColmiPacket.frame(byteArrayOf(0x03))))
        assertEquals(ColmiUUIDs.COMMAND, driver.commandUUID)
    }

    @Test
    fun `jring has no command channel`() {
        val driver = JringDriver(NullWriter())
        assertNull(driver.commandUUID)
        assertFalse(driver.usesCommandChannel(byteArrayOf(0x14)))
    }

    // MARK: Encoder

    @Test
    fun `encoder phone name`() {
        val cmd = ColmiEncoder.phoneName()
        assertEquals(0x04.toByte(), cmd[0])
        assertEquals('P'.code.toByte(), cmd[3])
        assertEquals('L'.code.toByte(), cmd[4])
    }

    @Test
    fun `goal command encodes u32le`() {
        val cmd = RingEncoder.makeGoalCommand(10000)
        assertEquals(0x1A.toByte(), cmd[0])
        assertEquals(0x10.toByte(), cmd[1]) // 10000 & 0xff
        assertEquals(0x27.toByte(), cmd[2]) // (10000 >> 8) & 0xff
    }

    // Auto-HR (0x16) has a different shape from the other prefs: on/off is 0x01/0x02
    // (not 0x01/0x00) and carries the sampling interval in minutes, then the four trailing
    // fields the official QRing app sends (hrStart, hrTooLow, hrTooHigh, hrTooSwitch — all 0
    // = no HR alarms). The full record is required by newer RT-series firmware (R09) to arm
    // background HR. See docs/qring-ble-adoption.md §Auto-HR.
    @Test
    fun `auto heart rate enable carries 0x01 flag, interval and zeroed alarm fields`() {
        val cmd = ColmiEncoder.autoHeartRate(enabled = true, intervalMinutes = 5)
        assertArrayEquals(byteArrayOf(0x16, 0x02, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00), cmd)
    }

    @Test
    fun `auto heart rate disable uses 0x02 flag`() {
        val cmd = ColmiEncoder.autoHeartRate(enabled = false, intervalMinutes = 5)
        assertArrayEquals(byteArrayOf(0x16, 0x02, 0x02, 0x05, 0x00, 0x00, 0x00, 0x00), cmd)
    }

    // Device-support (0x3C): QRing's QCDataParser strips the opcode before the rsp class runs,
    // so supportBlePair (rsp bArr[1] & 0x08) is FULL-frame byte 2, and supportIntervalTemp
    // (rsp bArr[8] & 0x80) is full-frame byte 9. The original port read frame[1] — a byte QRing
    // never parses — which silently broke the R09 bond gate.
    @Test
    fun `device support decodes supportBlePair from full-frame byte 2`() {
        assertEquals(true, ColmiDecoder.decodeDeviceSupport(
            ColmiPacket.frame(byteArrayOf(0x3C, 0x00, 0x08)))?.supportsBlePair)
        // Bit set among others → still true
        assertEquals(true, ColmiDecoder.decodeDeviceSupport(
            ColmiPacket.frame(byteArrayOf(0x3C, 0x00, 0x0F)))?.supportsBlePair)
        // Bit clear → false
        assertEquals(false, ColmiDecoder.decodeDeviceSupport(
            ColmiPacket.frame(byteArrayOf(0x3C, 0x00, 0x07)))?.supportsBlePair)
        // The OLD (wrong) offset must not trigger a bond: bit in frame[1], frame[2] clear.
        assertEquals(false, ColmiDecoder.decodeDeviceSupport(
            ColmiPacket.frame(byteArrayOf(0x3C, 0x08, 0x00)))?.supportsBlePair)
        // Not a device-support frame → null
        assertNull(ColmiDecoder.decodeDeviceSupport(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x01, 30))))
    }

    @Test
    fun `device support decodes supportIntervalTemp from full-frame byte 9`() {
        val withBit = ColmiPacket.frame(
            byteArrayOf(0x3C, 0, 0, 0, 0, 0, 0, 0, 0, 0x80.toByte()))
        assertEquals(true, ColmiDecoder.decodeDeviceSupport(withBit)?.supportsIntervalTemp)
        val withoutBit = ColmiPacket.frame(
            byteArrayOf(0x3C, 0, 0, 0, 0, 0, 0, 0, 0, 0x7F))
        assertEquals(false, ColmiDecoder.decodeDeviceSupport(withoutBit)?.supportsIntervalTemp)
    }

    @Test
    fun `auto heart rate interval rounds to 5-minute multiples within 5-60`() {
        assertEquals(5.toByte(), ColmiEncoder.autoHeartRate(enabled = true, intervalMinutes = 7)[3])
        assertEquals(60.toByte(), ColmiEncoder.autoHeartRate(enabled = true, intervalMinutes = 90)[3])
        assertEquals(5.toByte(), ColmiEncoder.autoHeartRate(enabled = true, intervalMinutes = 0)[3])
        assertEquals(30.toByte(), ColmiEncoder.autoHeartRate(enabled = true, intervalMinutes = 33)[3])
    }

    // MARK: Helpers

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class NullWriter : RingCommandWriter {
        override fun enqueue(command: ByteArray) {}
    }
}

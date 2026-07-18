package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

class YCBTHealthRecordsTest {

    private fun bytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        require(clean.length % 2 == 0)
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun values(kind: MeasurementKind, events: List<RingDecodedEvent>): List<Double> {
        return events.mapNotNull { event ->
            if (event is RingDecodedEvent.HistoryMeasurement && event.kind_field == kind) event.value else null
        }
    }

    private fun timestamps(events: List<RingDecodedEvent>): List<java.time.Instant> {
        return events.mapNotNull { event ->
            if (event is RingDecodedEvent.HistoryMeasurement) event._timestamp else null
        }
    }

    // Fixtures
    private val capturedBodyRecord = bytes("1cf0de3103023e0505030402050530002a0c3700b00484030d000000")
    private val capturedHeartRecords = bytes(
        "1cf0de3100471afede310042260cdf31003f3b1adf31003e4328df3100425136df31003c6444df3100419852df31003a"
    )
    private val capturedAllRecords = bytes(
        "1cf0de31080d47734c620e3404000f00000033de1afede31000042704a610d2b02000f000000d24b260cdf3100003f70" +
        "49610db106000f000000ce273b1adf3100003e6d49600c5f02000f00000077a54328df310000426f49610d2105000f00" +
        "0000474b5136df3100003c6f47600c2104000f00000024f66444df310000416e49610d3d05000f00000015769852df31" +
        "00003a6a465f0c8002000f000000d89d"
    )
    private val capturedNight = bytes(
        "affaa4019fe9de31bd58df31ffff971efb15733af29fe9de313c0500f1dceede312d0100f30af0de31d90100f2e4f1de31c9" +
        "0400f1aef6de31320100f3e1f7de31c10100f2a3f9de31b50400f159fede319b0100f3f5ffde31b00100f2a501df31660200" +
        "f10b04df313d0500f34809df31ae0800f2f611df31cc0100f1c213df31170200f3d915df317d0100f25617df31ef0000f345" +
        "18df31060100f24b19df31670200f1b21bdf31910000f2431cdf31030000f3461cdf314c0000f2921cdf31f70100f3891edf" +
        "31720200f2fb20df31e00000f3db21df31590100f23423df310e0100f34224df31d30100f21526df317a0000f38f26df31c1" +
        "0000f25027df31be0400f10f2cdf312f0100f33f2ddf31aa0100f2ea2edf317a0500f16534df316b0100f3d135df319e0000" +
        "f17036df319e0100f20e38df31450500f1543ddf318b0100f3e03edf31de0100f2bf40df31000500f1c045df315e0100f31f" +
        "47df31730100f29348df31a00000f13449df319c0000f2d049df316d0500f13e4fdf31410100f38050df31d80100f25952df" +
        "31050100f15f53df311e0100f27d54df31400400"
    )

    @Test
    fun `heart rate records decode every record`() {
        val events = YCBTHealthRecords.heartRate(capturedHeartRecords)
        assertEquals(listOf(71.0, 66.0, 63.0, 62.0, 66.0, 60.0, 65.0, 58.0), values(MeasurementKind.HEART_RATE, events))
        assertEquals(YCBTBytes.date(836_694_044), timestamps(events).first())
    }

    @Test
    fun `heart rate drops zero samples`() {
        val buffer = bytes("1cf0de310000" + "1afede310042")
        assertEquals(1, YCBTHealthRecords.heartRate(buffer).size)
    }

    @Test
    fun `combined vitals decodes BP SpO2 and HRV without activity`() {
        val events = YCBTHealthRecords.combinedVitals(capturedAllRecords)
        assertEquals(listOf(98.0, 97.0, 97.0, 96.0, 97.0, 96.0, 97.0, 95.0), values(MeasurementKind.SPO2, events))
        assertEquals(listOf(52.0, 43.0, 177.0, 95.0, 33.0, 33.0, 61.0, 128.0), values(MeasurementKind.HRV, events))
        assertEquals(listOf(115.0, 112.0, 112.0, 109.0, 111.0, 111.0, 110.0, 106.0), values(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, events))
        assertEquals(70.0, values(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, events).last(), 0.001)
        assertFalse(events.any { it is RingDecodedEvent.BloodPressureSample })
        assertFalse(events.any { it is RingDecodedEvent.ActivityUpdate })
    }

    @Test
    fun `combined vitals decodes respiratory rate`() {
        val events = YCBTHealthRecords.combinedVitals(capturedAllRecords)
        assertEquals(listOf(14.0, 13.0, 13.0, 12.0, 13.0, 12.0, 13.0, 12.0), values(MeasurementKind.RESPIRATORY_RATE, events))
    }

    @Test
    fun `combined vitals skips unmeasured temperature and blood sugar`() {
        val events = YCBTHealthRecords.combinedVitals(capturedAllRecords)
        assertTrue(values(MeasurementKind.TEMPERATURE, events).isEmpty())
        assertTrue(values(MeasurementKind.BLOOD_SUGAR, events).isEmpty())
    }

    @Test
    fun `combined vitals decodes temperature and blood sugar when present`() {
        val events = YCBTHealthRecords.combinedVitals(bytes("1cf0de31721046764f610f3a0324061504370000"))
        assertEquals(36.6, values(MeasurementKind.TEMPERATURE, events).firstOrNull() ?: 0.0, 0.001)
        assertEquals(99.088, values(MeasurementKind.BLOOD_SUGAR, events).firstOrNull() ?: 0.0, 0.001)
    }

    @Test
    fun `unworn combined vitals record produces no activity`() {
        val events = YCBTHealthRecords.combinedVitals(bytes("1cf0de31080d4700000000000000000000000000"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `partial trailing record is dropped`() {
        val events = YCBTHealthRecords.heartRate(bytes("1cf0de310047" + "1afede3100"))
        assertEquals(1, events.size)
    }

    @Test
    fun `sleep decodes a full night matching the app`() {
        val event = YCBTHealthRecords.sleep(capturedNight).first() as RingDecodedEvent.SleepTimeline
        val deep = event.stages.count { it == SleepStage.DEEP }
        val light = event.stages.count { it == SleepStage.LIGHT }
        val rem = event.stages.count { it == SleepStage.REM }
        assertTrue(kotlin.math.abs(93 - deep) <= 3)
        assertTrue(kotlin.math.abs(249 - light) <= 3)
        assertTrue(kotlin.math.abs(130 - rem) <= 3)
        assertFalse(event.stages.contains(SleepStage.AWAKE))
    }

    @Test
    fun `multiple sessions in one buffer`() {
        val timelines = YCBTHealthRecords.sleep(capturedNight + capturedNight).filterIsInstance<RingDecodedEvent.SleepTimeline>()
        assertEquals(2, timelines.size)
    }

    @Test
    fun `nap segment does not truncate the night`() {
        val session = sleepSession(listOf(
            0xf2 to 60 * 60,
            0xf5 to 20 * 60,
            0xf1 to 30 * 60,
        ))
        val event = YCBTHealthRecords.sleep(session).first() as RingDecodedEvent.SleepTimeline
        assertEquals(60, event.stages.count { it == SleepStage.LIGHT })
        assertEquals(20, event.stages.count { it == SleepStage.UNKNOWN })
        assertEquals(30, event.stages.count { it == SleepStage.DEEP })
    }

    @Test
    fun `segment duration is u24`() {
        val session = sleepSession(listOf(0xf2 to 72_000))
        val event = YCBTHealthRecords.sleep(session).first() as RingDecodedEvent.SleepTimeline
        assertEquals(1200, event.stages.size)
    }

    @Test
    fun `repeated segment is counted once`() {
        var session = sleepSession(listOf(0xf2 to 30 * 60, 0xf1 to 30 * 60, 0xf3 to 30 * 60))
        val deep = session.copyOfRange(20 + 8, 20 + 16)
        session[2] = (20 + 4 * 8).toByte()
        session[3] = 0
        session += deep
        val event = YCBTHealthRecords.sleep(session).first() as RingDecodedEvent.SleepTimeline
        assertEquals(30, event.stages.count { it == SleepStage.DEEP })
        assertEquals(90, event.stages.size)
    }

    @Test
    fun `truncated session is clamped`() {
        var session = sleepSession(listOf(0xf2 to 600, 0xf1 to 600))
        session = session.copyOfRange(0, session.size - 8)
        val event = YCBTHealthRecords.sleep(session).first() as RingDecodedEvent.SleepTimeline
        assertEquals(10, event.stages.size)
    }

    @Test
    fun `SpO2 records decode`() {
        val events = YCBTHealthRecords.spo2(bytes("1cf0de3100611afede310100260cdf31005f"))
        assertEquals(listOf(97.0, 95.0), values(MeasurementKind.SPO2, events))
        assertEquals(YCBTBytes.date(836_694_044), timestamps(events).first())
    }

    @Test
    fun `blood pressure records decode`() {
        val events = YCBTHealthRecords.bloodPressure(bytes("1cf0de3101764f401afede3100000000"))
        assertEquals(listOf(118.0), values(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, events))
        assertEquals(listOf(79.0), values(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, events))
        assertEquals(listOf(64.0), values(MeasurementKind.HEART_RATE, events))
        assertFalse(events.any { it is RingDecodedEvent.BloodPressureSample })
        assertEquals(YCBTBytes.date(836_694_044), timestamps(events).first())
    }

    @Test
    fun `body data decodes HRV in ms and stress fatigue on apps hundred scale`() {
        val events = YCBTHealthRecords.bodyData(capturedBodyRecord)
        assertEquals(62.5, values(MeasurementKind.HRV, events).firstOrNull() ?: 0.0, 0.001)
        assertEquals(53.0, values(MeasurementKind.STRESS, events).firstOrNull() ?: 0.0, 0.001)
        assertEquals(42.0, values(MeasurementKind.FATIGUE, events).firstOrNull() ?: 0.0, 0.001)
        assertEquals(42.0, values(MeasurementKind.VO2MAX, events).firstOrNull() ?: 0.0, 0.001)
        assertEquals(YCBTBytes.date(836_694_044), timestamps(events).first())
    }

    @Test
    fun `stress score is digit concatenated not a decimal composite`() {
        assertEquals(53.0, YCBTHealthRecords.score(5, 3), 0.001)
        assertEquals(70.0, YCBTHealthRecords.score(7, 0), 0.001)
        assertEquals(100.0, YCBTHealthRecords.score(10, 0), 0.001)
        assertEquals(5.3, YCBTHealthRecords.composite(5, 3), 0.001)
    }

    @Test
    fun `short body data record is dropped not misread`() {
        val events = YCBTHealthRecords.bodyData(capturedBodyRecord + capturedBodyRecord.copyOfRange(0, 17))
        assertEquals(1, values(MeasurementKind.HRV, events).size)
        assertEquals(listOf(42.0), values(MeasurementKind.VO2MAX, events))
    }

    @Test
    fun `sport records decode to activity buckets`() {
        val events = YCBTHealthRecords.sport(bytes("1cf0de31a0f3de318002e00119001afede319e01df31000000000000"))
        assertEquals(1, events.size)
        val bucket = events.first() as RingDecodedEvent.ActivityBucket
        assertEquals(YCBTBytes.date(836_694_044), bucket._timestamp)
        assertEquals(640, bucket.steps)
        assertEquals(480, bucket.distanceMeters)
    }

    @Test
    fun `temperature records decode with string concat fraction`() {
        val events = YCBTHealthRecords.temperature(bytes("1cf0de310024051afede31002419260cdf3100000f"))
        val temps = values(MeasurementKind.TEMPERATURE, events)
        assertEquals(2, temps.size)
        assertEquals(36.5, temps[0], 0.001)
        assertEquals(36.25, temps[1], 0.001)
        assertEquals(YCBTBytes.date(836_694_044), timestamps(events).first())
    }

    @Test
    fun `temperature filler fraction is dropped even with non zero integer`() {
        val events = YCBTHealthRecords.temperature(bytes("1cf0de3100240f" + "1afede3100000f"))
        assertTrue(values(MeasurementKind.TEMPERATURE, events).isEmpty())
    }

    @Test
    fun `comprehensive decodes blood sugar as mgdl`() {
        val events = YCBTHealthRecords.comprehensive(bytes(
            "1cf0de3101050500000000000000000000000000000000000000000000000000000000000000000000000000" +
            "1afede31010000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        ))
        val sugar = values(MeasurementKind.BLOOD_SUGAR, events)
        assertEquals(1, sugar.size)
        assertEquals(99.088, sugar[0], 0.001)
        assertEquals(YCBTBytes.date(836_694_044), timestamps(events).first())
    }

    @Test
    fun `every catalog type decodes through the type table`() {
        val spo2 = bytes("1cf0de3100611afede310100260cdf31005f")
        val blood = bytes("1cf0de3101764f401afede3100000000")
        val sport = bytes("1cf0de31a0f3de318002e00119001afede319e01df31000000000000")
        val temperature = bytes("1cf0de310024051afede31002419260cdf3100000f")

        assertEquals(2, YCBTHealthRecords.decode(spo2, YCBTHistoryType.SPO2).size)
        assertEquals(3, YCBTHealthRecords.decode(blood, YCBTHistoryType.BLOOD).size)
        assertEquals(1, YCBTHealthRecords.decode(sport, YCBTHistoryType.SPORT).size)
        assertEquals(2, YCBTHealthRecords.decode(temperature, YCBTHistoryType.TEMPERATURE).size)
        assertEquals(4, YCBTHealthRecords.decode(capturedBodyRecord, YCBTHistoryType.BODY_DATA).size)
        assertEquals(8 * 5, YCBTHealthRecords.decode(capturedAllRecords, YCBTHistoryType.ALL).size)
        assertFalse(YCBTHealthRecords.decode(capturedNight, YCBTHistoryType.SLEEP).isEmpty())
        assertFalse(YCBTHealthRecords.decode(capturedHeartRecords, YCBTHistoryType.HEART).isEmpty())
    }

    private fun sleepSession(segments: List<Pair<Int, Int>>): ByteArray {
        val recordLength = 20 + segments.size * 8
        val out = mutableListOf<Byte>()
        out.add(0xaf.toByte())
        out.add(0xfa.toByte())
        out.add((recordLength and 0xFF).toByte())
        out.add((recordLength shr 8).toByte())
        repeat(16) { out.add(0) }
        for ((index, segment) in segments.withIndex()) {
            val start = 0x31def01c + index * 3600
            out.add(segment.first.toByte())
            out.add((start and 0xFF).toByte())
            out.add(((start shr 8) and 0xFF).toByte())
            out.add(((start shr 16) and 0xFF).toByte())
            out.add(((start shr 24) and 0xFF).toByte())
            out.add((segment.second and 0xFF).toByte())
            out.add(((segment.second shr 8) and 0xFF).toByte())
            out.add(((segment.second shr 16) and 0xFF).toByte())
        }
        return out.toByteArray()
    }
}

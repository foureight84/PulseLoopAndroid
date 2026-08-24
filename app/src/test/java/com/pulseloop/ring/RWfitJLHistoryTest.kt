package com.pulseloop.ring

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vendor-layout oracles for the JieLi (`0xAB`) `05`-group history decoders, hand-assembled from
 * the parser offsets in `x5/b.java` (`decompiled-rwfit-official/sources/`) rather than from the
 * implementation. Every fixture comment cites the vendor line it was built from.
 *
 * Timestamps are asserted the same way `RWfitDecoderTest` asserts legacy ones — through the same
 * tz correction the decoder applies, not against a hard-coded epoch, because the correction
 * (`utils/b.java:250-252`, `getOffset(now)`) is timezone-dependent. [raw2000] inverts the
 * decoder's conversion so a fixture stamped with a plain Unix instant comes back as that instant.
 */
class RWfitJLHistoryTest {

    /** The vendor's JieLi correction: `utils.b.i() / 1000` = `getOffset(now)` (`utils/b.java:250-252`). */
    private fun jlTzCorrection(): Long =
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L

    /** Unix seconds → the ring's epoch-2000 stamp the decoder must turn back into those seconds. */
    private fun raw2000(unix: Long): Long = unix - RWfitJLHistory.JIELI_EPOCH_SECONDS + jlTzCorrection()

    private fun be32(v: Long) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun be16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    private fun be24(v: Int) = byteArrayOf(
        ((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private val t0 = 1_723_000_000L

    /** One 6-byte series record: `[ts2000 u32][value byte @+4][pad]` (V/S/W/Y at `x5/b.java:1314,1371,1486,1150`). */
    private fun rec6(unix: Long, value: Int): ByteArray = be32(raw2000(unix)) + byteArrayOf(value.toByte(), 0x00)

    /** One 6-byte series record: `[ts2000 u32][value u16 @+4..5]` (U/R at `x5/b.java:1261,1097`) — the stride is exactly 6. */
    private fun rec6u16(unix: Long, value: Int): ByteArray = be32(raw2000(unix)) + be16(value)

    /** One 7-byte sleep record: `[ts2000 u32][model @+4][2 unused]` (Z at `x5/b.java:1537,1538`). */
    private fun recSleep(unix: Long, model: Int): ByteArray =
        be32(raw2000(unix)) + byteArrayOf(model.toByte(), 0x00, 0x00)

    // ── Steps (a0, id -60) ───────────────────────────────────────────────────────

    @Test
    fun `steps decodes 16-byte records with 3-byte count and decimetre distance`() {
        // a0() @1558-1574: [ts u32][pad @+4][steps d(i+5,i+7) @1570][calorie d(i+8,i+11)/10 @1571]
        // [distance d(i+12,i+15)/10000 @1572], stride 16 @1573. Distance raw is decimetres, so
        // metres = raw/10 (the vendor renders raw/10000 as km). The middle record has 0 steps and
        // is dropped, as in the iOS port's bucket filter.
        val rec1 = be32(raw2000(t0)) + byteArrayOf(0x00) + be24(8421) + be32(3100) + be32(124_000)
        val rec2 = be32(raw2000(t0 + 3600)) + byteArrayOf(0x00) + be24(0) + be32(0) + be32(0)
        val rec3 = be32(raw2000(t0 + 7200)) + byteArrayOf(0x00) + be24(1234) + be32(450) + be32(2_000)

        val events = RWfitJLHistory.decodeSteps(rec1 + rec2 + rec3)

        assertEquals(2, events.size)
        val b1 = events[0] as RingDecodedEvent.ActivityBucket
        assertEquals(8421, b1.steps)
        assertEquals(12_400, b1.distanceMeters)   // 124000 raw decimetres / 10
        assertEquals(t0, b1._timestamp.epochSecond)
        val b2 = events[1] as RingDecodedEvent.ActivityBucket
        assertEquals(1234, b2.steps)
        assertEquals(200, b2.distanceMeters)
        assertEquals(t0 + 7200, b2._timestamp.epochSecond)
    }

    @Test
    fun `steps timestamp is epoch-2000 base minus the getOffset correction`() {
        // A raw stamp of 0 must land on 2000-01-01T00:00:00Z minus the zone correction — the
        // `+ 946684800` at a0() @1562, not the 2001 base the old triage notes carried.
        val rec = be32(0) + byteArrayOf(0x00) + be24(1) + be32(0) + be32(0)
        val event = (RWfitJLHistory.decodeSteps(rec).single() as RingDecodedEvent.ActivityBucket)
        assertEquals(
            Instant.ofEpochSecond(RWfitJLHistory.JIELI_EPOCH_SECONDS - jlTzCorrection()),
            event._timestamp,
        )
    }

    @Test
    fun `steps ignores a partial 16-byte tail`() {
        // The vendor loop's guard only tests the record start (a0 @1555) and would run off the end
        // of a torn body; the port stops at the boundary instead — identical on well-formed bodies.
        val full = be32(raw2000(t0)) + byteArrayOf(0x00) + be24(500) + be32(10) + be32(100)
        val torn = full + full.copyOfRange(0, 10)
        assertEquals(1, RWfitJLHistory.decodeSteps(torn).size)
        assertTrue(RWfitJLHistory.decodeSteps(ByteArray(0)).isEmpty())
    }

    // ── Heart rate (V, id -62) ──────────────────────────────────────────────────

    @Test
    fun `heart rate decodes 6-byte records and drops zero bpm`() {
        // V() @1296-1321: [ts u32][hr @+4 @1314][pad], stride 6 @1317; the vendor drops
        // `hr == 0` itself (@1318-1320) — a "no reading" slot, not a 0 bpm sample.
        val p = rec6(t0, 72) + rec6(t0 + 60, 0) + rec6(t0 + 120, 88) + byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte()) // torn tail
        val events = RWfitJLHistory.decodeHeartRate(p).filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(2, events.size)
        assertEquals(MeasurementKind.HEART_RATE, events[0].kind_field)
        assertEquals(72.0, events[0].value, 0.0)
        assertEquals(t0, events[0]._timestamp.epochSecond)
        assertEquals(88.0, events[1].value, 0.0)
        assertEquals(t0 + 120, events[1]._timestamp.epochSecond)
    }

    // ── Blood pressure (T, id -64) ──────────────────────────────────────────────

    @Test
    fun `blood pressure emits systolic and diastolic from a 6-byte record`() {
        // T() @1187-1210: [ts u32][sp @+4 @1205-1207][dp @+5 @1208], stride 6 @1209. A zero in
        // either field is a "no sample" slot and yields nothing.
        val body =
            be32(raw2000(t0)) + byteArrayOf(120.toByte(), 78) +
            be32(raw2000(t0 + 60)) + byteArrayOf(0, 78) +
            be32(raw2000(t0 + 120)) + byteArrayOf(120.toByte(), 0)

        val events = RWfitJLHistory.decodeBloodPressure(body).filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(2, events.size)
        assertEquals(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, events[0].kind_field)
        assertEquals(120.0, events[0].value, 0.0)
        assertEquals(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, events[1].kind_field)
        assertEquals(78.0, events[1].value, 0.0)
        assertEquals(events[0]._timestamp, events[1]._timestamp)
        assertEquals(t0, events[0]._timestamp.epochSecond)
    }

    // ── Sleep (Z, id -66) ───────────────────────────────────────────────────────

    @Test
    fun `sleep reconstructs a session from stage-transition records`() {
        // Z() @1523-1539 decodes the flat {time, model} stream; the session build is the vendor's
        // consumer (s1.java): 0x11 opens (1004-1006), 0x22 closes (1008-1018), segment N spans the
        // gap to record N+1 (1134-1135). The 0x11 marker's own segment counts as light (1149-1151).
        val p = recSleep(t0, 0x11) +
            recSleep(t0 + 600, 1) +    // deep segment starts 10 min in
            recSleep(t0 + 1200, 2) +   // light segment starts 20 min in
            recSleep(t0 + 1800, 0x22)  // wakeup

        val timeline = (RWfitJLHistory.decodeSleep(p).single() as RingDecodedEvent.SleepTimeline)

        assertEquals(30, timeline.stages.size)
        assertEquals(SleepStage.LIGHT, timeline.stages[0])     // the 0x11 marker's segment
        assertEquals(SleepStage.DEEP, timeline.stages[10])
        assertEquals(SleepStage.LIGHT, timeline.stages[20])
        assertEquals(t0, timeline._timestamp.epochSecond)      // anchored at the 0x11 marker
        assertTrue(timeline.completeSession)
    }

    @Test
    fun `sleep maps the vendor stage bytes 1 deep 2 light 0 and 3 awake 4 rem`() {
        // s1.java:1139-1157 — NOT the legacy 0/1/2/3 map (s1.java:1636-1645 is the 0x7E consumer).
        val p = recSleep(t0, 0x11) +
            recSleep(t0 + 60, 2) +
            recSleep(t0 + 120, 1) +
            recSleep(t0 + 180, 4) +
            recSleep(t0 + 240, 3) +
            recSleep(t0 + 300, 0) +
            recSleep(t0 + 360, 0x22)

        val stages = (RWfitJLHistory.decodeSleep(p).single() as RingDecodedEvent.SleepTimeline).stages

        assertEquals(listOf(SleepStage.LIGHT, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM,
            SleepStage.AWAKE, SleepStage.AWAKE), stages)
    }

    @Test
    fun `sleep counts the session-start marker segment as light like the vendor`() {
        // s1.java:1149-1151: the 0x11 marker's own segment (gap to the next record) is tallied
        // into lightTime, so a night that is otherwise all awake still carries one light minute.
        val p = recSleep(t0, 0x11) +
            recSleep(t0 + 60, 0) +
            recSleep(t0 + 120, 0x22)

        val stages = (RWfitJLHistory.decodeSleep(p).single() as RingDecodedEvent.SleepTimeline).stages
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.AWAKE), stages)
    }

    @Test
    fun `sleep emits nothing for a marker pair with no minutes and an unclosed tail`() {
        // A back-to-back 0x11/0x22 pair has a zero-minute marker segment, so no stages accumulate
        // (minutes > 0 guard, s1.java's delta division); a session without its 0x22 marker
        // produces no DataSleep in the vendor either (s1.java:1113).
        val p = recSleep(t0, 0x11) +
            recSleep(t0, 0x22) +
            recSleep(t0 + 7200, 0x11) +
            recSleep(t0 + 7320, 1)   // no wakeup marker follows

        assertTrue(RWfitJLHistory.decodeSleep(p).isEmpty())
    }

    @Test
    fun `sleep decodes two closed sessions and drops a torn 7-byte tail`() {
        val p = recSleep(t0, 0x11) +
            recSleep(t0 + 120, 1) +
            recSleep(t0 + 240, 0x22) +
            recSleep(t0 + 3600, 0x11) +
            recSleep(t0 + 3720, 2) +
            recSleep(t0 + 3840, 0x22) +
            recSleep(t0 + 7200, 0x11).copyOfRange(0, 4)   // torn tail: 4 of 7 bytes

        val timelines = RWfitJLHistory.decodeSleep(p).filterIsInstance<RingDecodedEvent.SleepTimeline>()

        assertEquals(2, timelines.size)
        assertEquals(t0, timelines[0]._timestamp.epochSecond)
        assertEquals(t0 + 3600, timelines[1]._timestamp.epochSecond)
    }

    // ── Temperature (U, id -68) ─────────────────────────────────────────────────

    @Test
    fun `temperature is raw u16 over 10 with no legacy plus-200 offset`() {
        // U() @1243-1263: setTemp(d(i+4, i+5) / 10.0f) @1261 — the value is already °C×10 on the
        // JieLi wire (the +200 encoding belongs to the 0x7E stream's u0()). Raw 0 = no sample.
        val p = rec6u16(t0, 365) + rec6u16(t0 + 60, 0)
        val events = RWfitJLHistory.decodeTemperature(p).filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(1, events.size)
        assertEquals(MeasurementKind.TEMPERATURE, events[0].kind_field)
        assertEquals(36.5, events[0].value, 1e-9)
        assertEquals(t0, events[0]._timestamp.epochSecond)
    }

    // ── SpO2 (S, id -70) ────────────────────────────────────────────────────────

    @Test
    fun `spo2 decodes byte 4 and drops zero`() {
        // S() @1132-1154: setBloodOxy(bArr[i+4] & 255) @1150-1152, stride 6 @1153.
        val p = rec6(t0, 97) + rec6(t0 + 60, 0)
        val events = RWfitJLHistory.decodeSpo2(p).filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(1, events.size)
        assertEquals(MeasurementKind.SPO2, events[0].kind_field)
        assertEquals(97.0, events[0].value, 0.0)
        assertEquals(t0, events[0]._timestamp.epochSecond)
    }

    // ── HRV (W, id -72) ─────────────────────────────────────────────────────────

    @Test
    fun `hrv decodes byte 4 and drops zero`() {
        // W() @1353-1375: setHrv(bArr[i+4] & 255) @1371-1373, stride 6 @1374.
        val p = rec6(t0, 42) + rec6(t0 + 60, 0)
        val events = RWfitJLHistory.decodeHrv(p).filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(1, events.size)
        assertEquals(MeasurementKind.HRV, events[0].kind_field)
        assertEquals(42.0, events[0].value, 0.0)
    }

    // ── Stress (Y, id -74) ──────────────────────────────────────────────────────

    @Test
    fun `stress decodes byte 4 and drops zero like the vendor`() {
        // Y() @1467-1492: setPressure(bArr[i+4] & 255) @1486-1488, stride 6 @1489, and the vendor
        // drops value == 0 itself (@1490-1492).
        val p = rec6(t0, 33) + rec6(t0 + 60, 0)
        val events = RWfitJLHistory.decodeStress(p).filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(1, events.size)
        assertEquals(MeasurementKind.STRESS, events[0].kind_field)
        assertEquals(33.0, events[0].value, 0.0)
    }

    // ── Blood sugar (R, id -112) ────────────────────────────────────────────────

    @Test
    fun `blood sugar is u16 over 10 mmol converted to the app unit mg per dL`() {
        // R() @1085-1098: setSugar(d(i+4, i+5) / 10.0f) @1097 — the vendor displays mmol/L
        // (SugarStatisticsFragment.java:439-442), and this app's BLOOD_SUGAR kind speaks mg/dL
        // everywhere, so the port converts with the standard glucose factor (18.016), same
        // convention as YCBTHealthRecords.bloodSugarMgdl. Raw 0 = no sample.
        val p = rec6u16(t0, 56) + rec6u16(t0 + 60, 0)
        val events = RWfitJLHistory.decodeBloodSugar(p).filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(1, events.size)
        assertEquals(MeasurementKind.BLOOD_SUGAR, events[0].kind_field)
        assertEquals(5.6 * 18.016, events[0].value, 1e-9)
        assertEquals(t0, events[0]._timestamp.epochSecond)
    }

    // ── Dispatch ────────────────────────────────────────────────────────────────

    @Test
    fun `unknown 05 keys decode to null so the driver keeps logging them`() {
        // Sport {5,14,16} (Q @1011), Muslim count {5,23,16} (X @1405) and the rest of the
        // 05-group table (y5/c.java:105-166) have no PulseLoop metric — decode() must say "not
        // ported", not fabricate a layout. The {.,.,0x30} delete variants have no vendor parser at
        // all, so they land in the same branch.
        assertNull(RWfitJLHistory.decode(0x0E, ByteArray(12)))
        assertNull(RWfitJLHistory.decode(0x14, ByteArray(12)))
        assertNull(RWfitJLHistory.decode(0x17, ByteArray(12)))
    }

    @Test
    fun `every ported key answers through the shared dispatch`() {
        val keys = listOf(
            RWfitProtocol.JLDataType.STEPS,
            RWfitProtocol.JLDataType.HEART_RATE,
            RWfitProtocol.JLDataType.BLOOD_PRESSURE,
            RWfitProtocol.JLDataType.SLEEP,
            RWfitProtocol.JLDataType.TEMPERATURE,
            RWfitProtocol.JLDataType.SPO2,
            RWfitProtocol.JLDataType.HRV,
            RWfitProtocol.JLDataType.STRESS,
            RWfitProtocol.JLDataType.BLOOD_SUGAR,
        )
        for (key in keys) {
            // An empty body (bare-triple reply from a ring that holds no records) decodes to
            // nothing, never an error — the vendor's loops simply don't run (e.g. a0 @1555).
            assertTrue("$key", RWfitJLHistory.decode(key, ByteArray(0)).orEmpty().isEmpty())
        }
    }
}

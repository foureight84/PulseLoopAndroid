package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Payload-layout oracles taken from `x5/b.java` in `decompiled-rwfit-official/sources/`.
 *
 * Timestamps are asserted through the same tz correction the decoder applies, rather than against a
 * hard-coded epoch — the vendor's own correction is timezone-dependent (and knowingly quirky), so
 * pinning an absolute value here would just make the suite fail outside one zone.
 */
class RWfitDecoderTest {

    private fun tzCorrection(): Long {
        val tz = TimeZone.getDefault()
        return (tz.rawOffset + if (tz.useDaylightTime()) 3_600_000L else 0L) / 1000
    }

    private fun be32(v: Long) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun be16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    private fun be24(v: Int) = byteArrayOf(
        ((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private val dayTs = 1_723_000_000L

    // ── Battery ─────────────────────────────────────────────────────────────────

    @Test
    fun `battery percent is payload byte 2, not byte 0`() {
        // PowerBean = [lowPowerFlag, powerStatus, percent]. Reading byte 0 yields the low-power
        // boolean, which is how the reverted driver reported every ring as 0% or 1%.
        val event = RWfitDecoder.decodeBattery(byteArrayOf(0x00, 0x00, 0x53)).single()
        assertEquals(RingDecodedEvent.Battery(percent = 83, charging = false), event)
    }

    @Test
    fun `battery reports charging from powerStatus`() {
        val event = RWfitDecoder.decodeBattery(byteArrayOf(0x01, 0x01, 0x2A)).single()
        assertEquals(RingDecodedEvent.Battery(percent = 42, charging = true), event)
    }

    @Test
    fun `battery ignores a short frame`() {
        assertTrue(RWfitDecoder.decodeBattery(byteArrayOf(0x00)).isEmpty())
    }

    // ── Sync manifest (0xA0) ────────────────────────────────────────────────────

    @Test
    fun `sync manifest decodes its bit flags`() {
        // v0(): [count u16][flagsA][flagsB]; flagsA bits 0..7 = step/sleep/hr/bp/spo2/temp/breathe/ecg
        val manifest = RWfitDecoder.decodeSyncManifest(byteArrayOf(0x00, 0x2A, 0b0001_0101, 0x01))!!

        assertEquals(42, manifest.totalDataCount)
        assertTrue(manifest.hasSteps)      // bit 0
        assertTrue(!manifest.hasSleep)     // bit 1
        assertTrue(manifest.hasHeartRate)  // bit 2
        assertTrue(!manifest.hasBloodPressure)
        assertTrue(manifest.hasSpo2)       // bit 4
        assertTrue(!manifest.hasTemperature)
        assertTrue(manifest.hasSport)      // flagsB bit 0
    }

    @Test
    fun `pending streams follow the vendor cascade order`() {
        val manifest = RWfitDecoder.decodeSyncManifest(byteArrayOf(0x00, 0x05, 0xFF.toByte(), 0x00))!!
        assertEquals(
            listOf(
                RWfitProtocol.HistoryType.STEPS,
                RWfitProtocol.HistoryType.SLEEP,
                RWfitProtocol.HistoryType.HEART_RATE,
                RWfitProtocol.HistoryType.BLOOD_PRESSURE,
                RWfitProtocol.HistoryType.SPO2,
                RWfitProtocol.HistoryType.TEMPERATURE,
                RWfitProtocol.HistoryType.BREATHE,
            ),
            manifest.pendingStreams(),
        )
    }

    @Test
    fun `sync manifest rejects a short frame`() {
        assertNull(RWfitDecoder.decodeSyncManifest(byteArrayOf(0x00, 0x01)))
    }

    // ── HR / SpO2 / BP / temperature history ────────────────────────────────────

    @Test
    fun `heart rate history decodes day header plus 5-byte items`() {
        val p = be32(dayTs) + be16(2) +
            be32(dayTs + 60) + byteArrayOf(72) +
            be32(dayTs + 120) + byteArrayOf(88.toByte())

        val events = RWfitDecoder.decodeHeartRateHistory(p)
            .filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(2, events.size)
        assertEquals(MeasurementKind.HEART_RATE, events[0].kind_field)
        assertEquals(72.0, events[0].value, 0.0)
        assertEquals(dayTs + 60 - tzCorrection(), events[0]._timestamp.epochSecond)
        assertEquals(88.0, events[1].value, 0.0)
    }

    @Test
    fun `heart rate history drops out-of-range samples`() {
        val p = be32(dayTs) + be16(2) +
            be32(dayTs) + byteArrayOf(0) +          // 0 bpm = no reading
            be32(dayTs + 60) + byteArrayOf(65)
        assertEquals(1, RWfitDecoder.decodeHeartRateHistory(p).size)
    }

    @Test
    fun `spo2 history decodes and clamps`() {
        val p = be32(dayTs) + be16(2) +
            be32(dayTs) + byteArrayOf(97.toByte()) +
            be32(dayTs + 60) + byteArrayOf(0)       // dropped
        val events = RWfitDecoder.decodeSpo2History(p).filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(1, events.size)
        assertEquals(MeasurementKind.SPO2, events[0].kind_field)
        assertEquals(97.0, events[0].value, 0.0)
    }

    @Test
    fun `blood pressure history emits systolic and diastolic from a 6-byte item`() {
        val p = be32(dayTs) + be16(1) + be32(dayTs + 30) + byteArrayOf(120.toByte(), 78)
        val events = RWfitDecoder.decodeBloodPressureHistory(p)
            .filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(2, events.size)
        assertEquals(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, events[0].kind_field)
        assertEquals(120.0, events[0].value, 0.0)
        assertEquals(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, events[1].kind_field)
        assertEquals(78.0, events[1].value, 0.0)
        assertEquals(events[0]._timestamp, events[1]._timestamp)
    }

    @Test
    fun `temperature is offset-encoded as raw plus 200 over 10`() {
        // u0(): temp = ((raw & 255) + 200) / 10.0 — raw 165 → 36.5 °C.
        val p = be32(dayTs) + be16(1) + be32(dayTs) + byteArrayOf(165.toByte())
        val events = RWfitDecoder.decodeTemperatureHistory(p)
            .filterIsInstance<RingDecodedEvent.HistoryMeasurement>()

        assertEquals(1, events.size)
        assertEquals(36.5, events[0].value, 1e-9)
    }

    @Test
    fun `multiple day records in one payload all decode`() {
        val p = be32(dayTs) + be16(1) + be32(dayTs) + byteArrayOf(60) +
            be32(dayTs + 86_400) + be16(1) + be32(dayTs + 86_400) + byteArrayOf(62)
        assertEquals(2, RWfitDecoder.decodeHeartRateHistory(p).size)
    }

    @Test
    fun `a truncated record stops decoding instead of reading past the end`() {
        val p = be32(dayTs) + be16(5) + be32(dayTs) + byteArrayOf(60)   // claims 5, carries 1
        assertTrue(RWfitDecoder.decodeHeartRateHistory(p).isEmpty())
    }

    // ── Steps (0xA1) ────────────────────────────────────────────────────────────

    @Test
    fun `step history decodes the 15-byte day header and skips its items`() {
        val p = be32(dayTs) + be24(8421) + be24(310) + be24(6200) + be16(2) +
            byteArrayOf(0) + be16(100) + be24(4) + be16(70) +
            byteArrayOf(1) + be16(250) + be24(9) + be16(180)

        val event = RWfitDecoder.decodeStepHistory(p).single() as RingDecodedEvent.ActivityUpdate

        assertEquals(8421, event.steps)
        assertEquals(310, event.calories)
        assertEquals(6200, event.distanceMeters)
        assertEquals(dayTs - tzCorrection(), event._timestamp.epochSecond)
    }

    // ── Sleep (0xA2) ────────────────────────────────────────────────────────────

    @Test
    fun `sleep history expands runs into per-minute stages`() {
        val asleep = dayTs + 3600
        val p = be32(dayTs) + be16(240) + be32(asleep) + be32(asleep + 28_800) + be16(3) +
            byteArrayOf(30, 1) +   // 30 min light
            byteArrayOf(20, 2) +   // 20 min deep
            byteArrayOf(10, 3)     // 10 min REM

        val timeline = RWfitDecoder.decodeSleepHistory(p).single() as RingDecodedEvent.SleepTimeline

        assertEquals(60, timeline.stages.size)
        assertEquals(SleepStage.LIGHT, timeline.stages[0])
        assertEquals(SleepStage.DEEP, timeline.stages[30])
        assertEquals(SleepStage.REM, timeline.stages[50])
        assertEquals(asleep - tzCorrection(), timeline._timestamp.epochSecond)
        assertTrue(timeline.completeSession)
    }

    @Test
    fun `stage type 0 is awake`() {
        val asleep = dayTs + 3600
        val p = be32(dayTs) + be16(60) + be32(asleep) + be32(asleep + 3600) + be16(2) +
            byteArrayOf(5, 0) + byteArrayOf(10, 1)

        val timeline = RWfitDecoder.decodeSleepHistory(p).single() as RingDecodedEvent.SleepTimeline

        assertEquals(SleepStage.AWAKE, timeline.stages[0])
        assertEquals(SleepStage.LIGHT, timeline.stages[5])
    }

    @Test
    fun `an all-awake record is not a sleep session`() {
        val asleep = dayTs + 3600
        val p = be32(dayTs) + be16(0) + be32(asleep) + be32(asleep) + be16(1) + byteArrayOf(20, 0)
        assertTrue(RWfitDecoder.decodeSleepHistory(p).isEmpty())
    }
}

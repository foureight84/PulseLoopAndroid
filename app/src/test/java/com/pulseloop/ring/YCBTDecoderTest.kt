package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class YCBTDecoderTest {
    private val decoder = YCBTDecoder()

    @Test
    fun `live heart rate decodes`() {
        val frame = YCBTFrame.validating(hexToBytes("06010700521a55"))!!
        val events = decoder.decode(frame)
        val hr = events.first() as RingDecodedEvent.HeartRateSample
        assertEquals(82, hr.bpm)
    }

    @Test
    fun `live status decodes steps distance calories`() {
        val frame = YCBTFrame.validating(hexToBytes("06000c007b0297011a00b60d"))!!
        val events = decoder.decode(frame)
        val activity = events.first() as RingDecodedEvent.ActivityUpdate
        assertEquals(635, activity.steps)
        assertEquals(407, activity.distanceMeters)
        assertEquals(26, activity.calories)
    }

    @Test
    fun `live vitals decodes BP shape`() {
        val frame = YCBTFrame.validating(hexToBytes("060314006f4a44000000000000000000000074f1"))!!
        val events = decoder.decode(frame)
        val bp = events[0] as RingDecodedEvent.BloodPressureSample
        assertEquals(111, bp.systolic)
        assertEquals(74, bp.diastolic)
        val hr = events.first { it is RingDecodedEvent.HeartRateSample } as RingDecodedEvent.HeartRateSample
        assertEquals(68, hr.bpm)
    }

    @Test
    fun `live vitals decodes HRV shape`() {
        val frame = YCBTFrame.validating(hexToBytes("06031400000000b1000000000000000000001579"))!!
        val events = decoder.decode(frame)
        val hrv = events.first() as RingDecodedEvent.HrvSample
        assertEquals(177, hrv.value)
        assertEquals(1, events.size)
    }

    @Test
    fun `live vitals decodes SpO2 and temperature tail`() {
        val frame = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x06, 0x03, 118, 79, 70, 42, 97, 36, 4)))!!
        val events = decoder.decode(frame)
        val spo2 = events.first { it is RingDecodedEvent.Spo2Result } as RingDecodedEvent.Spo2Result
        assertEquals(97, spo2.value)
        val temp = events.first { it is RingDecodedEvent.TemperatureSample } as RingDecodedEvent.TemperatureSample
        assertEquals(36.4, temp.celsius, 0.001)
    }

    @Test
    fun `battery push decodes`() {
        val frame = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x06, 0x15, 0x01, 0x5b)))!!
        val events = decoder.decode(frame)
        val battery = events.first() as RingDecodedEvent.Battery
        assertEquals(91, battery.percent)
    }

    @Test
    fun `wearing status push decodes worn and not worn`() {
        val seconds = YCBTBytes.ringSeconds(Instant.now())
        val ts = byteArrayOf(
            (seconds and 0xFF).toByte(),
            ((seconds shr 8) and 0xFF).toByte(),
            ((seconds shr 16) and 0xFF).toByte(),
            ((seconds shr 24) and 0xFF).toByte(),
        )
        val worn = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x06, 0x13) + ts + byteArrayOf(0x01)))!!
        val ws = decoder.decode(worn).first() as RingDecodedEvent.WearingStatus
        assertTrue(ws.worn)

        val removed = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x06, 0x13) + ts + byteArrayOf(0x00)))!!
        val ws2 = decoder.decode(removed).first() as RingDecodedEvent.WearingStatus
        assertFalse(ws2.worn)
    }

    @Test
    fun `measurement status push decodes per type`() {
        fun decodeStatus(payload: ByteArray): RingDecodedEvent? {
            val frame = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x04, 0x13) + payload))!!
            return decoder.decode(frame).firstOrNull()
        }

        val hr = decodeStatus(byteArrayOf(0x00, 0x01, 72)) as RingDecodedEvent.HeartRateSample
        assertEquals(72, hr.bpm)

        val bp = decodeStatus(byteArrayOf(0x01, 0x01, 118, 79)) as RingDecodedEvent.BloodPressureSample
        assertEquals(118, bp.systolic)
        assertEquals(79, bp.diastolic)

        val spo2 = decodeStatus(byteArrayOf(0x02, 0x01, 98)) as RingDecodedEvent.Spo2Result
        assertEquals(98, spo2.value)

        val temp = decodeStatus(byteArrayOf(0x04, 0x01, 36, 5)) as RingDecodedEvent.TemperatureSample
        assertEquals(36.5, temp.celsius, 0.001)

        val sugar = decodeStatus(byteArrayOf(0x05, 0x01, 5, 5)) as RingDecodedEvent.BloodSugarSample
        assertEquals(5.5 * YCBTHealthRecords.MGDL_PER_MMOL, sugar.mgdl, 0.001)
    }

    @Test
    fun `measurement status push with no value acks`() {
        fun decodeStatus(payload: ByteArray): RingDecodedEvent? {
            val frame = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x04, 0x13) + payload))!!
            return decoder.decode(frame).firstOrNull()
        }
        assertTrue(decodeStatus(byteArrayOf(0x00, 0x00, 0x00)) is RingDecodedEvent.CommandAck)
        assertTrue(decodeStatus(byteArrayOf(0x03, 0x01, 16)) is RingDecodedEvent.CommandAck)
    }

    @Test
    fun `measurement result push acks`() {
        val frame = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x04, 0x0e, 0x01, 0x02)))!!
        val events = decoder.decode(frame)
        assertTrue(events.first() is RingDecodedEvent.CommandAck)
    }

    @Test
    fun `measurement start reply distinguishes acceptance from refusal`() {
        fun reply(status: Int, startedMode: Int?): RingDecodedEvent {
            val frame = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x03, 0x2f, status.toByte())))!!
            return decoder.decode(frame, startedMode = startedMode).first()
        }
        val rejected = reply(0x01, YCBTMeasurementMode.HRV) as RingDecodedEvent.MeasurementRejected
        assertEquals(YCBTMeasurementMode.HRV, rejected.mode)

        assertTrue(reply(0x00, YCBTMeasurementMode.HRV) is RingDecodedEvent.CommandAck)
        assertTrue(reply(0x01, null) is RingDecodedEvent.CommandAck)
    }

    @Test
    fun `device info decodes battery and firmware`() {
        val frame = YCBTFrame.validating(hexToBytes("02001e00a30012010064000100030000000001000000010000000000ef10"))!!
        val events = decoder.decode(frame)
        val battery = events.first { it is RingDecodedEvent.Battery } as RingDecodedEvent.Battery
        assertEquals(100, battery.percent)
        val status = events.first { it is RingDecodedEvent.Status } as RingDecodedEvent.Status
        assertEquals("1.18", status.firmware)
    }

    @Test
    fun `firmware sub version is zero padded`() {
        val frame = YCBTFrame.validating(YCBTFrame.frame(byteArrayOf(0x02, 0x00, 0xa3.toByte(), 0x00, 0x05, 0x01, 0x00, 0x64)))!!
        val events = decoder.decode(frame)
        val status = events.first { it is RingDecodedEvent.Status } as RingDecodedEvent.Status
        assertEquals("1.05", status.firmware)
    }

    @Test
    fun `date decode recovers true instant across time zone`() {
        val tz = TimeZone.getTimeZone("America/New_York")
        val trueInstant = Instant.ofEpochSecond((System.currentTimeMillis() / 1000).toLong())
        val calendar = java.util.Calendar.getInstance(tz).apply { time = java.util.Date(trueInstant.toEpochMilli()) }
        val utcCalendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCalendar.set(calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH), calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), calendar.get(java.util.Calendar.SECOND))
        val ringSeconds = (utcCalendar.timeInMillis / 1000 - YCBTBytes.EPOCH_OFFSET).toInt()
        val decoded = YCBTBytes.date(ringSeconds, tz)
        assertTrue(kotlin.math.abs(trueInstant.epochSecond - decoded.epochSecond) <= 1)
    }

    @Test
    fun `u24 reads three bytes little endian`() {
        assertEquals(72000, YCBTBytes.u24(byteArrayOf(0x40, 0x19, 0x01), 0))
        assertEquals(0, YCBTBytes.u24(byteArrayOf(0x00, 0x00), 0))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        require(clean.length % 2 == 0)
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

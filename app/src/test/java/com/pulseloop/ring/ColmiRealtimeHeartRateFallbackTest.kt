package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/**
 * Issue #55 — the Colmi R09 (`R09_9D07`, firmware `RT09_3.10.22_260420`) answers **every** `0x1E`
 * realtime-HR frame with `9e ee`, so a workout showed no bpm at all.
 *
 * The vendor app never sends `0x1E`: no `BaseReqCmd` in the QRing decompile is built with opcode
 * 30, and `BeanFactory` case 30 only *receives* it (`RealTimeHeartRateRsp`, a bare bpm push).
 * `0x1E` as a request comes from GadgetBridge, which is where PulseLoop took it from. QRing's own
 * live readings are `StartHeartRateReq.getSimpleReq(TYPE_HEARTRATE=1)` = `0x69 01 00`, stopped
 * with `StopHeartRateReq.stopHeartRate` = `0x6A 01 <bpm> 00` — the path this ring answers.
 */
class ColmiRealtimeHeartRateFallbackTest {

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
        fun opcodes(): List<Int> = commands.map { it[0].toInt() and 0xFF }
        fun clear() = commands.clear()
    }

    /** The exact frame the R09 sends back, checksum included. */
    private fun rejection(): ByteArray =
        ColmiPacket.frame(byteArrayOf(ColmiCommandID.REALTIME_HEART_RATE_ERROR.toByte(), 0xEE.toByte()))

    /** A `0x69` heart-rate stream frame: `[0x69, type=1, errCode, bpm]`. */
    private fun hrStreamFrame(bpm: Int, errCode: Int = 0): ByteArray =
        ColmiPacket.frame(byteArrayOf(
            ColmiCommandID.MANUAL_HEART_RATE.toByte(),
            ColmiCommandID.RT_HEART_RATE.toByte(),
            errCode.toByte(),
            bpm.toByte(),
        ))

    private fun engineWith(writer: RecordingWriter) = ColmiSyncEngine(writer, ColmiDecoder)

    @Test
    fun `a rejected realtime request falls the session over to the 0x69 stream`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)

        engine.startHeartRate()
        assertEquals(
            "the probe is still 0x1E — rings that answer it keep the cheaper stream",
            listOf(0x1E), writer.opcodes(),
        )

        writer.clear()
        engine.handleRawNotify(rejection())

        assertEquals(listOf(0x69), writer.opcodes())
        assertArrayEquals(byteArrayOf(0x69, 0x01), writer.commands.single())
        engine.destroy()
    }

    @Test
    fun `once rejected, later sessions start on 0x69 without re-probing`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)

        engine.startHeartRate()
        engine.handleRawNotify(rejection())
        engine.stopHeartRate()
        writer.clear()

        engine.startHeartRate()
        assertEquals(listOf(0x69), writer.opcodes())
        engine.destroy()
    }

    @Test
    fun `the fallback stop reports the last bpm on 0x6A like QRing`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)

        engine.startHeartRate()
        engine.handleRawNotify(rejection())
        // The ring streams; the engine tracks the newest reading for the stop frame.
        for (frame in listOf(hrStreamFrame(71), hrStreamFrame(74))) {
            engine.handleRawNotify(frame)
            ColmiDecoder.decodeNormal(frame).forEach { engine.handle(it) }
        }
        writer.clear()

        engine.stopHeartRate()
        assertArrayEquals(byteArrayOf(0x6A, 0x01, 74, 0x00), writer.commands.single())
        engine.destroy()
    }

    @Test
    fun `restarting an already-running fallback stream does not re-issue the start`() {
        // RingSyncCoordinator.restartWorkoutHeartRateIfActive calls startHeartRate liberally.
        val writer = RecordingWriter()
        val engine = engineWith(writer)

        engine.startHeartRate()
        engine.handleRawNotify(rejection())
        writer.clear()

        engine.startHeartRate()
        engine.startHeartRate()
        assertTrue("no duplicate 0x69 starts", writer.commands.isEmpty())
        engine.destroy()
    }

    @Test
    fun `an unsolicited rejection with no session running starts nothing`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)

        engine.handleRawNotify(rejection())
        assertTrue(writer.commands.isEmpty())

        // …but it is remembered, so the next workout skips the probe.
        engine.startHeartRate()
        assertEquals(listOf(0x69), writer.opcodes())
        engine.destroy()
    }

    @Test
    fun `a ring that answers 0x1E keeps the realtime path and its continue keepalive`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)

        engine.startHeartRate()
        // A well-behaved reply: [0x1E, bpm] (BeanFactory case 30 → RealTimeHeartRateRsp).
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x1E, 68)))
        writer.clear()

        engine.stopHeartRate()
        assertArrayEquals(byteArrayOf(0x1E, 0x02), writer.commands.single())
        engine.destroy()
    }

    @Test
    fun `the 0x9E opcode still decodes to a no-reading completion`() {
        // Unchanged behaviour for a spot measure: 0x9E ends the attempt rather than idling out.
        val events = ColmiDecoder.decodeNormal(rejection())
        assertTrue(events.single() is RingDecodedEvent.HeartRateComplete)
    }

    @Test
    fun `the 0x69 stream decodes to heart-rate samples`() {
        val events = ColmiDecoder.decodeNormal(hrStreamFrame(66))
        assertEquals(66, (events.single() as RingDecodedEvent.HeartRateSample).bpm)
        // errCode 1 is QRing's wearing-detection failure — a completion, not a sample.
        assertTrue(
            ColmiDecoder.decodeNormal(hrStreamFrame(0, errCode = 1)).single()
                is RingDecodedEvent.HeartRateComplete
        )
    }
}

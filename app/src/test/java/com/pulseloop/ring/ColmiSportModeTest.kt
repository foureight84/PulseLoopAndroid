package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

/**
 * Issue #64 — a Colmi R09 in a PulseLoop workout flashed its LED now and then and produced a bpm
 * about once a minute, where the QRing app flashes almost constantly and reads every ~10 s.
 *
 * QRing does not use the realtime-HR commands in a live activity at all. `SportRunningActivity`
 * sends `PhoneSportReq.getSportStatus(1, sportType)` = `0x77 01 <type>` on entry and consumes the
 * ring's own unsolicited `0x78` telemetry (`DeviceNotifyRsp`; bpm at payload byte 4) until it
 * sends `0x77 04`. There is no timer and no keepalive on that path — the ring drives the cadence.
 */
class ColmiSportModeTest {

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
        fun opcodes(): List<Int> = commands.map { it[0].toInt() and 0xFF }
        fun clear() = commands.clear()
    }

    /** `[0x78][dataType][status][durMin×2][bpm][steps×3][metres×3][cal×3]` as the ring pushes it. */
    private fun sportFrame(bpm: Int, status: Int = 1): ByteArray = ColmiPacket.frame(byteArrayOf(
        0x78, 0x00, status.toByte(), 0x00, 0x05, bpm.toByte(),
        0x00, 0x01, 0x2C, 0x00, 0x00, 0x64, 0x00, 0x00, 0x00,
    ))

    private fun engineWith(writer: RecordingWriter) = ColmiSyncEngine(writer, ColmiDecoder)

    @Test
    fun `a workout starts a ring-side sport session, not an HR stream`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)

        engine.startWorkoutHeartRate("cycle")

        assertArrayEquals(byteArrayOf(0x77, 0x01, 0x09), writer.commands.single())
        engine.destroy()
    }

    @Test
    fun `activity types map onto QRing's sport list, unknown ones onto Other sports`() {
        assertEquals(0x04.toUByte(), ColmiEncoder.sportType("walk"))
        assertEquals(0x07.toUByte(), ColmiEncoder.sportType("run"))
        assertEquals(0x09.toUByte(), ColmiEncoder.sportType("cycle"))
        assertEquals(0x08.toUByte(), ColmiEncoder.sportType("hike"))
        assertEquals(0x16.toUByte(), ColmiEncoder.sportType("yoga"))
        assertEquals(0x0A.toUByte(), ColmiEncoder.sportType("gym"))
        assertEquals(0x0A.toUByte(), ColmiEncoder.sportType("other"))
    }

    @Test
    fun `sport telemetry decodes to a heart-rate sample from payload byte 4`() {
        val events = ColmiDecoder.decodeNormal(sportFrame(bpm = 132))
        val sample = events.filterIsInstance<RingDecodedEvent.HeartRateSample>().single()
        assertEquals(132, sample.bpm)
    }

    @Test
    fun `a warm-up telemetry frame with no bpm is not a reading`() {
        assertTrue(ColmiDecoder.decodeNormal(sportFrame(bpm = 0)).none { it is RingDecodedEvent.HeartRateSample })
    }

    @Test
    fun `a restart after a spot measure does not reset the ring's sport record`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)
        engine.startWorkoutHeartRate("run")
        writer.clear()

        // The coordinator's spot-measure cleanup: stop the spot stream, then bring the workout back.
        engine.stopHeartRate()
        engine.startWorkoutHeartRate("run")

        assertTrue("no 0x77 may be re-sent mid-session: got ${writer.opcodes()}", 0x77 !in writer.opcodes())
        engine.destroy()
    }

    @Test
    fun `ending the workout stops the sport session`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)
        engine.startWorkoutHeartRate("walk")
        writer.clear()

        engine.stopWorkoutHeartRate()

        assertArrayEquals(byteArrayOf(0x77, 0x04, 0x04), writer.commands.first())
        engine.destroy()
    }

    @Test
    fun `a ring that rejects the sport start falls back to the plain stream, and stays there`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)
        engine.startWorkoutHeartRate("run")
        writer.clear()

        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0xF7.toByte(), 0x01)))
        assertEquals("fallback probes the realtime stream as before", listOf(0x1E), writer.opcodes())

        engine.stopWorkoutHeartRate()
        writer.clear()
        engine.startWorkoutHeartRate("run")
        assertEquals("the refusal is remembered for the next workout", listOf(0x1E), writer.opcodes())
        engine.destroy()
    }

    @Test
    fun `the watchdog resumes a silent session once, then gives it up`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)
        val t0 = 1_000_000L
        engine.startWorkoutHeartRate("cycle")
        writer.clear()

        // Fresh telemetry: nothing to do.
        engine.handleRawNotify(sportFrame(bpm = 120))
        engine.sportWatchdogTick(System.currentTimeMillis() + 10_000)
        assertTrue(writer.commands.isEmpty())

        // Past the idle bound: one resume.
        engine.sportWatchdogTick(System.currentTimeMillis() + ColmiSyncEngine.SPORT_TELEMETRY_IDLE_MS + 1)
        assertArrayEquals(byteArrayOf(0x77, 0x03, 0x09), writer.commands.single())
        writer.clear()

        // Still silent after the resume: stop the session and fall back.
        engine.sportWatchdogTick(System.currentTimeMillis() + 2 * ColmiSyncEngine.SPORT_TELEMETRY_IDLE_MS + 2)
        assertEquals(listOf(0x77, 0x1E), writer.opcodes())
        assertArrayEquals(byteArrayOf(0x77, 0x04, 0x09), writer.commands.first())
        engine.destroy()
    }

    @Test
    fun `the ring ending the session itself moves the workout onto the plain stream`() {
        val writer = RecordingWriter()
        val engine = engineWith(writer)
        engine.startWorkoutHeartRate("run")
        writer.clear()

        engine.handleRawNotify(sportFrame(bpm = 0, status = 3))

        assertEquals(listOf(0x1E), writer.opcodes())
        engine.destroy()
    }
}

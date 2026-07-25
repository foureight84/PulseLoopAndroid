package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Unit tests for CRP inbound decoding + reassembly ([CRPDecoder], [CRPFrameAssembler]) and the
 * [CRPDriver.ingest] routing. Byte layouts are from the decompiled Moyoung app (`e1/k.b` steps,
 * `g1/a.B` heart rate, `g1/a.k` frame reassembly). No BLE stack needed.
 */
class CRPDecoderTest {

    private val fdd1 = CRPUUIDs.CHAR_STEPS_NOTIFY
    private val fdd3 = CRPUUIDs.CHAR_CMD_NOTIFY

    @Test
    fun `current-steps push decodes little-endian steps distance calories`() {
        // steps=1000 (E8 03 00), distance=500 (F4 01 00), calories=42 (2A 00 00)
        val data = byteArrayOf(
            0xE8.toByte(), 0x03, 0x00,
            0xF4.toByte(), 0x01, 0x00,
            0x2A, 0x00, 0x00,
        )
        val events = CRPDecoder.decode(data, fdd1)
        assertEquals(1, events.size)
        val a = events[0] as RingDecodedEvent.ActivityUpdate
        assertEquals(1000, a.steps)
        assertEquals(500, a.distanceMeters)
        assertEquals(42, a.calories)
    }

    @Test
    fun `steps push with only the step triple still decodes, distance and calories zero`() {
        val a = CRPDecoder.decode(byteArrayOf(0x0A, 0x00, 0x00), fdd1)[0] as RingDecodedEvent.ActivityUpdate
        assertEquals(10, a.steps)
        assertEquals(0, a.distanceMeters)
        assertEquals(0, a.calories)
    }

    @Test
    fun `steps push of non-multiple-of-three length is rejected`() {
        assertTrue(CRPDecoder.decode(byteArrayOf(1, 2), fdd1).isEmpty())
    }

    // ---- Real-time vital results: framed group-1 replies on fdd3 (g1/a.java 664–712). ----
    // This ring does NOT use the standard 2a37 characteristic — HR comes back as a framed reply.

    @Test
    fun `group1 cmd9 framed reply decodes heart rate from payload0`() {
        // The exact frame the ring returned for zaggash's Measure press (issue #29): HR = 0x4a = 74.
        val frame = CRPProtocol.frame(1, CRPCommands.CMD_RESULT_HR, byteArrayOf(0x4a))
        val hrs = CRPDecoder.decode(frame, fdd3)[0] as RingDecodedEvent.HeartRateSample
        assertEquals(74, hrs.bpm)
    }

    @Test
    fun `group1 cmd9 out-of-range bpm is dropped`() {
        assertTrue(CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_HR, byteArrayOf(0)), fdd3).isEmpty())
    }

    @Test
    fun `group1 cmd10 decodes hrv, cmd11 spo2, cmd14 stress from payload0`() {
        val hrv = CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_HRV, byteArrayOf(45)), fdd3)[0]
        assertEquals(45, (hrv as RingDecodedEvent.HrvSample).value)
        val spo2 = CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_SPO2, byteArrayOf(98)), fdd3)[0]
        assertEquals(98, (spo2 as RingDecodedEvent.Spo2Result).value)
        val stress = CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_STRESS, byteArrayOf(37)), fdd3)[0]
        assertEquals(37, (stress as RingDecodedEvent.StressSample).value)
    }

    @Test
    fun `group1 cmd32 decodes temperature as two-byte tenths of a degree`() {
        // e1/m.a: (payload[1]<<8 | payload[0]) / 10 -> 365 => 36.5 C
        val frame = CRPProtocol.frame(1, CRPCommands.CMD_RESULT_TEMP, byteArrayOf(0x6D, 0x01))
        val temp = CRPDecoder.decode(frame, fdd3)[0] as RingDecodedEvent.TemperatureSample
        assertEquals(36.5, temp.celsius, 0.001)
    }

    @Test
    fun `group1 out-of-range temperature is dropped`() {
        // 0x0064 = 100 -> 10.0 C, below the 28..50 validity window.
        assertTrue(CRPDecoder.decode(CRPProtocol.frame(1, CRPCommands.CMD_RESULT_TEMP, byteArrayOf(0x64, 0x00)), fdd3).isEmpty())
    }

    // ---- Wear state: framed group-3 cmd-7 push (g1/a.java case 3->7, onWearStateChange). ----

    @Test
    fun `group3 cmd7 payload0 zero decodes wear state not worn`() {
        // The exact frame from zaggash's build-25 captures (issue #29): ring off the finger.
        val ev = CRPDecoder.decode(CRPProtocol.frame(3, CRPCommands.CMD_WEAR_STATE, byteArrayOf(0)), fdd3)[0]
        assertFalse((ev as RingDecodedEvent.WearingStatus).worn)
    }

    @Test
    fun `group3 cmd7 payload0 nonzero decodes wear state worn`() {
        val ev = CRPDecoder.decode(CRPProtocol.frame(3, CRPCommands.CMD_WEAR_STATE, byteArrayOf(1)), fdd3)[0]
        assertTrue((ev as RingDecodedEvent.WearingStatus).worn)
    }

    @Test
    fun `wear state maps to a PulseEvent WearState`() {
        val worn = RingEventBridge.eventsFor(RingDecodedEvent.WearingStatus(worn = false, _timestamp = Instant.EPOCH))
        assertEquals(false, (worn.single() as PulseEvent.WearState).worn)
    }

    @Test
    fun `unrecognised group3 cmd is acked, not dropped`() {
        val ev = CRPDecoder.decode(CRPProtocol.frame(3, 2, byteArrayOf(0)), fdd3)[0]
        assertTrue(ev is RingDecodedEvent.CommandAck)
    }

    @Test
    fun `unrecognised group1 cmd is acked, not dropped`() {
        // cmd 0 (set-user-info ack) isn't a vital result -> CommandAck, no fabricated sample.
        val ev = CRPDecoder.decode(CRPProtocol.frame(1, 0, byteArrayOf(0)), fdd3)[0]
        assertTrue(ev is RingDecodedEvent.CommandAck)
    }

    @Test
    fun `assembler returns a single-packet frame immediately`() {
        val a = CRPFrameAssembler()
        val frame = CRPProtocol.frame(1, 9, byteArrayOf(0x50)) // len 7
        assertArrayEquals(frame, a.append(frame))
    }

    @Test
    fun `assembler reassembles a frame split across two notifications`() {
        val a = CRPFrameAssembler()
        // A 10-byte frame: FD DA 10 0A 02 05 + 4 payload bytes, delivered as 6 + 4.
        val payload = byteArrayOf(1, 2, 3, 4)
        val full = CRPProtocol.frame(2, 5, payload) // size 10
        assertNull(a.append(full.copyOfRange(0, 6)))          // header only — not complete
        val done = a.append(full.copyOfRange(6, 10))          // continuation completes it
        assertArrayEquals(full, done)
    }

    @Test
    fun `assembler drops a continuation with no in-progress frame`() {
        val a = CRPFrameAssembler()
        assertNull(a.append(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `driver routes fdd1 to steps and reassembles fdd3 replies`() {
        val driver = CRPDriver(writer = null)
        val steps = driver.ingest(byteArrayOf(0x05, 0x00, 0x00), fdd1)
        assertTrue(steps.single() is RingDecodedEvent.ActivityUpdate)

        // A framed reply split across two fdd3 notifications yields exactly one decoded event.
        val full = CRPProtocol.frame(1, 9, byteArrayOf(0x50))
        assertTrue(driver.ingest(full.copyOfRange(0, 4), fdd3).isEmpty())
        assertEquals(1, driver.ingest(full.copyOfRange(4, full.size), fdd3).size)
    }

    // ── Sleep history (group 2 / cmd 14, vendor e1/j.b) ──────────────────────────────────────

    @Test
    fun `sleep history reply decodes a full night into per-minute stages`() {
        // Real payload from zaggash's Colmi R11 (issue #29): dayIndex 0 + 26 [state,hour,minute]
        // records spanning 01:07 → 08:05. Wrapped in a group-2/cmd-14 frame as it arrives on fdd3.
        val payload = hexToBytes(
            "0001010702011301013702020901021302022501022d02030201031302031801032103" +
                "040801040e03043001050002051501052502052d01053403053901061102063301063a03071301072c000805"
        )
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, payload)

        val timeline = CRPDecoder.decode(frame, fdd3).single() as RingDecodedEvent.SleepTimeline
        val stages = timeline.stages
        // One entry per minute; the night totals 418 minutes of scored sleep.
        assertEquals(418, stages.size)
        assertEquals(245, stages.count { it == SleepStage.LIGHT })
        assertEquals(110, stages.count { it == SleepStage.DEEP })
        assertEquals(63, stages.count { it == SleepStage.REM })
        // The closing awake record only terminates the night; it contributes no sleep minutes.
        assertEquals(0, stages.count { it == SleepStage.AWAKE })
    }

    @Test
    fun `sleep reply with only the day byte yields no timeline`() {
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, byteArrayOf(0x00))
        assertTrue(CRPDecoder.decode(frame, fdd3).none { it is RingDecodedEvent.SleepTimeline })
    }

    @Test
    fun `post-midnight night anchors on the query day`() {
        // dayIndex 0, light @01:00 → awake @08:00 = 7h light, starting today at 01:00.
        val payload = byteArrayOf(0, /*light*/1, 1, 0, /*awake*/0, 8, 0)
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, payload)
        val now = Instant.parse("2026-07-22T11:00:00Z")
        val timeline = CRPDecoder.decode(frame, fdd3, now, ZoneId.of("UTC")).single() as RingDecodedEvent.SleepTimeline
        assertEquals(420, timeline.stages.size)
        assertEquals(SleepStage.LIGHT, timeline.stages.first())
        assertEquals(Instant.parse("2026-07-22T01:00:00Z"), timeline._timestamp)
    }

    @Test
    fun `evening-start night rolls back before midnight`() {
        // dayIndex 0, light @23:00 → awake @06:00 = 7h, so the night began the previous evening.
        val payload = byteArrayOf(0, /*light*/1, 23, 0, /*awake*/0, 6, 0)
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, payload)
        val now = Instant.parse("2026-07-22T11:00:00Z")
        val timeline = CRPDecoder.decode(frame, fdd3, now, ZoneId.of("UTC")).single() as RingDecodedEvent.SleepTimeline
        assertEquals(420, timeline.stages.size)
        assertEquals(Instant.parse("2026-07-21T23:00:00Z"), timeline._timestamp)
    }

    @Test
    fun `a night and a nap split into separate sessions on the long awake gap`() {
        // light 01:00→03:00 (2h), awake 03:00→05:00 (2h ≥ gap → split), light 05:00→06:00 (nap), awake.
        val payload = byteArrayOf(0, 1, 1, 0, 0, 3, 0, 1, 5, 0, 0, 6, 0)
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, payload)
        val now = Instant.parse("2026-07-22T12:00:00Z")
        val events = CRPDecoder.decode(frame, fdd3, now, ZoneId.of("UTC")).filterIsInstance<RingDecodedEvent.SleepTimeline>()
        assertEquals(2, events.size)
        assertEquals(120, events[0].stages.size)
        assertEquals(Instant.parse("2026-07-22T01:00:00Z"), events[0]._timestamp)
        assertEquals(60, events[1].stages.size)
        assertEquals(Instant.parse("2026-07-22T05:00:00Z"), events[1]._timestamp)
    }

    @Test
    fun `a brief mid-night wake stays inside one session`() {
        // light 60m + awake 30m (< gap, kept) + deep 90m = one 180-minute session.
        val payload = byteArrayOf(0, 1, 1, 0, 0, 2, 0, 2, 2, 30, 0, 4, 0)
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, payload)
        val now = Instant.parse("2026-07-22T12:00:00Z")
        val timeline = CRPDecoder.decode(frame, fdd3, now, ZoneId.of("UTC")).single() as RingDecodedEvent.SleepTimeline
        assertEquals(180, timeline.stages.size)
        assertEquals(60, timeline.stages.count { it == SleepStage.LIGHT })
        assertEquals(30, timeline.stages.count { it == SleepStage.AWAKE })
        assertEquals(90, timeline.stages.count { it == SleepStage.DEEP })
    }

    @Test
    fun `malformed sleep reply length is rejected`() {
        // 4 payload bytes ⇒ length % 3 != 1, which the vendor parser refuses.
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_HISTORY_SLEEP, byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertTrue(CRPDecoder.decode(frame, fdd3).none { it is RingDecodedEvent.SleepTimeline })
    }

    // ── All-day "timing" vital history (group 2 / cmd 15/16/17/47, vendor e1/{f,d,g,l}) ──────────
    // Frames captured verbatim from zaggash's Colmi R11 (rc2, issue #29): [day][frameIndex][slots…],
    // one 5-minute slot per sample. HR/SpO2/stress = one byte/slot; HRV = little-endian 2 bytes/slot.

    /** Real group-2/cmd-15 HR frame (day 0, frame 0): 19 non-zero 5-min slots, an overnight curve. */
    private val hrTimingFrame =
        "fdda1098020f000000003a000000000000003c000000004b0000000054000000005200000000680000000063000000005400" +
        "0000006300000000006000000058000000004f00000000300000000063000000005a0000000060000000003f000000005c00" +
        "0000005000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "0000"

    /** Real group-2/cmd-16 HRV frame (day 0, frame 0): 11 non-zero slots, little-endian 2-byte values. */
    private val hrvTimingFrame =
        "fdda109802100000000000002f00000000000000000000000000000020000000000000000000000000000000000000003200" +
        "0000000000000000000000000000000000001e00000000000000000021000000000000000000220000000000000000003300" +
        "0000000000000000000021000000000000000000000000000000000024000000000000000000380000000000000000002400" +
        "0000"

    @Test
    fun `HR timing frame decodes one bpm per 5-minute slot at the right time-of-day`() {
        // day 0, so slots hang off local midnight; slot 2 → 00:10, slot 10 → 00:50, slot 95 → 07:55.
        val now = Instant.parse("2026-07-24T12:00:00Z")
        val events = CRPDecoder.decode(hexToBytes(hrTimingFrame), fdd3, now, ZoneId.of("UTC"))
        val samples = events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
        assertEquals(19, samples.size)
        assertTrue(samples.all { it.kind_field == MeasurementKind.HEART_RATE })
        // First slot: 58 bpm at 00:10 (slot 2 × 5 min).
        assertEquals(58.0, samples.first().value, 0.0)
        assertEquals(Instant.parse("2026-07-24T00:10:00Z"), samples.first()._timestamp)
        // A daytime peak the vendor keeps (within 40..200): 104 bpm at 02:30 (slot 30).
        assertTrue(samples.any { it.value == 104.0 && it._timestamp == Instant.parse("2026-07-24T02:30:00Z") })
        // Last slot: 80 bpm at 07:55 (slot 95).
        assertEquals(80.0, samples.last().value, 0.0)
        assertEquals(Instant.parse("2026-07-24T07:55:00Z"), samples.last()._timestamp)
    }

    @Test
    fun `HRV timing frame decodes little-endian two-byte samples per slot`() {
        val now = Instant.parse("2026-07-24T12:00:00Z")
        val samples = CRPDecoder.decode(hexToBytes(hrvTimingFrame), fdd3, now, ZoneId.of("UTC"))
            .filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
        assertEquals(11, samples.size)
        assertTrue(samples.all { it.kind_field == MeasurementKind.HRV })
        // First slot: 47 ms at 00:10 (slot 2, 2-byte value 0x002f).
        assertEquals(47.0, samples.first().value, 0.0)
        assertEquals(Instant.parse("2026-07-24T00:10:00Z"), samples.first()._timestamp)
        // 2-byte cadence: HRV packs 72 slots/frame, so slot 10 is still 00:50.
        assertTrue(samples.any { it.value == 32.0 && it._timestamp == Instant.parse("2026-07-24T00:50:00Z") })
    }

    @Test
    fun `an all-zero timing frame yields no samples, only the follow-up marker`() {
        // zaggash's SpO2 timeline came back all-zero (no all-day SpO2 recorded) — decode must not
        // fabricate 0-valued samples, but must still emit the frame marker so the engine advances.
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_SPO2, ByteArray(146))
        val events = CRPDecoder.decode(frame, fdd3)
        assertTrue(events.none { it is RingDecodedEvent.HistoryMeasurement })
        val marker = events.filterIsInstance<RingDecodedEvent.TimingHistoryFrame>().single()
        assertEquals(CRPCommands.CMD_QUERY_TIMING_SPO2, marker.cmd)
        assertEquals(0, marker.frameIndex)
    }

    @Test
    fun `a timing frame emits a TimingHistoryFrame marker carrying its cmd, day and index`() {
        val payload = byteArrayOf(1, 0) + ByteArray(144) // day 1, frame 0, empty slots
        val frame = CRPProtocol.frame(CRPCommands.GROUP_HISTORY, CRPCommands.CMD_QUERY_TIMING_HR, payload)
        val marker = CRPDecoder.decode(frame, fdd3).filterIsInstance<RingDecodedEvent.TimingHistoryFrame>().single()
        assertEquals(CRPCommands.CMD_QUERY_TIMING_HR, marker.cmd)
        assertEquals(1, marker.day)
        assertEquals(0, marker.frameIndex)
    }

    @Test
    fun `TimingHistoryFrame produces no PulseEvent`() {
        assertTrue(RingEventBridge.eventsFor(RingDecodedEvent.TimingHistoryFrame(CRPCommands.CMD_QUERY_TIMING_HR, 0, 0)).isEmpty())
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
}

package com.pulseloop.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy scrub applied to an exported diagnostics report: physiological payloads are masked,
 * the routing bytes that say which record a frame is are not (issue #58).
 */
class DiagnosticsRedactorTest {

    /** A real CRP temperature-history reply: `FD DA 10 98 02 16` then the day, frame index and
     *  slot values. The header must survive so a reader can tell it from any other health frame. */
    private val crpTempFrame = "fdda1098021600000000000000000000" + "6b01" + "00".repeat(20)

    @Test
    fun `a CRP health frame keeps its group and command but loses every value`() {
        val masked = DiagnosticsRedactor.maskPacketHex(crpTempFrame, "history_measurement", "CRP")

        assertEquals("fdda109802 16 identifies the record", "fdda10980216", masked.take(12))
        assertTrue("no sample bytes survive", masked.drop(12).all { it == '·' })
        assertEquals("length is preserved", crpTempFrame.length, masked.length)
    }

    @Test
    fun `a YCBT health frame keeps its four-byte header`() {
        val frame = "041300" + "48".repeat(9)
        val masked = DiagnosticsRedactor.maskPacketHex(frame, "hr_sample", "COLMI_SMART_HEALTH")

        assertEquals("04130048", masked.take(8))
        assertTrue(masked.drop(8).all { it == '·' })
    }

    /** Every family behind a YCBTDriver shares the frame — the R10M path (`YCBT`) and TK5 must not
     *  fall to the one-byte rule, or their health frames export as an anonymous `04··…`. */
    @Test
    fun `every YCBT-driven family keeps the four-byte header`() {
        val frame = "041300" + "48".repeat(9)
        for (family in listOf("YCBT", "TK5", "COLMI_SMART_HEALTH")) {
            val masked = DiagnosticsRedactor.maskPacketHex(frame, "hr_sample", family)
            assertEquals(family, "04130048", masked.take(8))
            assertTrue(family, masked.drop(8).all { it == '·' })
        }
    }

    /** Families whose opcode is byte 0 keep exactly that, as before — and so does an unknown one. */
    @Test
    fun `other families keep only the opcode byte`() {
        val frame = "69" + "5a".repeat(15)
        for (type in listOf("COLMI_R02", "JRING", "LUCK_RING", "")) {
            val masked = DiagnosticsRedactor.maskPacketHex(frame, "hr_sample", type)
            assertEquals("opcode kept for $type", "69", masked.take(2))
            assertTrue("payload masked for $type", masked.drop(2).all { it == '·' })
        }
    }

    /**
     * A Colmi `0x78` sport push with bpm 0 (warm-up, contact lost) still carries the workout's
     * live steps, distance and calories. It used to decode to nothing, fall through to `unknown`,
     * and export in clear while the neighbouring frames with a bpm were masked.
     */
    @Test
    fun `a sport telemetry frame is masked even when it carried no heart rate`() {
        val frame = "7801" + "0000" + "00" + "0007d0" + "0005dc" + "00c350"
        val masked = DiagnosticsRedactor.maskPacketHex(frame, "sport_telemetry", "COLMI")
        assertEquals("78", masked.take(2))
        assertTrue("steps, distance and calories do not survive", masked.drop(2).all { it == '·' })
    }

    /**
     * A chunk of a frame still being reassembled. It used to reach the log as `unknown`, which is
     * deliberately exported whole (control and pairing frames decode to nothing and are what most
     * connection reports are for) — so half an all-day health reply left in clear.
     */
    @Test
    fun `an assembler chunk is masked rather than exported whole`() {
        val chunk = "6b01" + "5a".repeat(18)
        val masked = DiagnosticsRedactor.maskPacketHex(chunk, DiagnosticsRedactor.FRAME_CONTINUATION, "CRP")

        assertEquals("length is preserved", chunk.length, masked.length)
        assertTrue("no sample bytes survive", masked.drop(2).all { it == '·' })
    }

    /**
     * A continuation chunk is the middle of a frame, so byte 0 onwards is payload — it must not be
     * masked as though its family's header were sitting in front of it. The opening chunk is the
     * one that has the header, and keeping it is what makes a multi-frame reply identifiable.
     */
    @Test
    fun `only the opening chunk of a frame keeps its header`() {
        val hex = "fdda1098021600000000" + "5a".repeat(10)

        val start = DiagnosticsRedactor.maskPacketHex(hex, DiagnosticsRedactor.FRAME_START, "CRP")
        val continuation = DiagnosticsRedactor.maskPacketHex(hex, DiagnosticsRedactor.FRAME_CONTINUATION, "CRP")

        assertEquals("fdda10980216", start.take(12))
        assertTrue(start.drop(12).all { it == '·' })
        assertEquals("fd", continuation.take(2))
        assertTrue("a mid-frame chunk keeps nothing but byte 0", continuation.drop(2).all { it == '·' })
    }

    /** The decoder names these kinds and the redactor matches on them; drift means a chunk exports
     *  in clear with nothing failing. */
    @Test
    fun `the chunk kinds the redactor masks are the ones the decoder emits`() {
        val opening = com.pulseloop.ring.RingDecodedEvent.FramePending(byteArrayOf(1), startsFrame = true)
        val middle = com.pulseloop.ring.RingDecodedEvent.FramePending(byteArrayOf(1), startsFrame = false)

        assertEquals(DiagnosticsRedactor.FRAME_START, opening.kind)
        assertEquals(DiagnosticsRedactor.FRAME_CONTINUATION, middle.kind)
    }

    /**
     * The header length has to come from the family that captured the packet. Exporting a report
     * after switching rings used to mask every stored frame with the connected ring's header, and
     * CRP's is the longest — six bytes of a one-byte-header family's samples kept in clear.
     */
    @Test
    fun `a packet with no recorded family is masked from byte one`() {
        val colmiFrame = "69" + "5a".repeat(15)

        val unknownFamily = DiagnosticsRedactor.maskPacketHex(colmiFrame, "hr_sample", "")
        val asCrp = DiagnosticsRedactor.maskPacketHex(colmiFrame, "hr_sample", "CRP")

        assertEquals("69", unknownFamily.take(2))
        assertTrue("only the opcode survives", unknownFamily.drop(2).all { it == '·' })
        assertEquals(
            "CRP's six-byte header is what a wrongly-attributed packet would have leaked",
            "695a5a5a5a5a", asCrp.take(12),
        )
    }

    @Test
    fun `control frames are never masked`() {
        val frame = "fdda100603030102"
        assertEquals(frame, DiagnosticsRedactor.maskPacketHex(frame, "firmware_revision", "CRP"))
        assertEquals(frame, DiagnosticsRedactor.maskPacketHex(frame, "command_ack", "CRP"))
    }

    /** A health frame shorter than its family's header must still lose a byte to masking rather
     *  than being exported whole. */
    @Test
    fun `a frame shorter than the header still masks its tail`() {
        val masked = DiagnosticsRedactor.maskPacketHex("fdda1098", "history_measurement", "CRP")
        assertEquals("fdda10··", masked)
    }

    @Test
    fun `MAC addresses are scrubbed from free text`() {
        assertEquals(
            "connected to ··:··:··:··:··:·· ok",
            DiagnosticsRedactor.scrubText("connected to A4:C1:38:9F:2B:07 ok"),
        )
    }
}

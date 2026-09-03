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

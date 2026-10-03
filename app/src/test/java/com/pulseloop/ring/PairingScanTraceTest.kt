package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEMPORARY — delete with [PairingScanTrace] once issue #96 is resolved. */
class PairingScanTraceTest {
    private val lines = mutableListOf<String>()
    private val trace = PairingScanTrace { lines += it }

    private fun see(
        address: String = "AA:BB:CC:DD:EE:01",
        name: String? = "R02_A1B2",
        type: RingDeviceType? = RingDeviceType.COLMI_R02,
        lastKnown: Boolean = false,
    ) = trace.sighting(address, name, -60, type, "colmi-r02", emptyList(), emptyList(), lastKnown)

    @Test
    fun `logs name shape without serial or MAC`() {
        trace.scanStarted(hasLastKnown = true)
        see(lastKnown = true)
        val line = lines.last()
        assertTrue(line, line.contains("name=\"R02\" suffix=_xxxx"))
        assertTrue(line, line.contains("family=COLMI_R02") && line.contains("lastKnown=true"))
        assertFalse(line, line.contains("A1B2") || line.contains("AA:BB"))
    }

    @Test
    fun `each device once per scan, again when its name changes`() {
        trace.scanStarted(hasLastKnown = false)
        see(); see()
        see(name = "R02_FFFF")
        assertEquals(3, lines.size) // start + two sightings
        trace.scanStarted(hasLastKnown = false)
        see()
        assertEquals(5, lines.size)
    }

    @Test
    fun `unnamed unmatched devices are counted, not logged`() {
        trace.scanStarted(hasLastKnown = false)
        see(address = "1", name = null, type = null)
        see(address = "2", name = null, type = null)
        see(address = "3", name = null, type = RingDeviceType.JRING)
        trace.scanEnded("stopped")
        assertEquals(3, lines.size) // start + matched-unnamed sighting + end
        assertTrue(lines[1], lines[1].contains("name=<none>"))
        assertTrue(lines.last(), lines.last().contains("logged=1 dropped=0 unnamedUnmatched=2"))
    }

    @Test
    fun `nothing is logged outside a pairing scan and end is reported once`() {
        see()
        trace.scanEnded("stopped")
        assertTrue(lines.isEmpty())
        trace.scanStarted(hasLastKnown = false)
        trace.scanEnded("ring picked")
        trace.scanEnded("stopped")
        assertEquals(2, lines.size)
    }

    @Test
    fun `caps devices per scan`() {
        trace.scanStarted(hasLastKnown = false)
        repeat(PairingScanTrace.MAX_DEVICES_PER_SCAN + 5) { see(address = "dev$it", name = "Thing$it", type = null) }
        see(address = "dev44", name = "Thing44", type = null) // a dropped device re-advertising
        see(address = "ring", name = "R02_A1B2")              // a ring is never dropped
        trace.scanEnded("stopped")
        assertEquals(PairingScanTrace.MAX_DEVICES_PER_SCAN + 3, lines.size)
        assertTrue(lines.last(), lines.last().contains("logged=41 dropped=5"))
    }

    @Test
    fun `base service UUIDs are shortened`() {
        val line = PairingScanTrace.describe(
            "R02_A1B2", -50, null, null,
            listOf("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e", "0000fee7-0000-1000-8000-00805f9b34fb"),
            listOf(0x0a1d), false,
        )
        assertTrue(line, line.contains("uuids=6e40fff0-b5a3-f393-e0a9-e50e24dcca9e,fee7 mfr=0a1d"))
    }
}

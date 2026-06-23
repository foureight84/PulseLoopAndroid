package com.pulseloop.ring

import com.pulseloop.ring.WearableCapability.Companion.toCsv
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests coordinator name matching, device type detection, and wearable capability
 * CSV serialization. Pure logic — no BLE/hardware dependency.
 */
class PairingMatchingTest {

    @Test
    fun `device type enum has expected entries`() {
        val types = RingDeviceType.entries.map { it.name }
        assertTrue(types.contains("JRING"))
        assertTrue(types.contains("COLMI_R02"))
    }

    @Test
    fun `device type display names are meaningful`() {
        assertEquals("SMART_RING", RingDeviceType.JRING.displayName)
        assertEquals("Colmi R02", RingDeviceType.COLMI_R02.displayName)
    }

    @Test
    fun `device type valueOf roundtrip`() {
        assertEquals(RingDeviceType.JRING, RingDeviceType.valueOf("JRING"))
        assertEquals(RingDeviceType.COLMI_R02, RingDeviceType.valueOf("COLMI_R02"))
    }

    @Test
    fun `connection state enum has all expected states`() {
        val states = RingConnectionState.entries.map { it.name }
        assertTrue(states.contains("IDLE"))
        assertTrue(states.contains("SCANNING"))
        assertTrue(states.contains("CONNECTING"))
        assertTrue(states.contains("CONNECTED"))
        assertTrue(states.contains("DISCONNECTED"))
    }

    // ── WearableCapability CSV ──────────────────────────────────────────

    @Test
    fun `wearable capability csv roundtrip`() {
        val caps = setOf(WearableCapability.HEART_RATE, WearableCapability.SPO2)
        val csv = caps.toCsv()
        val back = WearableCapability.fromCsv(csv)
        assertEquals(caps, back)
    }

    @Test
    fun `wearable capability csv handles empty`() {
        assertEquals(emptySet<WearableCapability>(), WearableCapability.fromCsv(""))
        assertEquals("", emptySet<WearableCapability>().toCsv())
    }

    @Test
    fun `wearable capability csv handles single entry`() {
        val caps = setOf(WearableCapability.BATTERY)
        val csv = caps.toCsv()
        assertEquals("battery", csv)
        assertEquals(caps, WearableCapability.fromCsv(csv))
    }

    @Test
    fun `wearable capability csv handles all entries`() {
        val all = WearableCapability.entries.toSet()
        val csv = all.toCsv()
        val back = WearableCapability.fromCsv(csv)
        assertEquals(all, back)
    }

    @Test
    fun `capability keys are stable`() {
        assertEquals("heartRate", WearableCapability.HEART_RATE.key)
        assertEquals("spo2", WearableCapability.SPO2.key)
        assertEquals("steps", WearableCapability.STEPS.key)
        assertEquals("sleep", WearableCapability.SLEEP.key)
        assertEquals("battery", WearableCapability.BATTERY.key)
        assertEquals("stress", WearableCapability.STRESS.key)
        assertEquals("hrv", WearableCapability.HRV.key)
        assertEquals("temperature", WearableCapability.TEMPERATURE.key)
    }

    @Test
    fun `default capability set is Jring base`() {
        val defaultCaps = WearableCapability.fromCsv("")
        assertTrue(defaultCaps.isEmpty())
    }
}

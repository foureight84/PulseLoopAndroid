package com.pulseloop.health

import com.pulseloop.health.exporters.VitalsExporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #103: tests that estimated SpO2 and derived respiratory rate are excluded from Health Connect export.
 */
class VitalsExporterFilteringTest {

    @Test
    fun `genuine spot SpO2 reading is exported`() {
        val timestamp = 1729000000000L
        val estimatedSet = setOf(1729003600000L, 1729007200000L)
        assertTrue(VitalsExporter.shouldExportSpo2(98.0, timestamp, estimatedSet))
    }

    @Test
    fun `hourly SpO2 estimated from heart rate is excluded`() {
        val timestamp = 1729003600000L
        val estimatedSet = setOf(timestamp, 1729007200000L)
        assertFalse(VitalsExporter.shouldExportSpo2(98.0, timestamp, estimatedSet))
    }

    @Test
    fun `implausible SpO2 is excluded even if not estimated`() {
        val timestamp = 1729000000000L
        assertFalse(VitalsExporter.shouldExportSpo2(105.0, timestamp, emptySet()))
        assertFalse(VitalsExporter.shouldExportSpo2(0.0, timestamp, emptySet()))
    }

    @Test
    fun `respiratory rate on YCBT ring is excluded from export`() {
        // YCBT rings derive respiratory rate as ~0.2 * HR without a real sensor
        assertFalse(VitalsExporter.shouldExportRespRate(16.0, isYcbt = true))
    }

    @Test
    fun `respiratory rate on non-YCBT ring with native sensor is exported`() {
        assertTrue(VitalsExporter.shouldExportRespRate(16.0, isYcbt = false))
    }

    @Test
    fun `implausible respiratory rate is excluded on any ring`() {
        assertFalse(VitalsExporter.shouldExportRespRate(0.0, isYcbt = false))
        assertFalse(VitalsExporter.shouldExportRespRate(100.0, isYcbt = false))
    }
}

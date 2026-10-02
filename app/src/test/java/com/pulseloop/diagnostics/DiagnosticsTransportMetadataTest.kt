package com.pulseloop.diagnostics

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTransportMetadataTest {
    @Test fun `export preserves transport metadata and excludes decoded health values`() {
        val metadata = """{"characteristic":"0000fdd3-0000-1000-8000-00805f9b34fb","generation":12,"attempt":2,"bpm":72,"payload":"48"}"""
        val expected = Json.parseToJsonElement("""{"characteristic":"0000fdd3-0000-1000-8000-00805f9b34fb","generation":12,"attempt":2}""")
        assertEquals(expected, DiagnosticsExporter.transportMetadata(metadata))
    }

    @Test fun `older rows with only decoder values do not export transport metadata`() {
        assertNull(DiagnosticsExporter.transportMetadata("""{"bpm":72}"""))
        assertNull(DiagnosticsExporter.transportMetadata(null))
    }

    @Test fun `malformed and non-object metadata cannot break an export`() {
        for (metadata in listOf("{broken", "[]", "null", "12", "{}")) {
            assertNull(DiagnosticsExporter.transportMetadata(metadata))
        }
    }
}

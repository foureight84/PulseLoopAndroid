package com.pulseloop.coach.attachments

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the attachment-ref JSON persistence ported from
 * CoachAttachmentStore.swift (the pure metadata layer; file I/O is Android-only).
 */
class CoachAttachmentRefTest {

    @Test
    fun testEncodeDecodeRoundTrip() {
        val refs = listOf(
            CoachAttachmentRef(file = "a.jpg", width = 1024, height = 768),
            CoachAttachmentRef(file = "b.jpg", mime = "image/png", width = 10, height = 20),
        )
        val json = CoachAttachmentRef.encode(refs)
        assertNotNull(json)
        assertEquals(refs, CoachAttachmentRef.decode(json))
    }

    @Test
    fun testMimeDefaultsToJpeg() {
        val decoded = CoachAttachmentRef.decode("""[{"file":"x.jpg","width":1,"height":2}]""")
        assertEquals(CoachAttachmentStore.MIME_TYPE, decoded.single().mime)
    }

    @Test
    fun testEncodeEmptyListReturnsNull() {
        assertNull(CoachAttachmentRef.encode(emptyList()))
    }

    @Test
    fun testDecodeToleratesNullBlankAndGarbage() {
        assertTrue(CoachAttachmentRef.decode(null).isEmpty())
        assertTrue(CoachAttachmentRef.decode("").isEmpty())
        assertTrue(CoachAttachmentRef.decode("   ").isEmpty())
        assertTrue(CoachAttachmentRef.decode("not json").isEmpty())
        assertTrue(CoachAttachmentRef.decode("""{"file":"x"}""").isEmpty())
    }
}

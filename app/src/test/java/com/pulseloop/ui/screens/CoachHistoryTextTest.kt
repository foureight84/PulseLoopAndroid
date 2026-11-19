package com.pulseloop.ui.screens

import com.pulseloop.data.dao.CoachConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Issue #131: how a past coach conversation reads in the history sheet. */
class CoachHistoryTextTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 21)

    private fun ms(y: Int, mo: Int, d: Int, h: Int = 9, mi: Int = 15): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun convo(updatedAt: Long, count: Int = 4) =
        CoachConversationSummary("id", updatedAt, updatedAt, "How did I sleep?", count)

    @Test
    fun `title is the first question on one line`() {
        assertEquals("How did I sleep last night?", CoachHistoryText.title("  How did I sleep\n\nlast   night?  "))
    }

    @Test
    fun `missing or blank question gets a placeholder`() {
        assertEquals("Untitled conversation", CoachHistoryText.title(null))
        assertEquals("Untitled conversation", CoachHistoryText.title(" \n "))
    }

    @Test
    fun `today and yesterday are named with the time`() {
        assertTrue(CoachHistoryText.whenText(ms(2026, 10, 21), zone, today).startsWith("Today "))
        assertTrue(CoachHistoryText.whenText(ms(2026, 10, 20), zone, today).startsWith("Yesterday "))
    }

    @Test
    fun `older dates show the year only when it differs`() {
        assertFalse(CoachHistoryText.whenText(ms(2026, 10, 12), zone, today).contains("2026"))
        assertTrue(CoachHistoryText.whenText(ms(2025, 12, 30), zone, today).contains("2025"))
    }

    @Test
    fun `subtitle counts messages and marks the open conversation`() {
        val at = ms(2026, 10, 12)
        val date = CoachHistoryText.whenText(at, zone, today)
        assertEquals("$date · 1 message", CoachHistoryText.subtitle(convo(at, 1), false, zone, today))
        assertEquals("$date · 4 messages · open now", CoachHistoryText.subtitle(convo(at), true, zone, today))
    }
}

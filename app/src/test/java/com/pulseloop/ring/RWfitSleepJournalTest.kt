package com.pulseloop.ring

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RWfitSleepJournalTest {

    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("rwfit_journal_test").toFile()
        RWfitSleepJournal.directoryOverride = tempDir
    }

    @After
    fun tearDown() {
        RWfitSleepJournal.directoryOverride = null
        tempDir.deleteRecursively()
    }

    @Test
    fun `isValidPage enforces multiple of 7 and bounds`() {
        assertFalse(RWfitSleepJournal.isValidPage(ByteArray(0)))
        assertFalse(RWfitSleepJournal.isValidPage(ByteArray(3)))
        assertFalse(RWfitSleepJournal.isValidPage(ByteArray(6)))
        assertTrue(RWfitSleepJournal.isValidPage(ByteArray(7)))
        assertTrue(RWfitSleepJournal.isValidPage(ByteArray(14)))
        assertFalse(RWfitSleepJournal.isValidPage(ByteArray(15)))
        assertTrue(RWfitSleepJournal.isValidPage(ByteArray(7 * 20)))
    }

    @Test
    fun `stages pages and deduplicates byte-identical records`() {
        val journal = RWfitSleepJournal()
        val page1 = byteArrayOf(0, 0, 0, 1, 17, 0, 0)
        val page2 = byteArrayOf(0, 0, 0, 2, 1, 0, 0)

        assertTrue(journal.stagePage(page1))
        assertEquals(1, journal.pageCount())

        // Duplicate page is ignored without error
        assertTrue(journal.stagePage(page1))
        assertEquals(1, journal.pageCount())

        assertTrue(journal.stagePage(page2))
        assertEquals(2, journal.pageCount())

        val combined = journal.assembledPayload()
        assertArrayEquals(page1 + page2, combined)
    }

    @Test
    fun `file-backed storage persists across journal instances`() {
        val journal1 = RWfitSleepJournal(tempDir)
        val page1 = byteArrayOf(0, 0, 0, 1, 17, 0, 0)
        journal1.stagePage(page1, deviceId = "ring123")
        assertEquals(1, journal1.pageCount(deviceId = "ring123"))

        // Create fresh journal pointing to same directory
        val journal2 = RWfitSleepJournal(tempDir)
        assertTrue(journal2.hasPages(deviceId = "ring123"))
        assertEquals(1, journal2.pageCount(deviceId = "ring123"))
        assertArrayEquals(page1, journal2.assembledPayload(deviceId = "ring123"))

        journal2.clear(deviceId = "ring123")
        assertFalse(journal2.hasPages(deviceId = "ring123"))
    }
}

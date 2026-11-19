package com.pulseloop.ring

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/**
 * Durable staging journal for RWfit JieLi (`0xAB`) sleep history pages.
 *
 * Ported from iOS `RWfitHistoryPersistence` (PulseLoopiOS PR #138, issue #135).
 *
 * **Why sleep requires a journal:**
 * On JieLi framing, sleep history is paginated with `GET_DEL_GET_DEL`:
 *  1. App requests sleep with `{05, 05, 10}`.
 *  2. Ring returns a 7-byte record page (which may start with `0x11` at e.g. 2:00 AM, but has no `0x22` wakeup marker yet).
 *  3. App must delete that page with `{05, 05, 30}` to advance the ring's cursor to page 2.
 *  4. App repeats until the ring returns an empty body (`payload.size < 7`), then decodes the joined records.
 *
 * Deleting advances the stream on the ring, so each page must be saved durably before sending the delete ACK.
 * If the connection drops mid-paging, or if the user syncs mid-sleep before waking up, the staged pages survive
 * disconnections and app restarts. When the user reconnects after waking up, the remaining pages are appended,
 * joined with the earlier pages, and decoded into a complete [RingDecodedEvent.SleepTimeline].
 */
class RWfitSleepJournal(
    private val baseDir: File? = null,
) {
    private val memoryPages = mutableListOf<ByteArray>()

    private val activeDir: File?
        get() = directoryOverride ?: baseDir ?: defaultBaseDir

    @Synchronized
    fun stagePage(payload: ByteArray, deviceId: String = "default"): Boolean {
        if (!isValidPage(payload)) {
            Log.w(TAG, "invalid JieLi sleep page size ${payload.size}B (must be non-empty multiple of 7)")
            return false
        }

        val dir = activeDir
        if (dir == null) {
            // In-memory fallback
            if (memoryPages.any { it.contentEquals(payload) }) return true
            if (memoryPages.size >= MAX_PAGES) {
                Log.w(TAG, "sleep journal reached maximum page capacity ($MAX_PAGES)")
                return false
            }
            memoryPages.add(payload.copyOf())
            return true
        }

        return try {
            dir.mkdirs()
            val file = journalFile(dir, deviceId)
            val existing = readPagesFromFile(file)
            if (existing.any { it.contentEquals(payload) }) return true
            if (existing.size >= MAX_PAGES) {
                Log.w(TAG, "sleep journal file reached maximum page capacity ($MAX_PAGES)")
                return false
            }
            val updated = existing + payload.copyOf()
            writePagesToFile(file, updated)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to stage sleep page to ${dir.path}", e)
            // Fallback to in-memory so sync can still proceed
            if (!memoryPages.any { it.contentEquals(payload) }) {
                memoryPages.add(payload.copyOf())
            }
            true
        }
    }

    @Synchronized
    fun assembledPayload(deviceId: String = "default"): ByteArray {
        val pages = getPages(deviceId)
        if (pages.isEmpty()) return ByteArray(0)
        val totalBytes = pages.sumOf { it.size }
        val joined = ByteArray(totalBytes)
        var offset = 0
        for (page in pages) {
            System.arraycopy(page, 0, joined, offset, page.size)
            offset += page.size
        }
        return joined
    }

    @Synchronized
    fun hasPages(deviceId: String = "default"): Boolean = getPages(deviceId).isNotEmpty()

    @Synchronized
    fun pageCount(deviceId: String = "default"): Int = getPages(deviceId).size

    @Synchronized
    fun clear(deviceId: String = "default") {
        memoryPages.clear()
        val dir = activeDir ?: return
        try {
            val file = journalFile(dir, deviceId)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "failed to delete sleep journal file", e)
        }
    }

    /** Discard pages older than [maxAgeHours] to prevent abandoned or partial sessions from lingering forever. */
    @Synchronized
    fun pruneStale(deviceId: String = "default", maxAgeHours: Long = 48) {
        val pages = getPages(deviceId)
        if (pages.isEmpty()) return
        val cutoff = Instant.now().minusSeconds(maxAgeHours * 3600L).epochSecond

        // Check timestamp of the first record in each page
        val validPages = pages.filter { page ->
            if (page.size >= 4) {
                val epochSeconds = RWfitJLHistory.instantAt(page, 0).epochSecond
                epochSeconds >= cutoff
            } else true
        }

        if (validPages.size != pages.size) {
            val dir = activeDir
            if (dir != null) {
                val file = journalFile(dir, deviceId)
                if (validPages.isEmpty()) {
                    if (file.exists()) file.delete()
                } else {
                    writePagesToFile(file, validPages)
                }
            }
            memoryPages.clear()
            memoryPages.addAll(validPages)
            Log.i(TAG, "pruned ${pages.size - validPages.size} stale sleep pages from journal")
        }
    }

    private fun getPages(deviceId: String): List<ByteArray> {
        val dir = activeDir
        if (dir != null) {
            val file = journalFile(dir, deviceId)
            if (file.exists()) {
                val diskPages = readPagesFromFile(file)
                if (diskPages.isNotEmpty()) return diskPages
            }
        }
        return memoryPages
    }

    private fun journalFile(dir: File, deviceId: String): File {
        val safeId = deviceId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        return File(dir, "rwfit_sleep_$safeId.bin")
    }

    private fun readPagesFromFile(file: File): List<ByteArray> {
        if (!file.exists()) return emptyList()
        val pages = mutableListOf<ByteArray>()
        try {
            DataInputStream(FileInputStream(file).buffered()).use { dis ->
                while (dis.available() > 0) {
                    val length = dis.readInt()
                    if (length <= 0 || length > 8192) break
                    val bytes = ByteArray(length)
                    dis.readFully(bytes)
                    if (isValidPage(bytes)) {
                        pages.add(bytes)
                    }
                }
            }
        } catch (_: Exception) {}
        return pages
    }

    private fun writePagesToFile(file: File, pages: List<ByteArray>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            DataOutputStream(FileOutputStream(temp).buffered()).use { dos ->
                for (page in pages) {
                    dos.writeInt(page.size)
                    dos.write(page)
                }
            }
            if (temp.exists()) {
                if (file.exists()) file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    companion object {
        private const val TAG = "RWfitSleepJournal"
        const val MAX_PAGES = 64

        /** Default base directory initialized from Application Context in production. */
        var defaultBaseDir: File? = null

        fun init(context: Context) {
            defaultBaseDir = File(context.filesDir, "rwfit_sleep_journal")
        }

        val defaultJournal by lazy { RWfitSleepJournal() }

        /** Test-only override for the journal directory. */
        var directoryOverride: File? = null

        fun isValidPage(payload: ByteArray): Boolean =
            payload.size in 7..8192 && (payload.size % 7) == 0
    }
}

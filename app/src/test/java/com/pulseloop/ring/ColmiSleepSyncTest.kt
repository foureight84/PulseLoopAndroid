package com.pulseloop.ring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-demand sleep decoupling (QRing parity). Verifies that [ColmiSyncEngine.syncSleepNow] fires a
 * standalone `bigDataSleep()` request off the staged history pipeline, and that its completion does
 * NOT drive the pipeline into the next stage — while the normal in-pipeline sleep completion still
 * advances to HRV. Pure — a recording writer captures enqueued commands, no hardware.
 */
class ColmiSleepSyncTest {

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
    }

    /** `bc 27 01 00 ff 00 ff` — big-data V2 sleep request. */
    private val sleepRequest = ColmiEncoder.bigDataSleep()

    @Test
    fun `syncSleepNow from idle fires exactly the standalone sleep request`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)

        engine.syncSleepNow()

        assertEquals("only the sleep request should be enqueued", 1, writer.commands.size)
        assertArrayEquals(sleepRequest, writer.commands.single())
        engine.destroy()
    }

    @Test
    fun `syncSleepNow is a no-op while a full history sync is running`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()               // stage advances into the pipeline (ACTIVITY)
        val countAfterStartup = writer.commands.size

        engine.syncSleepNow()             // full sync already fetches sleep — must not double-request

        assertEquals(countAfterStartup, writer.commands.size)
        engine.destroy()
    }

    @Test
    fun `standalone sleep completion does not advance the pipeline to HRV`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.syncSleepNow()             // sleepOnlyActive = true, 1 command (sleep)

        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)

        assertEquals("no follow-up request after a standalone sleep", 1, writer.commands.size)
        engine.destroy()
    }

    @Test
    fun `in-pipeline sleep completion still advances to HRV`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()               // sleepOnlyActive stays false; stage in pipeline
        val countAfterStartup = writer.commands.size

        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)  // normal pipeline sleep→hrv

        assertTrue("HRV request should be enqueued after in-pipeline sleep",
            writer.commands.size > countAfterStartup)
        engine.destroy()
    }

    @Test
    fun `syncSleepNow does not double-request while one is already outstanding`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)

        engine.syncSleepNow()
        engine.syncSleepNow()             // still outstanding — must be ignored

        assertEquals(1, writer.commands.size)
        engine.destroy()
    }
}

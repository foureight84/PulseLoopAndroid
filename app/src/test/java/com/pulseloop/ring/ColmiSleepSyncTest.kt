package com.pulseloop.ring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-demand sleep decoupling (QRing parity) + history-pipeline termination.
 *
 * Verifies that [ColmiSyncEngine.syncSleepNow] fires a standalone `bigDataSleep()` request off the
 * staged history pipeline and that its completion does NOT drive the pipeline into the next stage —
 * while the normal in-pipeline sleep completion still advances to HRV. Also locks in the R10 sleep
 * fixes: the New_Sleep_Protocol request frame bytes, and temperature as the terminal, idempotent
 * history stage (no follow-up request the ring answers with a re-emitted frame — the 0x47 storm).
 * Pure — a recording writer captures enqueued commands, no hardware.
 */
class ColmiSleepSyncTest {

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
    }

    /** `bc 27 02 00 81 80 ff 01` — big-data V2 New_Sleep_Protocol request. */
    private val sleepRequest = ColmiEncoder.bigDataSleep()

    /** An "empty day" history reply (`packetNr = 0xFF`): completes the stage's current day so
     *  [ColmiSyncEngine.handleHistoryFrame] advances the pipeline without decodable payload. */
    private fun emptyDayFrame(op: UByte): ByteArray = byteArrayOf(op.toByte(), 0xFF.toByte())

    /** Drive a just-started engine (stage ACTIVITY) to the SPO2 big-data stage by completing
     *  every paged history day as empty: activity days 0..7, HR days 0..7, stress days 0..6. */
    private fun driveToSpo2(engine: ColmiSyncEngine) {
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_ACTIVITY)) }
        repeat(8) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_HEART_RATE)) }
        repeat(7) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_STRESS)) }
    }

    /** Continue from SPO2 to the terminal TEMPERATURE stage: spo2 → sleep → HRV days 0..6. */
    private fun driveSpo2ToTemperature(engine: ColmiSyncEngine) {
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SPO2)
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)
        repeat(7) { engine.handleHistoryFrame(emptyDayFrame(ColmiCommandID.SYNC_HRV)) }
    }

    @Test
    fun `bigDataSleep builds the New_Sleep_Protocol frame byte-for-byte`() {
        // QRing LargeDataHandler.addHeader(39, [0xFF, 0x01]): 0xBC, action 0x27, len=2 LE,
        // CRC16/MODBUS([FF 01]) = 0x8081 LE, payload FF 01. The pre-fix 1-byte frame
        // (bc 27 01 00 ff 00 ff) is silently ignored by the ring — sleep never syncs.
        assertArrayEquals(
            byteArrayOf(
                0xBC.toByte(), 0x27, 0x02, 0x00, 0x81.toByte(), 0x80.toByte(), 0xFF.toByte(), 0x01
            ),
            ColmiEncoder.bigDataSleep()
        )
    }

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
        engine.runStartup()               // sleepOnlyActive stays false; stage = ACTIVITY
        driveToSpo2(engine)               // reach SPO2 for real — stray completions are ignored
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SPO2)   // SPO2 → SLEEP
        val countAfterSleepRequest = writer.commands.size

        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)  // normal pipeline sleep→hrv

        assertTrue("HRV request should be enqueued after in-pipeline sleep",
            writer.commands.size > countAfterSleepRequest)
        engine.destroy()
    }

    @Test
    fun `a stray sleep completion off the SLEEP stage does not jump the pipeline`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()               // stage = ACTIVITY, sleepOnlyActive = false
        val countAfterStartup = writer.commands.size

        // A standalone sleep reply that landed after a full sync started (or a late reply after the
        // watchdog skipped SLEEP): must NOT advance/duplicate the pipeline into HRV.
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SLEEP)

        assertEquals("stray sleep completion must not enqueue anything",
            countAfterStartup, writer.commands.size)
        engine.destroy()
    }

    @Test
    fun `a stray spo2 completion off the SPO2 stage does not jump the pipeline`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()               // stage = ACTIVITY
        val countAfterStartup = writer.commands.size

        // Rings can re-emit big-data frames outside their stage (the R10 did this with
        // temperature); an unguarded SPO2 completion would reset the pipeline to SLEEP.
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_SPO2)

        assertEquals("stray spo2 completion must not enqueue anything",
            countAfterStartup, writer.commands.size)
        engine.destroy()
    }

    @Test
    fun `temperature completion is terminal - no follow-up request`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()
        driveToSpo2(engine)
        driveSpo2ToTemperature(engine)    // stage = TEMPERATURE, temperature request enqueued
        val countAtTemperature = writer.commands.size

        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_TEMPERATURE)

        // Pre-fix this requested blood sugar (0x47), which Colmi rings answer by re-emitting
        // the temperature frame — a request loop that congested the GATT queue (PR #26).
        assertEquals("temperature completion must finish the sync, not request more",
            countAtTemperature, writer.commands.size)
        engine.destroy()
    }

    @Test
    fun `a re-emitted temperature frame after the sync finished is ignored`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.runStartup()
        driveToSpo2(engine)
        driveSpo2ToTemperature(engine)
        engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_TEMPERATURE)  // finishes the sync
        val countAfterFinish = writer.commands.size

        repeat(3) { engine.handleBigDataComplete(ColmiCommandID.BIG_DATA_TEMPERATURE) }

        assertEquals("duplicate temperature completions must be idempotent",
            countAfterFinish, writer.commands.size)
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

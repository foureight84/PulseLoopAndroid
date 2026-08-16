package com.pulseloop.health

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 1 (docs/health-connect-integration.md): the chunking/retry engine, tested through the
 * injected insert function — no HealthConnectClient, no database (the repo's no-mock convention).
 * Pins Gadgetbridge's production constants: 200 records per call, 5 retries, backoff
 * 1 / 2 / 4 / 8 / 16 s, SecurityException aborts on the first attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthConnectExporterTest {

    private class FakeRecord : Record {
        override val metadata: Metadata = Metadata.autoRecorded(
            Device(type = Device.TYPE_UNKNOWN, manufacturer = "test", model = "test"),
        )
    }

    private fun records(n: Int): List<Record> = List(n) { FakeRecord() }

    private fun highWaters(n: Int): List<Long> = (1L..n.toLong()).toList()

    @Test
    fun emptyInputIsTriviallyComplete() = runTest {
        val p = healthConnectInsertChunked(emptyList(), emptyList()) { fail("no records, no insert") }
        assertTrue(p.allCompleted)
        assertEquals(0, p.attempts)
        assertEquals(0, p.inserted)
        assertEquals(0L, p.lastCompletedHighWater)
    }

    @Test
    fun chunksAtTwoHundred() = runTest {
        val sizes = mutableListOf<Int>()
        val p = healthConnectInsertChunked(records(450), highWaters(450)) { chunk ->
            sizes += chunk.size
        }
        assertEquals(listOf(200, 200, 50), sizes)
        assertTrue(p.allCompleted)
        assertEquals(450, p.inserted)
        assertEquals(3, p.attempts)
        assertEquals(450L, p.lastCompletedHighWater)
    }

    @Test
    fun exactChunkBoundaryNeedsNoEmptyTailChunk() = runTest {
        val sizes = mutableListOf<Int>()
        val p = healthConnectInsertChunked(records(400), highWaters(400)) { chunk ->
            sizes += chunk.size
        }
        assertEquals(listOf(200, 200), sizes)
        assertTrue(p.allCompleted)
    }

    @Test
    fun transientFailuresAreRetriedWithBackoff() = runTest {
        var failures = 0
        val p = healthConnectInsertChunked(records(10), highWaters(10)) { chunk ->
            if (chunk.size == 10 && failures < 2) {
                failures++
                throw IllegalStateException("flaky")
            }
        }
        assertEquals(2, failures)
        assertTrue(p.allCompleted)
        assertEquals(3, p.attempts) // initial + 2 retries
        assertEquals(10, p.inserted)
        // virtual time advanced by the 1 s + 2 s backoff delays
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun securityExceptionAbortsImmediatelyWithoutRetry() = runTest {
        var attempts = 0
        try {
            healthConnectInsertChunked(records(10), highWaters(10)) { chunk ->
                attempts += chunk.size
                throw SecurityException("permission revoked")
            }
            fail("expected SecurityException")
        } catch (e: SecurityException) {
            assertEquals("permission revoked", e.message)
        }
        assertEquals(10, attempts) // one attempt only — never retried
        assertEquals(0L, testScheduler.currentTime) // no backoff delay
    }

    @Test
    fun retriesAreExhaustedThenReportedNotThrown() = runTest {
        val p = healthConnectInsertChunked(records(5), highWaters(5)) {
            throw IllegalStateException("down")
        }
        assertFalse(p.allCompleted)
        assertEquals(0, p.inserted)
        assertEquals(0L, p.lastCompletedHighWater)
        // 1 initial + 5 retries
        assertEquals(6, p.attempts)
        assertNotNull(p.lastError)
        assertEquals("down", p.lastError?.message)
        // 1+2+4+8+16 = 31 s of virtual backoff
        assertEquals(31_000L, testScheduler.currentTime)
    }

    @Test
    fun watermarkStopsAtLastSuccessfulChunk() = runTest {
        var chunk = 0
        val p = healthConnectInsertChunked(records(450), highWaters(450)) { c ->
            chunk += c.size
            if (chunk > 200) throw IllegalStateException("second chunk dies")
        }
        assertFalse(p.allCompleted)
        assertEquals(200, p.inserted)
        assertEquals(200L, p.lastCompletedHighWater) // highWaters(450) is 1..450 → first 200 max 200
    }

    @Test
    fun mismatchedHighWatersIsRejected() = runTest {
        try {
            healthConnectInsertChunked(records(3), highWaters(2)) { }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // parallel-list contract
        }
    }

    // ── sleep (Phase 2): the sleep group's watermark is the max session updatedAt of what
    //    actually landed. Sleep is a single-kind group, so its group-min rule (min of per-kind
    //    highs) reduces to this value; independence from the vitals group is pinned in
    //    HealthConnectPrefsStoreTest (watermarksAreIndependentPerKey / sleepWatermarkNeverRewinds).
    //    The full pass (run()) is runtime-verified — same convention as Phase 1.

    @Test
    fun sleepWatermarkAdvancesOnlyToLandedSessions() = runTest {
        // Three pending sessions with NON-MONOTONIC updatedAt (a re-synced old night alongside
        // newer ones). The watermark must land at the MAX of what was inserted — not the last
        // record's, not a source-row ordering artifact.
        val highWaters = listOf(700L, 900L, 500L)
        val p = healthConnectInsertChunked(records(3), highWaters) { }
        assertTrue(p.allCompleted)
        assertEquals(3, p.inserted)
        assertEquals(900L, p.lastCompletedHighWater)
    }

    @Test
    fun sleepWatermarkStopsAtMaxLandedChunkOnPartialFailure() = runTest {
        // 450 records = three 200/200/50 chunks; chunks 2–3 die. The watermark may only advance
        // over chunk 1's sessions — here the max of their updatedAt is 499, not 299 (the last
        // landed in list order) and nothing past it — so the next pass re-reads exactly what
        // was missed and re-upserts nothing that already landed.
        val highWaters = (1..450).map { if (it <= 200) 500L - it else 1000L - it }
        var chunk = 0
        val p = healthConnectInsertChunked(records(450), highWaters) { c ->
            chunk += c.size
            if (chunk > 200) throw IllegalStateException("provider down")
        }
        assertFalse(p.allCompleted)
        assertEquals(200, p.inserted)
        assertEquals(499L, p.lastCompletedHighWater)
    }

    // ── Phase 3: one source row can emit several records sharing one high water ──

    @Test
    fun activityWatermarkNeverStrandsASiblingRecordSplitAcrossAChunkBoundary() = runTest {
        // A day writes steps + energy + distance, all stamped with the row's updatedAt. Build 201
        // records so the chunk boundary falls INSIDE the 67th day, then fail chunk 2. Advancing to
        // that day's updatedAt would strand its unlanded record forever, because the DAO selects
        // on `updatedAt > watermark`.
        val highWaters = (0 until 201).map { (it / 3).toLong() + 1L }
        val p = healthConnectInsertChunked(records(201), highWaters) { c ->
            if (c.size < 200) throw IllegalStateException("second chunk fails")
        }
        assertFalse(p.allCompleted)
        assertEquals(200, p.inserted)
        // Day 67 (high water 67) straddles the boundary: 200 = 3*66 + 2, so two of its three
        // records landed and one did not. The watermark must stop at day 66.
        assertEquals(66L, p.lastCompletedHighWater)
    }

    @Test
    fun aFullyLandedRowStillAdvancesTheWatermark() = runTest {
        // Same shape, but the boundary falls cleanly between rows — nothing is stranded, so the
        // clamp must not cost us the last complete row.
        val highWaters = (0 until 300).map { (it / 2).toLong() + 1L }
        val p = healthConnectInsertChunked(records(300), highWaters) { c ->
            if (c.size < 200) throw IllegalStateException("second chunk fails")
        }
        assertFalse(p.allCompleted)
        assertEquals(100L, p.lastCompletedHighWater)
    }

    // ── Phase 3: netting is gated on workouts actually being written ──

    private fun prefs(workouts: Boolean) = HealthConnectPrefs(workouts = workouts)

    @Test
    fun nettingStaysOffWhileNoWorkoutExporterExists() {
        // Phase 3 ships before Phase 4: subtracting workout energy/distance that nothing writes
        // back would silently under-report every day containing a workout.
        assertFalse(HealthConnectExporter.WORKOUTS_EXPORTED)
        assertFalse(
            HealthConnectExporter.shouldNetWorkouts(prefs(workouts = true), HealthConnectPermissions.all),
        )
    }

    @Test
    fun nettingWouldRequireBothTheToggleAndTheExercisePermission() {
        // Pins the intent for Phase 4, which flips WORKOUTS_EXPORTED: the toggle alone is not
        // enough, because it can be on while WRITE_EXERCISE is denied (partial grants are
        // first-class), and then no workout record is written to net against.
        val granted = HealthConnectPermissions.all
        val withoutExercise = granted - HealthConnectPermissions.exercise.first()
        assertFalse(HealthConnectExporter.shouldNetWorkouts(prefs(workouts = false), granted))
        assertFalse(HealthConnectExporter.shouldNetWorkouts(prefs(workouts = true), withoutExercise))
    }
}

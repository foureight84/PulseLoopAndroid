package com.pulseloop.service

import org.junit.Assert.*
import org.junit.Test

class ApneaScreenTest {
    private val STEP_MS = 30_000L

    @Test
    fun `flat SpO2 series yields zero desaturations`() {
        val flat = (0 until 200).map { i -> (i * STEP_MS) to 97 }
        assertEquals(0, detectDesaturations(flat, 3).size)
        assertEquals(0, detectDesaturations(flat, 4).size)
    }

    @Test
    fun `sawtooth 98 to 93 to 98 repeated yields expected desaturation count`() {
        // 12 samples/cycle at 30s spacing (6-minute period): plateau at 98, dip to 93,
        // recover to 98, plateau again — cycles are far enough apart (6 min > the 2 min
        // baseline lookback) that each one is scored independently.
        val cycle = listOf(98, 98, 98, 98, 95, 93, 95, 98, 98, 98, 98, 98)
        val numCycles = 4
        val spo2 = (0 until numCycles * cycle.size).map { i ->
            (i * STEP_MS) to cycle[i % cycle.size]
        }
        val nadirs3 = detectDesaturations(spo2, 3)
        val nadirs4 = detectDesaturations(spo2, 4)
        assertEquals(numCycles, nadirs3.size)
        assertEquals(numCycles, nadirs4.size)
        // Nadir lands on the sample that reads 93.
        val expectedFirstNadir = 5 * STEP_MS
        assertEquals(expectedFirstNadir, nadirs3.first())
    }

    @Test
    fun `HR spikes at each recovery produce matching coincident count`() {
        val cycle = listOf(98, 98, 98, 98, 95, 93, 95, 98, 98, 98, 98, 98)
        val numCycles = 3
        val spo2 = (0 until numCycles * cycle.size).map { i -> (i * STEP_MS) to cycle[i % cycle.size] }
        val nadirs = detectDesaturations(spo2, 3)
        assertEquals(numCycles, nadirs.size)

        // Baseline HR of 60 throughout, plus a +10 bpm spike 20s after each nadir
        // (within the +/-60s CVHR window).
        val totalMs = numCycles * cycle.size * STEP_MS
        val baseHr = (0..(totalMs / 10_000L)).map { i -> (i * 10_000L) to 60 }.toMutableList()
        val spikes = nadirs.map { nadir -> (nadir + 20_000L) to 70 }
        val hr = (baseHr + spikes)

        assertEquals(numCycles, countCoincidentHrSpikes(nadirs, hr))
    }

    @Test
    fun `sparse or missing data yields insufficient or sparse quality`() {
        // Fewer than 10 samples spanning under 2h → insufficient.
        val tiny = (0 until 5).map { i -> (i * 60_000L) to 97 }
        val tinyResult = buildApneaScreenResult(tiny, emptyList(), 0L, 30 * 60_000L)
        assertEquals("insufficient", tinyResult.dataQuality)

        // Enough samples and span, but spaced ~10 min apart (spot checks, not a stream) → sparse.
        val spanMs = 8 * 3_600_000L
        val gapMs = 10 * 60_000L
        val spotChecks = (0 until (spanMs / gapMs).toInt()).map { i -> (i * gapMs) to 97 }
        val sparseResult = buildApneaScreenResult(spotChecks, emptyList(), 0L, spanMs)
        assertTrue(sparseResult.dataQuality == "sparse" || sparseResult.dataQuality == "insufficient")
        assertNotEquals("good", sparseResult.dataQuality)
    }
}

package com.pulseloop.service

import com.pulseloop.service.EstimatedSpo2.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #103: hourly SpO₂ that a YCBT ring computes from heart rate is labelled as estimated. */
class EstimatedSpo2Test {

    private val hour = 3_600_000L
    private val start = 1_757_000_000_000L

    /** The reporter's mapping, read off 304 `05 18` records: HR 48 → 88, 57 → 94, 67–92 → 98, 105 → 96. */
    private fun ringSpo2(hr: Int): Int = when {
        hr <= 48 -> 88
        hr <= 57 -> 94
        hr <= 92 -> 98
        else -> 96
    }

    private val heartRates = listOf(48, 57, 67, 75, 82, 92, 105, 70)

    /** [hours] hourly composite records, each logged ~34 s past the hour (the end of the ring's HR run). */
    private fun hourlyTimes(hours: Int) = (0 until hours).map { start + it * hour + 34_000L }

    private data class Ring(val spo2: List<Sample>, val respiratory: Set<Long>, val hr: List<Sample>)

    private fun ring(hours: Int, spo2For: (index: Int, hr: Int) -> Int = { _, hr -> ringSpo2(hr) }): Ring {
        val times = hourlyTimes(hours)
        val hrs = times.indices.map { heartRates[it % heartRates.size] }
        return Ring(
            spo2 = times.mapIndexed { i, t -> Sample(t, spo2For(i, hrs[i]).toDouble()) },
            respiratory = times.toSet(),
            // `05 15` logs the same hourly HR run; give it a few seconds' skew to prove pairing is
            // by proximity, not by identical timestamps.
            hr = times.mapIndexed { i, t -> Sample(t - 5_000L, hrs[i].toDouble()) },
        )
    }

    private fun estimated(r: Ring, spo2: List<Sample> = r.spo2, hr: List<Sample> = r.hr): Set<Long> =
        EstimatedSpo2.estimated(spo2, r.respiratory, spo2, r.respiratory, hr)

    @Test
    fun `hourly SpO2 that is a fixed function of heart rate is labelled estimated`() {
        val r = ring(hours = 48)
        assertEquals(r.spo2.map { it.timestampMs }.toSet(), estimated(r))
    }

    @Test
    fun `a spot reading is never labelled, even beside an hourly HR row`() {
        val r = ring(hours = 48)
        // Spot SpO₂ logged in `05 18` with HR and respiration 0: an SpO₂ row and no respiratory row.
        // Put it 90 s after an hourly HR row, inside the pairing window, to show the respiratory
        // sibling — not HR proximity — is what decides hourly vs spot.
        val spot = Sample(start + 10 * hour + 124_000L, 99.0)
        val labelled = estimated(r, spo2 = r.spo2 + spot)

        assertFalse(spot.timestampMs in labelled)
        assertEquals(r.spo2.size, labelled.size)
    }

    @Test
    fun `a real oximeter disagreeing with itself at one heart rate labels nothing`() {
        // Same hourly structure, but SpO₂ varies independently of heart rate as a sensor would.
        val r = ring(hours = 48) { i, _ -> listOf(97, 98, 99, 96, 98)[i % 5] }
        assertTrue(estimated(r).isEmpty())
    }

    @Test
    fun `a single exception to the mapping is enough to withhold the label`() {
        val r = ring(hours = 48) { i, hr -> if (i == 40) ringSpo2(hr) - 1 else ringSpo2(hr) }
        assertTrue(estimated(r).isEmpty())
    }

    @Test
    fun `too little history earns no verdict`() {
        val r = ring(hours = EstimatedSpo2.MIN_PAIRS - 1)
        assertTrue(estimated(r).isEmpty())
    }

    @Test
    fun `a constant reading says nothing about where it came from`() {
        val r = ring(hours = 48) { _, _ -> 98 }
        assertTrue(estimated(r).isEmpty())
    }

    @Test
    fun `heart rates that never repeat cannot test the mapping`() {
        val times = hourlyTimes(30)
        val hrs = times.indices.map { 45 + it * 2 }          // 30 distinct heart rates
        val spo2 = times.mapIndexed { i, t -> Sample(t, ringSpo2(hrs[i]).toDouble()) }
        val hr = times.mapIndexed { i, t -> Sample(t, hrs[i].toDouble()) }
        val respiratory = times.toSet()

        assertFalse(EstimatedSpo2.derivesFromHeartRate(EstimatedSpo2.pairs(spo2, hr)))
        assertTrue(EstimatedSpo2.estimated(spo2, respiratory, spo2, respiratory, hr).isEmpty())
    }

    @Test
    fun `without heart rate history there is nothing to pair and nothing is labelled`() {
        val r = ring(hours = 48)
        assertTrue(estimated(r, hr = emptyList()).isEmpty())
    }

    @Test
    fun `pairing takes the nearest heart rate and ignores one outside the window`() {
        val at = start + 34_000L
        val hourly = listOf(Sample(at, 94.0))
        val hr = listOf(
            Sample(at - 90_000L, 70.0),
            Sample(at + 20_000L, 57.0),
            Sample(at + EstimatedSpo2.PAIR_WINDOW_MS + 60_000L, 48.0),
        )
        assertEquals(listOf(57 to 94), EstimatedSpo2.pairs(hourly, hr))
        assertTrue(EstimatedSpo2.pairs(hourly, listOf(Sample(at + EstimatedSpo2.PAIR_WINDOW_MS + 1, 57.0))).isEmpty())
    }

    @Test
    fun `only rows with a respiratory sibling at the identical timestamp are hourly`() {
        val a = Sample(start, 98.0)
        val b = Sample(start + hour, 99.0)
        assertEquals(listOf(a), EstimatedSpo2.hourly(listOf(a, b), setOf(start, start + hour + 1)))
    }

    @Test
    fun `labels rows outside the evidence window once the ring is judged`() {
        val r = ring(hours = 48)
        // A month-view chart: an older hourly row the 7-day evidence doesn't include still gets the
        // label, because the verdict is about the ring, and a spot row beside it still doesn't.
        val oldHourly = Sample(start - 10 * 86_400_000L, 94.0)
        val oldSpot = Sample(start - 10 * 86_400_000L + 600_000L, 99.0)
        val labelled = EstimatedSpo2.estimated(
            spo2 = listOf(oldHourly, oldSpot),
            respiratoryTimestamps = setOf(oldHourly.timestampMs),
            evidence = r.spo2,
            evidenceRespiratoryTimestamps = r.respiratory,
            evidenceHeartRate = r.hr,
        )
        assertEquals(setOf(oldHourly.timestampMs), labelled)
    }
}

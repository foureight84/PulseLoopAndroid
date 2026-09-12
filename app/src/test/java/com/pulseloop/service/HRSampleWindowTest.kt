package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the spot-HR sampling gate ported from iOS #66: warm-up echo discard, the contact-gap
 * abort, and the median/majority consistency rule. Pure logic — time is driven by an injected clock.
 */
class HRSampleWindowTest {

    /** A window whose clock is a mutable field, so tests advance time deterministically. */
    private class Fixture {
        var now = 0L
        val window = HRSampleWindow { now }
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `samples inside the warm-up are dropped`() {
        val f = Fixture()
        f.window.begin()
        f.advance(1_000); f.window.collect(70)
        f.advance(2_000); f.window.collect(72)   // still < 5s
        assertFalse("no real reading yet during warm-up", f.window.receivedReading)
        assertNull(f.window.stableValue)
    }

    @Test
    fun `samples after the warm-up are collected`() {
        val f = Fixture()
        f.window.begin()
        f.advance(6_000)                          // past the 5s warm-up
        f.window.collect(70)
        assertTrue(f.window.receivedReading)
    }

    @Test
    fun `stableValue is null below the minimum sample count`() {
        val f = Fixture()
        f.window.begin()
        f.advance(6_000)
        repeat(5) { f.window.collect(70); f.advance(200) }   // only 5 samples
        assertNull(f.window.stableValue)
    }

    @Test
    fun `stableValue is null when the window scatters`() {
        val f = Fixture()
        f.window.begin()
        f.advance(6_000)
        // 8 wildly-scattered readings: no >=60% majority sits within +-8 of the median.
        listOf(40, 60, 80, 100, 120, 140, 160, 180).forEach { f.window.collect(it); f.advance(200) }
        assertNull(f.window.stableValue)
    }

    @Test
    fun `stableValue returns the cluster median when a majority agrees`() {
        val f = Fixture()
        f.window.begin()
        f.advance(6_000)
        // 7 tight readings + 1 outlier: the cluster is {70,71,72,72,73,74}, median 72.
        listOf(70, 71, 72, 72, 73, 74, 140).forEach { f.window.collect(it); f.advance(200) }
        assertEquals(72, f.window.stableValue)
    }

    @Test
    fun `contactLost is false during the warm-up and true after a gap`() {
        val f = Fixture()
        f.window.begin()
        f.advance(2_000)
        assertFalse("no samples collected yet during warm-up", f.window.contactLost())
        f.advance(4_000); f.window.collect(70)   // first real sample at t=6s
        f.advance(1_000)
        assertFalse("within the contact gap", f.window.contactLost())
        f.advance(8_500)
        assertTrue("more than 8s since the last sample", f.window.contactLost())
    }

    @Test
    fun `collect reports whether the sample was kept`() {
        val f = Fixture()
        assertFalse("no measurement running", f.window.collect(70))
        f.window.begin()
        f.advance(1_000)
        assertFalse("inside the warm-up echo", f.window.collect(70))
        f.advance(5_000)
        assertTrue("past the warm-up", f.window.collect(70))
    }

    /**
     * Issue #59, replayed from the reporter's instrumented capture of an `Ale-Hop2211` YCBT ring.
     * The PPG spends ~26 s on a pre-converged plateau (47 47 47, 46 46 46) before stepping to the
     * real rate (84 … 81), and the ring ends the measurement itself at ~35 s. The plateau is both
     * the majority of the window and the most consistent thing in it, so the old whole-window
     * median reported 46 — a number that was never this user's heart rate.
     */
    @Test
    fun `the pre-converged plateau does not outvote the converged tail`() {
        val f = Fixture()
        f.window.begin()
        val capture = listOf(
            14_100L to 47, 15_100L to 47, 16_100L to 47,
            22_100L to 46, 23_100L to 46, 24_100L to 46,
            26_100L to 84, 27_100L to 84, 28_100L to 84,
            32_100L to 82, 33_100L to 81, 34_100L to 81, 35_100L to 81,
        )
        for ((at, bpm) in capture) {
            f.now = at
            assertTrue("t+${at}ms is past the warm-up", f.window.collect(bpm))
        }
        val settled = f.window.stableValue
        assertNotNull("the capture is a successful measurement", settled)
        assertTrue("settles on the converged rate, not the 46 bpm plateau: got $settled", settled!! >= 80)
    }

    /**
     * The counterpart to the test above: the bursty cadence that produced that capture — three
     * samples about a second apart, then 4-6 s of silence — must not read as a slipped ring.
     * At the old 3 s gap this aborted the leg at t+19 s, before the sensor had converged at all.
     */
    @Test
    fun `a bursty ring is not mistaken for lost contact`() {
        val f = Fixture()
        f.window.begin()
        f.now = 14_100; f.window.collect(47)
        f.now = 15_100; f.window.collect(47)
        f.now = 16_100; f.window.collect(47)
        f.now = 21_000
        assertFalse("still inside the ring's 4-6s burst gap", f.window.contactLost())
        f.now = 22_100; f.window.collect(46)
        assertFalse(f.window.contactLost())
    }

    // MARK: - The ring's own choice (issue #59, RC-3 read-back)

    /**
     * The three spot measurements the reporter captured with no stop command and then read back
     * out of the ring's own memory before letting any app near it. The ring stored the **last**
     * streamed sample all three times, and the run is still climbing when the ring stops — so this
     * is a discriminating test, unlike SpO2 where the tail and the last sample coincide.
     *
     * The elided middle of capture 2 is written out as the groups the report named; what matters
     * to the rule is the shape of the tail, which is quoted verbatim there.
     */
    private val readBackCaptures = listOf(
        Triple(
            "capture 1 (08:14:28)",
            listOf(47, 47, 47, 46, 46, 45, 45, 45, 46, 46, 47, 55, 65, 65),
            65,
        ),
        Triple(
            "capture 2 (08:18:18)",
            listOf(47, 47, 47, 44, 44, 44, 49, 49, 49, 51, 51, 53, 53, 54, 55, 57, 58, 58),
            58,
        ),
        Triple(
            "capture 3 (08:21:44)",
            listOf(47, 47, 47, 46, 46, 46, 47, 47, 55, 66, 72, 72),
            72,
        ),
    )

    /** Replays a whole run past the warm-up at the ring's ~1 s burst cadence. */
    private fun Fixture.replay(stream: List<Int>) {
        window.begin()
        now = 14_000
        for (bpm in stream) {
            now += 1_000
            window.collect(bpm)
        }
    }

    @Test
    fun `a ring that ends its own measurement settles on the value it will log`() {
        for ((name, stream, ringStored) in readBackCaptures) {
            val f = Fixture()
            f.replay(stream)
            assertEquals(
                "$name: the app must report what the ring stored",
                ringStored,
                f.window.settled(ringChoosesLastSample = true),
            )
        }
    }

    /**
     * The point of the previous test: a tail-weighted rule lands *below* the ring's answer on a
     * climbing run, which is how the app came to show 94 against the ring's 93. Kept as a test so
     * that "just use the tail everywhere" reads as a deliberate regression rather than a tidy-up.
     */
    @Test
    fun `the tail rule disagrees with the ring on a climbing run`() {
        for ((name, stream, ringStored) in readBackCaptures) {
            val f = Fixture()
            f.replay(stream)
            val tail = f.window.settled(ringChoosesLastSample = false)
            assertNotNull("$name: the tail rule still produces a reading", tail)
            assertTrue(
                "$name: tail $tail should sit below the ring's $ringStored",
                tail!! < ringStored,
            )
        }
    }

    /**
     * A ring with no completion signal keeps the tail rule, because nothing chose its last sample
     * — the leg simply ran out of window. Its steady stream settles where it always did.
     */
    @Test
    fun `a ring with no completion signal still gets the consistency gate`() {
        val f = Fixture()
        f.replay(listOf(30, 200, 60, 150, 40, 190, 55, 170))   // scattered: no majority agrees
        assertNull(f.window.settled(ringChoosesLastSample = false))
    }

    @Test
    fun `a trailing dropout frame cannot become the reading`() {
        val f = Fixture()
        f.replay(listOf(47, 55, 65, 72, 0))          // ring drops out on the last frame
        assertEquals(72, f.window.settled(ringChoosesLastSample = true))
    }

    @Test
    fun `a run with no plausible sample is a failed measurement`() {
        val f = Fixture()
        f.replay(listOf(0, 0, 255, 0))
        assertNull(f.window.settled(ringChoosesLastSample = true))
    }

    @Test
    fun `one plausible sample is enough when the ring chose it`() {
        val f = Fixture()
        f.replay(listOf(0, 88))                       // below stableValue's 6-sample floor
        assertEquals(88, f.window.settled(ringChoosesLastSample = true))
        assertNull("the tail rule still needs a run to judge", f.window.settled(ringChoosesLastSample = false))
    }

    @Test
    fun `begin resets a prior window`() {
        val f = Fixture()
        f.window.begin()
        f.advance(6_000); repeat(6) { f.window.collect(70); f.advance(200) }
        assertTrue(f.window.receivedReading)
        f.window.begin(f.now)
        assertFalse("begin clears samples", f.window.receivedReading)
        assertNull(f.window.stableValue)
    }
}

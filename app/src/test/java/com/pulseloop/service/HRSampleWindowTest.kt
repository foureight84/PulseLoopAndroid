package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse("within the 3s contact gap", f.window.contactLost())
        f.advance(3_500)
        assertTrue("more than 3s since the last sample", f.window.contactLost())
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

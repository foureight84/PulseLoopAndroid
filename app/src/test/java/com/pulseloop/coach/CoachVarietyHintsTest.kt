package com.pulseloop.coach.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from CoachVarietyHintsTests in CoachWS3Tests.swift (iOS #65).
 */
class CoachVarietyHintsTest {

    @Test
    fun testAngleIsDeterministicForSameSeed() {
        val a = CoachVarietyHints.angle("2026-07-08morning")
        val b = CoachVarietyHints.angle("2026-07-08morning")
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
        assertTrue(CoachVarietyHints.angles.contains(a))
    }

    @Test
    fun testDifferentSeedsRotateAngles() {
        val seen = mutableSetOf<String>()
        for (i in 0 until 40) {
            val angle = CoachVarietyHints.angle("seed-$i")
            assertTrue(CoachVarietyHints.angles.contains(angle))
            seen.add(angle)
        }
        assertTrue(seen.size > 1)
    }

    @Test
    fun testFnv1aIsStableAcrossCalls() {
        assertEquals(CoachVarietyHints.fnv1a("pulseloop"), CoachVarietyHints.fnv1a("pulseloop"))
        assertNotEquals(CoachVarietyHints.fnv1a("a"), CoachVarietyHints.fnv1a("b"))
    }
}

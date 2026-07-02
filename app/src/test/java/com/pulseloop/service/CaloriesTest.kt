package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaloriesTest {
    @Test
    fun `keytel is plausible at running HR`() {
        // 35yo male, 75kg, HR 140 → ~13 kcal/min (moderate run)
        val kcal = keytelKcalPerMinute(140, 75.0, 35, male = true)
        assertEquals(13.2, kcal, 0.5)
    }

    @Test
    fun `keytel increases with HR`() {
        val low = keytelKcalPerMinute(80, 75.0, 35, male = true)
        val high = keytelKcalPerMinute(150, 75.0, 35, male = true)
        assertTrue(high > low)
    }

    @Test
    fun `keytel never negative`() {
        assertTrue(keytelKcalPerMinute(40, 50.0, 20, male = false) >= 0.0)
    }
}

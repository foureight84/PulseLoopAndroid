package com.pulseloop.ui.viewmodels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentDayValuesTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun retainedCollectorSwitchesFromYesterdayAtMidnight() = runTest {
        val sunday = 1L
        val monday = 2L
        val currentDay = MutableStateFlow(sunday)
        val rows = mapOf(
            sunday to MutableStateFlow<Int?>(5_397),
            monday to MutableStateFlow<Int?>(null),
        )
        val displayedSteps = currentDayValues(currentDay, rows::getValue)
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)

        runCurrent()
        assertEquals(5_397, displayedSteps.value)

        currentDay.value = monday
        runCurrent()
        assertNull("yesterday's steps must disappear after rollover", displayedSteps.value)

        rows.getValue(sunday).value = 6_000
        runCurrent()
        assertNull("updates to yesterday must stay hidden", displayedSteps.value)

        rows.getValue(monday).value = 42
        runCurrent()
        assertEquals(42, displayedSteps.value)
    }
}

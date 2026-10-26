package com.pulseloop.data

import com.pulseloop.data.entity.ActivityDailyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Read-time step-source selection (PR #98, Path B). The phone never overwrites the ring's
 * `steps` column — it writes `phoneSteps` — and the display picks between them here. The rules
 * the whole feature rests on:
 *
 *  - Ring mode (`preferPhone = false`) always shows the ring's `steps`, never `phoneSteps`.
 *  - Phone mode (`preferPhone = true`) shows `phoneSteps` when it is non-null, else falls back
 *    to the ring's `steps` — a day the phone never backfilled still shows something.
 *  - `phoneSteps = 0` is a real state distinct from `null`: the phone was counting and counted
 *    zero. Only `null` means "the phone never wrote for this day".
 *  - `forDisplay` rewrites only `steps`; every other column (calories, distance, source) is
 *    left as the ring wrote it — that is what makes switching source reversible.
 *
 * Pure functions, no Room: these are the same helpers the ViewModels, widgets, and the
 * notification context builder call.
 */
class ActivityDailyDisplayTest {

    private fun row(
        steps: Int = 5_000,
        phoneSteps: Int? = null,
        calories: Double = 200.0,
        distanceMeters: Double = 4_000.0,
        source: String = "ring",
    ) = ActivityDailyEntity(
        date = 1_700_000_000_000L,
        steps = steps,
        phoneSteps = phoneSteps,
        calories = calories,
        distanceMeters = distanceMeters,
        source = source,
    )

    // ── displaySteps (scalar read) ──

    @Test
    fun ringModeShowsRingStepsEvenWhenPhoneHasAValue() {
        assertEquals(5_000, row(phoneSteps = 8_200).displaySteps(preferPhone = false))
    }

    @Test
    fun ringModeShowsRingStepsWhenPhoneHasNoValue() {
        assertEquals(5_000, row(phoneSteps = null).displaySteps(preferPhone = false))
    }

    @Test
    fun phoneModeShowsPhoneStepsWhenSet() {
        assertEquals(8_200, row(phoneSteps = 8_200).displaySteps(preferPhone = true))
    }

    @Test
    fun phoneModeFallsBackToRingStepsWhenPhoneIsNull() {
        assertEquals(5_000, row(phoneSteps = null).displaySteps(preferPhone = true))
    }

    @Test
    fun phoneModeShowsZeroWhenPhoneWalkedZero() {
        // The midnight-gap case: the phone was counting and counted 0, which must display as
        // 0 and not fall through to the ring's stale value.
        assertEquals(0, row(steps = 5_000, phoneSteps = 0).displaySteps(preferPhone = true))
    }

    // ── forDisplay (whole-row projection) ──

    @Test
    fun forDisplayInRingModeReturnsRowUnchanged() {
        val original = row(phoneSteps = 8_200)
        assertSame(original, original.forDisplay(preferPhone = false))
    }

    @Test
    fun forDisplayInPhoneModeWithNoPhoneValueReturnsRowUnchanged() {
        val original = row(phoneSteps = null)
        assertSame(original, original.forDisplay(preferPhone = true))
    }

    @Test
    fun forDisplayInPhoneModeProjectsPhoneStepsOntoSteps() {
        val original = row(steps = 5_000, phoneSteps = 8_200)
        val shown = original.forDisplay(preferPhone = true)
        assertEquals(8_200, shown.steps)
    }

    @Test
    fun forDisplayLeavesEveryOtherColumnUntouched() {
        // Path B's whole point: only `steps` is projected. If calories/distance ever changed
        // under source switching, the ring's real values would be lost.
        val original = row(
            steps = 5_000, phoneSteps = 8_200,
            calories = 210.5, distanceMeters = 4_321.0, source = "ring",
        )
        val shown = original.forDisplay(preferPhone = true)
        assertEquals(210.5, shown.calories, 0.0)
        assertEquals(4_321.0, shown.distanceMeters, 0.0)
        assertEquals("ring", shown.source)
        assertEquals(8_200, shown.phoneSteps) // phoneSteps itself is unchanged
        assertEquals(1_700_000_000_000L, shown.date)
    }

    @Test
    fun forDisplayZeroPhoneStepsProjectsZeroNotFallback() {
        // Same as the midnight-gap scalar case, but through the whole-row path the ViewModels
        // actually use.
        val original = row(steps = 5_000, phoneSteps = 0)
        val shown = original.forDisplay(preferPhone = true)
        assertEquals(0, shown.steps)
    }
}

package com.pulseloop.service

import com.pulseloop.data.entity.ActivityDailyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone step write's column-only contract (PR #98, Path B). Phone and ring data live in
 * separate columns, and the phone write touches exactly one of them — that is what makes the
 * source switch reversible and what stops the phone from clobbering the ring's calories and
 * distance. [phoneStepRow] is the pure decision this rests on (same pattern as
 * `EventPersistenceIdentityTest`'s helpers); the Room call around it is one line.
 *
 * The four rules asserted here:
 *  - only `phoneSteps`/`syncedAt`/`updatedAt` change on an existing row
 *  - a brand-new row is created with `source = "phone"`
 *  - writing the same value twice is a no-op
 *  - `phoneSteps = 0` is a real value distinct from null
 */
class EventPersistencePhoneWriteTest {

    private val dayStart = 1_700_000_000_000L

    private fun ringRow(
        steps: Int = 5_000,
        phoneSteps: Int? = null,
        calories: Double = 210.5,
        distanceMeters: Double = 4_321.0,
        source: String = "ring",
    ) = ActivityDailyEntity(
        date = dayStart,
        steps = steps,
        phoneSteps = phoneSteps,
        calories = calories,
        distanceMeters = distanceMeters,
        source = source,
    )

    @Test
    fun phoneWriteOnlyTouchesPhoneStepsSyncedAtAndUpdatedAt() {
        val existing = ringRow()
        val written = phoneStepRow(existing, dayStart, steps = 8_200, now = 999L)!!

        assertEquals(8_200, written.phoneSteps)
        assertEquals(999L, written.syncedAt)
        assertEquals(999L, written.updatedAt)

        // Ring's own columns, untouched:
        assertEquals(5_000, written.steps)
        assertEquals(210.5, written.calories, 0.0)
        assertEquals(4_321.0, written.distanceMeters, 0.0)
        assertEquals("ring", written.source)
    }

    @Test
    fun firstPhoneWriteOnADayCreatesRowWithPhoneSource() {
        val written = phoneStepRow(null, dayStart = dayStart, steps = 8_200, now = 999L)!!

        assertEquals(dayStart, written.date)
        assertEquals(8_200, written.phoneSteps)
        assertEquals("phone", written.source)
        assertEquals(0, written.steps)             // default — nothing has written the ring column yet
        assertEquals(0.0, written.calories, 0.0)   // default
        assertEquals(0.0, written.distanceMeters, 0.0)
    }

    @Test
    fun sameValueTwiceIsANoOp() {
        // `null` returned means "don't write" — the caller skips the upsert entirely.
        assertNull(phoneStepRow(ringRow(phoneSteps = 8_200), dayStart, steps = 8_200))
        assertNull(phoneStepRow(ringRow(phoneSteps = 0), dayStart, steps = 0))
    }

    @Test
    fun differentValueWrites() {
        // 8_200 → 8_500: today's phone count moved; a write is required.
        assertNotNull(phoneStepRow(ringRow(phoneSteps = 8_200), dayStart, steps = 8_500))
    }

    @Test
    fun nullToZeroWrites() {
        // The midnight-gap case: phone has begun counting and counted zero. This is the write
        // that makes "today's phone value is 0" a real state — skipping it would leave the day's
        // phoneSteps null and the display showing the ring's stale total.
        val written = phoneStepRow(ringRow(phoneSteps = null), dayStart, steps = 0)!!
        assertEquals(0, written.phoneSteps)
    }

    @Test
    fun zeroToNullWouldWrite() {
        // Not a state we expect in production, but the helper's contract must not confuse
        // "explicit zero" with "no value": only equal-non-null values are the no-op case.
        assertNotNull(phoneStepRow(ringRow(phoneSteps = 0), dayStart, steps = 1))
    }
}

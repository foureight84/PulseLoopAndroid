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
 * The four [phoneStepRow] rules asserted here:
 *  - only `phoneSteps`/`syncedAt` change on an existing row — `updatedAt` is preserved so the
 *    exporter does not re-select the row for unchanged ring data (review item 10)
 *  - a brand-new row is created with `source = "phone"`
 *  - writing the same value twice is a no-op
 *  - `phoneSteps = 0` is a real value distinct from null
 *
 * Plus [clearPhoneStepsOnRow]'s contract (review item 4):
 *  - the returned row differs only in `phoneSteps = null` — no `updatedAt` bump (see item 10)
 *  - the call is a no-op when `phoneSteps` is already null, or when the row is absent
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
    fun phoneWriteTouchesPhoneStepsAndSyncedAtOnly() {
        val existing = ringRow().copy(updatedAt = 500L)
        val written = phoneStepRow(existing, dayStart, steps = 8_200, now = 999L)!!

        assertEquals(8_200, written.phoneSteps)
        assertEquals(999L, written.syncedAt)       // syncedAt bumps
        assertEquals(500L, written.updatedAt)      // preserved — review item 10

        // Ring's own columns, untouched:
        assertEquals(5_000, written.steps)
        assertEquals(210.5, written.calories, 0.0)
        assertEquals(4_321.0, written.distanceMeters, 0.0)
        assertEquals("ring", written.source)
    }

    @Test
    fun changedPhoneValueAlsoPreservesUpdatedAt() {
        // The realistic case: an existing day has phoneSteps = 8_200, the user walks 300
        // more, refresh() publishes 8_500. phoneSteps and syncedAt move; updatedAt must not,
        // or ActivityExporter re-selects the row and re-exports unchanged ring steps/
        // calories/distance (review item 10).
        val existing = ringRow(phoneSteps = 8_200).copy(updatedAt = 500L)
        val written = phoneStepRow(existing, dayStart, steps = 8_500, now = 999L)!!

        assertEquals(8_500, written.phoneSteps)
        assertEquals(999L, written.syncedAt)
        assertEquals(500L, written.updatedAt)
    }

    @Test
    fun firstPhoneWriteOnADayCreatesRowWithPhoneSource() {
        val written = phoneStepRow(null, dayStart = dayStart, steps = 8_200, now = 999L)!!

        assertEquals(dayStart, written.date)
        assertEquals(8_200, written.phoneSteps)
        assertEquals("phone", written.source)
        assertEquals(999L, written.syncedAt)
        assertEquals(999L, written.updatedAt)      // new rows stamp updatedAt so the exporter picks them up
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

    // ── clearPhoneStepsOnRow (review item 4) ─────────────────────────────────

    @Test
    fun clearPhoneStepsNullsTheColumnWithoutTouchingAnythingElse() {
        val existing = ringRow(phoneSteps = 8_200, source = "ring").copy(
            activeMinutes = 42, syncedAt = 500L, updatedAt = 500L,
        )
        val cleared = clearPhoneStepsOnRow(existing)!!

        assertNull(cleared.phoneSteps)
        // Ring data columns untouched:
        assertEquals(5_000, cleared.steps)
        assertEquals(210.5, cleared.calories, 0.0)
        assertEquals(4_321.0, cleared.distanceMeters, 0.0)
        assertEquals("ring", cleared.source)
        assertEquals(42, cleared.activeMinutes)
        // Metadata untouched too: syncedAt is preserved, and updatedAt deliberately is
        // — ActivityExporter selects on it, and a metadata-only change would re-export
        // unchanged ring data (item 10's rule).
        assertEquals(500L, cleared.syncedAt)
        assertEquals(500L, cleared.updatedAt)
    }

    @Test
    fun clearPhoneStepsIsANoOpWhenAlreadyNull() {
        // Every failed foreground fires PhoneStepSourceUnavailable. Only the first one
        // (while phoneSteps was non-null) should write; the rest must be no-ops.
        assertNull(clearPhoneStepsOnRow(ringRow(phoneSteps = null)))
    }

    @Test
    fun clearPhoneStepsIsANoOpWhenRowIsAbsent() {
        // No row for today: nothing to clear. The ring hasn't written and the phone hasn't
        // either, so display falls back to the null-row default (0 / no data) already.
        assertNull(clearPhoneStepsOnRow(null))
    }
}

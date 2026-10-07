package com.pulseloop.service

import com.pulseloop.ring.PulseEvent
import com.pulseloop.settings.StepSourcePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

/**
 * Regression guards for the phone step source. Deeper coverage of the Health Connect reads
 * (`PhoneStepManager`) would need a mocked HC client and is out of scope here — these tests
 * protect the invariants that are easy to break silently:
 *
 *  - the two source constants stay distinct (a typo collapsing them would silently break the
 *    preference switch)
 *  - the event carries only the step count (adding a hardcoded distance/calorie back would
 *    bypass DailyCalorieEstimator without any compile error)
 *
 * The read-time source selection itself (`ActivityDailyEntity.displaySteps` / `forDisplay`) is
 * covered in `app/src/test/java/com/pulseloop/data/ActivityDailyDisplayTest.kt`. The phone
 * write's column-only behaviour is covered (Room-backed) in
 * `app/src/test/java/com/pulseloop/service/EventPersistenceSubscriberPhoneWriteTest.kt`.
 * The EXCLUDED_SOURCES assertion that used to live here was superseded by
 * `HealthConnectTypeMappingsTest.excludedSourcesCoversDemoAndMock` in commit 41beee0 — `"phone"`
 * was dropped from the set, so the old assertion was simply wrong.
 */
class PhoneStepSourceTest {

    @Test
    fun sourceConstantsAreDistinct() {
        assertNotEquals(StepSourcePrefs.SOURCE_RING, StepSourcePrefs.SOURCE_PHONE)
    }

    @Test
    fun phoneStepsUpdateCarriesOnlySteps() {
        // Constructing the event with only (timestamp, steps) is itself the regression guard:
        // if anyone reintroduces `distanceMeters` or `calories` as required constructor
        // parameters, this file stops compiling.
        val event = PulseEvent.PhoneStepsUpdate(
            timestamp = Instant.ofEpochMilli(0L),
            steps = 123,
        )
        assertEquals(123, event.steps)
    }
}
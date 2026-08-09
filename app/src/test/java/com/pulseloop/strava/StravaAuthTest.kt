package com.pulseloop.strava

import com.pulseloop.data.entity.ActivitySessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic Strava tests: scope parsing, duplicate parsing, sport mapping, activity naming.
 * The HTTP and EncryptedSharedPreferences paths need instrumentation and aren't covered here.
 */
class StravaAuthTest {

    // ── Granted scope ───────────────────────────────────────────────────────────

    @Test
    fun `granted scope must contain activity write`() {
        // Strava lets the user untick "Upload your activities" on the consent screen; without this
        // check the connect appears to succeed and every later upload 401s with no explanation.
        assertTrue(StravaAuth.grantedScopeIncludesWrite("read,activity:write"))
        assertTrue(StravaAuth.grantedScopeIncludesWrite("activity:write, read"))
        assertFalse(StravaAuth.grantedScopeIncludesWrite("read"))
        assertFalse(StravaAuth.grantedScopeIncludesWrite("read,activity:read_all"))
        assertFalse(StravaAuth.grantedScopeIncludesWrite(null))
        assertFalse(StravaAuth.grantedScopeIncludesWrite(""))
    }

    @Test
    fun `scope matching is exact, not a substring`() {
        assertFalse(StravaAuth.grantedScopeIncludesWrite("activity:write_all"))
    }

    // ── Duplicate detection ─────────────────────────────────────────────────────

    @Test
    fun `duplicate errors yield the existing activity id`() {
        assertEquals(123456L, StravaUploader.parseDuplicate("workout.tcx is a duplicate of activity 123456"))
        assertEquals(987L, StravaUploader.parseDuplicate("duplicate of 987"))
        assertEquals(42L, StravaUploader.parseDuplicate("DUPLICATE OF ACTIVITY 42"))
        assertNull(StravaUploader.parseDuplicate("some other error"))
    }

    // ── Sport mapping ───────────────────────────────────────────────────────────

    @Test
    fun `sport type never returns null`() {
        assertEquals("Run", StravaSportMapping.toStravaType("run"))
        assertEquals("Ride", StravaSportMapping.toStravaType("cycle"))
        assertEquals("WeightTraining", StravaSportMapping.toStravaType("gym"))
        assertEquals("Workout", StravaSportMapping.toStravaType("something-new"))
    }

    @Test
    fun `only run and cycle map losslessly through TCX`() {
        assertFalse(StravaSportMapping.needsSportTypeFix("run"))
        assertFalse(StravaSportMapping.needsSportTypeFix("cycle"))
        assertTrue(StravaSportMapping.needsSportTypeFix("walk"))
        assertTrue(StravaSportMapping.needsSportTypeFix("yoga"))
    }

    // ── Activity naming ─────────────────────────────────────────────────────────

    @Test
    fun `activity name is Strava-style time of day plus sport`() {
        fun nameAt(hour: Int, type: String = "run"): String {
            val cal = java.util.Calendar.getInstance().apply {
                set(2024, 7, 7, hour, 30, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            return StravaUploader.activityName(
                ActivitySessionEntity(id = "s", type = type, startedAt = cal.timeInMillis)
            )
        }

        assertEquals("Morning Run", nameAt(7))
        assertEquals("Lunch Run", nameAt(12))
        assertEquals("Afternoon Run", nameAt(15))
        assertEquals("Evening Run", nameAt(19))
        assertEquals("Night Run", nameAt(23))
        assertEquals("Morning Ride", nameAt(9, "cycle"))
    }

    @Test
    fun `activity name is unbranded`() {
        val cal = java.util.Calendar.getInstance().apply { set(2024, 7, 7, 9, 0, 0) }
        val name = StravaUploader.activityName(
            ActivitySessionEntity(id = "s", type = "run", startedAt = cal.timeInMillis)
        )
        assertFalse(name.contains("PulseLoop", ignoreCase = true))
    }
}

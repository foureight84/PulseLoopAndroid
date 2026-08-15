package com.pulseloop.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 (docs/health-connect-integration.md): the permission sets must be derived from the
 * record classes, not hardcoded, and must contain exactly the ten Phase 1–4 WRITE permissions
 * — no READ_*, no Phase 5 types. Asserting the official string literals pins the
 * record-class → permission mapping so a silent library change fails loudly here, not in the
 * field.
 */
class HealthConnectPermissionsTest {

    @Test
    fun allContainsExactlyTheTenPhasePermissions() {
        assertEquals(10, HealthConnectPermissions.all.size)
    }

    @Test
    fun everyPermissionIsAHealthWritePermission() {
        for (p in HealthConnectPermissions.all) {
            assertTrue("expected a health WRITE permission, got $p", p.startsWith("android.permission.health.WRITE_"))
        }
    }

    @Test
    fun noReadPermissionAnyWhere() {
        for (p in HealthConnectPermissions.all) {
            assertFalse("found a read permission: $p", p.startsWith("android.permission.health.READ"))
        }
    }

    @Test
    fun derivationMatchesTheOfficialPermissionStrings() {
        // Official data-types reference; the route is singular on purpose (READ_EXERCISE_ROUTES
        // is the plural one — the docs call out the asymmetry).
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_HEART_RATE"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_OXYGEN_SATURATION"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_HEART_RATE_VARIABILITY"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_BODY_TEMPERATURE"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_SLEEP"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_STEPS"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_ACTIVE_CALORIES_BURNED"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_DISTANCE"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_EXERCISE"))
        assertTrue(HealthConnectPermissions.all.contains("android.permission.health.WRITE_EXERCISE_ROUTE"))
    }

    @Test
    fun noPhase5PermissionLeakedIn() {
        val phase5 = setOf(
            "android.permission.health.WRITE_BLOOD_PRESSURE",
            "android.permission.health.WRITE_BLOOD_GLUCOSE",
            "android.permission.health.WRITE_RESPIRATORY_RATE",
            "android.permission.health.WRITE_VO2_MAX",
            "android.permission.health.WRITE_RESTING_HEART_RATE",
        )
        assertTrue(HealthConnectPermissions.all.none { it in phase5 })
    }

    @Test
    fun everyRowMapsToItsPermissions() {
        assertEquals(1, HealthConnectPermissions.permissionsForRow(HealthConnectPermissions.DataTypeRow.HEART_RATE).size)
        assertEquals(3, HealthConnectPermissions.permissionsForRow(HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY).size)
        assertEquals(2, HealthConnectPermissions.permissionsForRow(HealthConnectPermissions.DataTypeRow.WORKOUTS).size)
        // Nutrition's permission lands with Phase 5 — the row is stored, not granted, for now.
        assertTrue(HealthConnectPermissions.permissionsForRow(HealthConnectPermissions.DataTypeRow.NUTRITION).isEmpty())
    }
}

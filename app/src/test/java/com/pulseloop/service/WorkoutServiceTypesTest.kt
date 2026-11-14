package com.pulseloop.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #139: a GPS workout's foreground service must carry the location type. */
class WorkoutServiceTypesTest {
    private val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test
    fun gpsWorkoutOnAndroid14GetsHealthAndLocation() {
        assertEquals(health or location, WorkoutForegroundService.serviceTypes(34, useGps = true, hasLocationPermission = true))
    }

    @Test
    fun indoorWorkoutOnAndroid14IsHealthOnly() {
        assertEquals(health, WorkoutForegroundService.serviceTypes(34, useGps = false, hasLocationPermission = true))
    }

    @Test
    fun withoutLocationPermissionNoLocationTypeIsClaimed() {
        assertEquals(health, WorkoutForegroundService.serviceTypes(35, useGps = true, hasLocationPermission = false))
    }

    @Test
    fun gpsWorkoutOnAndroid10To13GetsLocation() {
        assertEquals(location, WorkoutForegroundService.serviceTypes(29, useGps = true, hasLocationPermission = true))
        assertNull(WorkoutForegroundService.serviceTypes(33, useGps = false, hasLocationPermission = true))
    }

    @Test
    fun preAndroid10UsesPlainStart() {
        assertNull(WorkoutForegroundService.serviceTypes(28, useGps = true, hasLocationPermission = true))
    }
}

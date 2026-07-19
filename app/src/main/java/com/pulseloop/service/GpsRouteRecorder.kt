package com.pulseloop.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityGpsPointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Ported from [GpsRouteRecorder] in GpsRouteRecorder.swift.
 * Captures a GPS route during an outdoor workout using FusedLocationProviderClient. Every fix
 * (accepted or rejected) is persisted as an [ActivityGpsPointEntity] row — [RouteDistanceEngine]
 * derives distance/splits from those rows later, so the live tile, finish summary, and splits
 * table never disagree (iOS #57b).
 */
class GpsRouteRecorder(private val context: Context, private val db: PulseLoopDatabase) {
    data class RouteState(
        val isTracking: Boolean = false,
        val pointCount: Int = 0,
        val totalDistance: Double = 0.0,
        val isPermissionDenied: Boolean = false,
    )

    private val _state = MutableStateFlow(RouteState()); val state: StateFlow<RouteState> = _state.asStateFlow()

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var sessionId: String? = null
    private var profile: ActivityTrackingProfile = ActivityTrackingProfile.default
    private var lastAccepted: Location? = null
    private var accumulator = RouteDistanceEngine.Accumulator(profile, splitMeters = 1000.0)

    // Filtering thresholds shared by all activity types; per-type speed/movement thresholds
    // come from `ActivityTrackingProfile`.
    private val maxHorizontalAccuracy = 30.0f  // metres; reject poorer/invalid fixes
    private val maxFixAgeMs = 5_000L           // milliseconds; reject stale cached fixes
    private val maxCourseDelta = 25.0          // degrees; turn detection keeps cornering detail

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (fix in result.locations) {
                ingest(fix)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(sessionId: String, activityType: String = "run") {
        this.sessionId = sessionId
        this.profile = ActivityTrackingProfile.profile(activityType)
        this.accumulator = RouteDistanceEngine.Accumulator(profile, splitMeters = 1000.0)
        lastAccepted = null

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1000)
            .setMaxUpdateDelayMillis(5000)
            .setMinUpdateDistanceMeters(profile.distanceFilterMeters.toFloat())
            .build()

        client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        _state.value = _state.value.copy(isTracking = true, pointCount = 0, totalDistance = 0.0)
    }

    fun stop() {
        client.removeLocationUpdates(locationCallback)
        lastAccepted = null
        sessionId = null
        _state.value = _state.value.copy(isTracking = false)
    }

    private fun ingest(fix: Location) {
        val sid = sessionId ?: return
        val reason = rejectionReason(fix)
        val accepted = reason == null
        if (accepted) {
            lastAccepted = fix
            accumulator.add(fix.latitude, fix.longitude, fix.time)
            _state.value = _state.value.copy(
                pointCount = _state.value.pointCount + 1,
                totalDistance = accumulator.distanceMeters,
            )
        }
        scope.launch {
            db.activityGpsPointDao().insert(
                ActivityGpsPointEntity(
                    sessionId = sid,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    altitude = if (fix.hasAltitude()) fix.altitude else null,
                    horizontalAccuracy = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
                    speed = if (fix.hasSpeed()) fix.speed.toDouble() else null,
                    course = if (fix.hasBearing()) fix.bearing.toDouble() else null,
                    timestamp = fix.time,
                    accepted = accepted,
                    rejectionReason = reason,
                )
            )
        }
    }

    /** Returns a rejection reason for a fix, or null if it should be accepted. Rejected fixes are
     *  still persisted (with the reason) so the post-workout quality report can show coverage. */
    private fun rejectionReason(location: Location): String? {
        if (!location.hasAccuracy() || location.accuracy > maxHorizontalAccuracy) return "accuracy"
        if (abs(System.currentTimeMillis() - location.time) > maxFixAgeMs) return "stale"
        val last = lastAccepted ?: return null
        val distance = location.distanceTo(last).toDouble()
        val dt = (location.time - last.time) / 1000.0
        if (dt > 0 && distance / dt > profile.maxSpeedMps) return "speed"
        if (distance >= profile.minMoveMeters) return null
        if (last.hasBearing() && location.hasBearing()) {
            var courseDelta = location.bearing - last.bearing
            while (courseDelta > 180) courseDelta -= 360
            while (courseDelta < -180) courseDelta += 360
            if (abs(courseDelta) > maxCourseDelta) return null
        }
        if (dt >= profile.minIntervalSeconds) return null
        return "stationary"
    }
}

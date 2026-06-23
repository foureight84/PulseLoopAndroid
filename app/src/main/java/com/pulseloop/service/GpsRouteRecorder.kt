package com.pulseloop.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ported from [GpsRouteRecorder] in GpsRouteRecorder.swift.
 * Captures a GPS route during an outdoor workout using FusedLocationProviderClient.
 */
class GpsRouteRecorder(private val context: Context) {
    data class RouteState(
        val isTracking: Boolean = false,
        val pointCount: Int = 0,
        val totalDistance: Double = 0.0,
        val isPermissionDenied: Boolean = false,
    )

    private val _state = MutableStateFlow(RouteState()); val state: StateFlow<RouteState> = _state.asStateFlow()

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var sessionId: String? = null
    private var activityType = "run"
    private var points = mutableListOf<Pair<Double, Double>>()
    private var lastAccepted: Location? = null

    private val maxHorizontalAccuracy = 30.0f  // metres
    private val maxFixAge = 5_000L             // milliseconds

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
        this.activityType = activityType
        points.clear()
        lastAccepted = null

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(1000)
            .setMaxUpdateDelayMillis(5000)
            .build()

        client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        _state.value = _state.value.copy(isTracking = true, pointCount = 0, totalDistance = 0.0)
    }

    fun stop() {
        client.removeLocationUpdates(locationCallback)
        _state.value = _state.value.copy(isTracking = false)
    }

    private fun ingest(fix: Location) {
        if (!fix.hasAccuracy() || fix.accuracy > maxHorizontalAccuracy) return
        val ageMs = System.currentTimeMillis() - fix.time
        if (ageMs > maxFixAge) return

        points.add(fix.latitude to fix.longitude)
        val distance = DistanceUtils.totalDistance(points)
        _state.value = _state.value.copy(
            pointCount = points.size,
            totalDistance = distance,
        )
    }
}

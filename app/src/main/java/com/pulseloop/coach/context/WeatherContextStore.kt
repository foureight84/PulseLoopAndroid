package com.pulseloop.coach.context

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.math.abs

/**
 * Ported from the private `Cached`/`CachedWeather`/`Place` types in
 * CoachEnvironmentContextService.swift. Persists the last resolved coordinate/place/weather
 * so background callers (the notification worker) can degrade to a cached reading instead
 * of ever triggering a location fetch. Non-sensitive (no raw coordinates leave the device via
 * the packet this feeds — only city/region), so a plain prefs file is used, matching
 * MetricPrefsStore's idiom.
 */
class WeatherContextStore internal constructor(private val prefsStore: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Place(val city: String? = null, val region: String? = null)

    @Serializable
    data class Cached(
        val lat: Double,
        val lon: Double,
        val place: Place? = null,
        val weather: EnvironmentContext? = null,
        val weatherAt: Long,
        val locationAt: Long,
    ) {
        fun isWeatherFresh(nowMs: Long, ttlMs: Long): Boolean =
            weather != null && (nowMs - weatherAt) < ttlMs

        /** Within ~1km — close enough to reuse the same city geocode. */
        fun isNear(otherLat: Double, otherLon: Double): Boolean =
            abs(lat - otherLat) < 0.01 && abs(lon - otherLon) < 0.01

        /** Rebuild the packet context from cache, dropping weather older than the stale window. */
        fun environment(nowMs: Long, staleWeatherWindowMs: Long, overridePlace: Place? = null): EnvironmentContext? {
            val resolvedPlace = overridePlace ?: place
            val weatherUsable = weather != null && (nowMs - weatherAt) <= staleWeatherWindowMs
            if (weather != null && weatherUsable) {
                return weather.copy(city = resolvedPlace?.city, region = resolvedPlace?.region, asOf = Instant.ofEpochMilli(weatherAt).toString())
            }
            if (resolvedPlace?.city == null && resolvedPlace?.region == null) return null
            return EnvironmentContext(city = resolvedPlace.city, region = resolvedPlace.region, asOf = Instant.ofEpochMilli(nowMs).toString())
        }
    }

    var cached: Cached?
        get() {
            val raw = prefsStore.getString(KEY, null) ?: return null
            return try { json.decodeFromString(Cached.serializer(), raw) } catch (_: Exception) { null }
        }
        set(value) {
            if (value == null) { prefsStore.edit().remove(KEY).apply(); return }
            prefsStore.edit().putString(KEY, json.encodeToString(Cached.serializer(), value)).apply()
        }

    companion object {
        private const val KEY = "pulseloop.coach.environment.v1"
        private const val FILE = "pulseloop_prefs"

        @Volatile
        private var instance: WeatherContextStore? = null

        fun get(context: Context): WeatherContextStore =
            instance ?: synchronized(this) {
                instance ?: WeatherContextStore(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}

package com.pulseloop.coach.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume

/**
 * Opt-in city-level location + weather for the coach — adapted from iOS #65d's
 * `CoachEnvironmentContextService`. Android has no WeatherKit equivalent, so this uses
 * Open-Meteo (free, no API key) for weather and `FusedLocationProviderClient` + the
 * on-device [Geocoder] for location, in place of CoreLocation/CLGeocoder/WeatherKit.
 *
 * Privacy + resilience mirrors iOS:
 * - Coarse one-shot location, fetched only when the app is foregrounded AND authorized;
 *   background (WorkManager) callers reuse the persisted cache only — never trigger a fix.
 * - Raw coordinates never enter the packet — only city/region from [Geocoder].
 * - Weather fetch failures degrade to city-only or a stale cached reading (≤3h). [snapshot]
 *   never throws.
 */
class WeatherContextService(private val context: Context) {
    private val apiKeyStore = ApiKeyStore(context)
    private val store = WeatherContextStore.get(context)
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val http = OkHttpClient()

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Build the environment context, or null when the toggle is off. Never throws: weather
     *  failures degrade to city-only or a stale cached reading. */
    suspend fun snapshot(nowMs: Long = System.currentTimeMillis()): EnvironmentContext? {
        if (!apiKeyStore.enableEnvironmentContext) return null

        val cache = store.cached
        val coordinate = resolveCoordinate(nowMs, cache)
            ?: return cache?.environment(nowMs, STALE_WEATHER_WINDOW_MS)

        val place = resolvePlace(coordinate, nowMs, cache)

        if (cache != null && cache.isWeatherFresh(nowMs, WEATHER_TTL_MS)) {
            return cache.environment(nowMs, STALE_WEATHER_WINDOW_MS, place)
        }
        val failedAt = lastWeatherFailureAtMs
        if (failedAt != null && (nowMs - failedAt) < WEATHER_RETRY_COOLDOWN_MS) {
            return degraded(place, cache, nowMs)
        }

        return try {
            val env = fetchWeather(coordinate, place, nowMs)
            store.cached = WeatherContextStore.Cached(
                lat = coordinate.first, lon = coordinate.second,
                place = place, weather = env, weatherAt = nowMs, locationAt = nowMs,
            )
            lastWeatherFailureAtMs = null
            env
        } catch (e: Exception) {
            lastWeatherFailureAtMs = nowMs
            degraded(place, cache, nowMs)
        }
    }

    private fun degraded(place: WeatherContextStore.Place?, cache: WeatherContextStore.Cached?, nowMs: Long): EnvironmentContext? {
        cache?.environment(nowMs, STALE_WEATHER_WINDOW_MS, place)?.let { return it }
        if (place?.city == null && place?.region == null) return null
        return EnvironmentContext(city = place.city, region = place.region, asOf = Instant.ofEpochMilli(nowMs).toString())
    }

    // ── Location ─────────────────────────────────────────────────────────

    private suspend fun resolveCoordinate(nowMs: Long, cache: WeatherContextStore.Cached?): Pair<Double, Double>? {
        if (hasLocationPermission() && isAppForeground()) {
            requestOneShotLocation()?.let { return it.first to it.second }
        }
        if (cache != null && (nowMs - cache.locationAt) < LOCATION_TTL_MS) {
            return cache.lat to cache.lon
        }
        return null
    }

    /** Only fetch a fresh fix when the app is active — mirrors iOS checking
     *  `UIApplication.shared.applicationState != .background` before calling CoreLocation. */
    private suspend fun isAppForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private suspend fun requestOneShotLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        return try {
            suspendCancellableCoroutine { cont ->
                val cts = CancellationTokenSource()
                cont.invokeOnCancellation { cts.cancel() }
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { loc -> cont.resume(loc?.let { it.latitude to it.longitude }) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } catch (e: SecurityException) {
            null
        }
    }

    private suspend fun resolvePlace(
        coordinate: Pair<Double, Double>, nowMs: Long, cache: WeatherContextStore.Cached?,
    ): WeatherContextStore.Place? {
        if (cache != null && (nowMs - cache.locationAt) < LOCATION_TTL_MS &&
            cache.isNear(coordinate.first, coordinate.second) && cache.place != null
        ) {
            return cache.place
        }
        return withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext cache?.place
                @Suppress("DEPRECATION")
                val results = Geocoder(context).getFromLocation(coordinate.first, coordinate.second, 1)
                val placemark = results?.firstOrNull() ?: return@withContext cache?.place
                WeatherContextStore.Place(city = placemark.locality, region = placemark.adminArea)
            } catch (e: Exception) {
                cache?.place
            }
        }
    }

    // ── Weather ──────────────────────────────────────────────────────────

    private suspend fun fetchWeather(
        coordinate: Pair<Double, Double>, place: WeatherContextStore.Place?, nowMs: Long,
    ): EnvironmentContext = withContext(Dispatchers.IO) {
        // Privacy (first-pass finding #1): the precise fix (~100m at BALANCED_POWER_ACCURACY) must
        // never leave the device bound for a third party — iOS requests at 3km accuracy and sends
        // only to first-party WeatherKit. The full-precision fix is still used on-device for the
        // Geocoder city lookup and the cache-nearness check; only the wire value is coarsened.
        val lat = roundForWire(coordinate.first)
        val lon = roundForWire(coordinate.second)
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=1"
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Open-Meteo HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Open-Meteo: empty body")
            val root = Json.parseToJsonElement(body).jsonObject
            val current = root["current"]?.jsonObject
            val daily = root["daily"]?.jsonObject
            val tempC = current?.get("temperature_2m")?.jsonPrimitive?.double
            val code = current?.get("weather_code")?.jsonPrimitive?.int
            val highC = daily?.get("temperature_2m_max")?.jsonArray?.firstOrNull()?.jsonPrimitive?.double
            val lowC = daily?.get("temperature_2m_min")?.jsonArray?.firstOrNull()?.jsonPrimitive?.double
            val precip = daily?.get("precipitation_probability_max")?.jsonArray?.firstOrNull()?.jsonPrimitive?.int
            val sunrise = daily?.get("sunrise")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            val sunset = daily?.get("sunset")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            EnvironmentContext(
                city = place?.city, region = place?.region,
                tempC = tempC, condition = code?.let { wmoCondition(it) },
                highC = highC, lowC = lowC, precipitationChancePct = precip,
                sunrise = sunrise, sunset = sunset, asOf = Instant.ofEpochMilli(nowMs).toString(),
            )
        }
    }

    /** WMO weather-interpretation codes — the fixed vocabulary Open-Meteo's `weather_code`
     *  field uses (https://open-meteo.com/en/docs, "WMO Weather interpretation codes"). */
    private fun wmoCondition(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    companion object {
        /** Weather is re-fetched after this; the city/coordinates are kept much longer. */
        private const val WEATHER_TTL_MS = 30 * 60_000L
        /** City + coordinate cache lifetime — people don't move cities minute-to-minute. */
        private const val LOCATION_TTL_MS = 6 * 3600_000L
        /** Oldest weather still surfaced when a fresh fetch fails. */
        private const val STALE_WEATHER_WINDOW_MS = 3 * 3600_000L
        /** After a failed fetch, don't retry weather for this long — avoids hammering Open-Meteo
         *  on a persistent failure (e.g. no network) on every coach turn / notification build. */
        private const val WEATHER_RETRY_COOLDOWN_MS = 5 * 60_000L

        /** Process-wide like iOS's `CoachEnvironmentContextService.shared`: the notification
         *  worker builds a fresh service per call, so an instance field here would reset the
         *  retry cooldown on every background check-in and defeat it (first-pass finding #4). */
        @Volatile
        private var lastWeatherFailureAtMs: Long? = null

        /** The wire granularity for coordinates sent to Open-Meteo: 2 decimals ≈ 1.1km, matching
         *  [WeatherContextStore.isNear]'s 0.01 cache-hit threshold. */
        internal fun roundForWire(degrees: Double): Double = kotlin.math.round(degrees * 100.0) / 100.0
    }
}

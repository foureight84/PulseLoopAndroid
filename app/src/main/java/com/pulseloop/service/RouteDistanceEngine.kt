package com.pulseloop.service

import com.pulseloop.data.entity.ActivityGpsPointEntity
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ported from [ActivityTrackingProfile] in RouteDistanceEngine.swift (iOS #57b).
 * Per-activity GPS tuning: how the recorder filters fixes and how the distance engine judges
 * segments. One table keyed by activity type, so the live recorder, distance totals, and splits
 * all agree on what counts as movement.
 */
data class ActivityTrackingProfile(
    /** Fastest plausible sustained speed; segments implying more are GPS jumps and are dropped. */
    val maxSpeedMps: Double,
    /** Minimum displacement for a fix to count as movement (stationary-jitter gate). */
    val minMoveMeters: Double,
    /** Minimum distance the OS should move before delivering a new fix. */
    val distanceFilterMeters: Double,
    /** Minimum seconds between accepted stationary-ish fixes (keeps slow walks sampled). */
    val minIntervalSeconds: Double,
    /** Segments spanning more time than this (pause teleports, long signal loss) contribute
     *  neither distance nor moving time — bridging them with a straight line is what made
     *  mileage wrong. */
    val gapSeconds: Double,
) {
    companion object {
        val default = ActivityTrackingProfile(
            maxSpeedMps = 8.0, minMoveMeters = 4.0, distanceFilterMeters = 5.0,
            minIntervalSeconds = 6.0, gapSeconds = 30.0,
        )

        private val table = mapOf(
            "walk" to ActivityTrackingProfile(maxSpeedMps = 5.0, minMoveMeters = 3.0, distanceFilterMeters = 4.0, minIntervalSeconds = 6.0, gapSeconds = 30.0),
            "run" to ActivityTrackingProfile(maxSpeedMps = 8.0, minMoveMeters = 4.0, distanceFilterMeters = 5.0, minIntervalSeconds = 5.0, gapSeconds = 30.0),
            "hike" to ActivityTrackingProfile(maxSpeedMps = 6.0, minMoveMeters = 3.0, distanceFilterMeters = 4.0, minIntervalSeconds = 6.0, gapSeconds = 45.0),
            "cycle" to ActivityTrackingProfile(maxSpeedMps = 25.0, minMoveMeters = 8.0, distanceFilterMeters = 8.0, minIntervalSeconds = 8.0, gapSeconds = 30.0),
        )

        fun profile(activityType: String): ActivityTrackingProfile = table[activityType] ?: default
    }
}

/**
 * Ported from [RouteDistanceEngine] in RouteDistanceEngine.swift (iOS #57b).
 * Distance and splits for a recorded route. All callers (live tile, finish summary, splits
 * table) go through here so a workout never shows two different mileages. Points are sorted and
 * gap/speed-filtered identically everywhere.
 */
object RouteDistanceEngine {

    /** Completed splits plus the partial split in progress, in *moving* time (gap segments
     *  contribute neither distance nor seconds, so a mid-run pause doesn't wreck the pace). */
    data class Splits(
        val completedSeconds: List<Double> = emptyList(),
        val partialMeters: Double = 0.0,
        val partialSeconds: Double = 0.0,
    )

    fun distanceMeters(points: List<ActivityGpsPointEntity>, profile: ActivityTrackingProfile): Double {
        val sorted = accepted(points)
        if (sorted.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until sorted.size) {
            total += segmentMeters(sorted[i - 1], sorted[i], profile) ?: 0.0
        }
        return total
    }

    fun splits(points: List<ActivityGpsPointEntity>, splitMeters: Double, profile: ActivityTrackingProfile): Splits {
        if (splitMeters <= 0) return Splits()
        val sorted = accepted(points)
        if (sorted.size < 2) return Splits()
        val completed = mutableListOf<Double>()
        var partialMeters = 0.0
        var partialSeconds = 0.0
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val meters = segmentMeters(a, b, profile) ?: continue
            partialMeters += meters
            partialSeconds += (b.timestamp - a.timestamp) / 1000.0
            while (partialMeters >= splitMeters) {
                // The crossing segment's full time credits the completed split (matches the
                // previous mark-walking behaviour); the leftover distance rolls forward.
                completed.add(partialSeconds)
                partialMeters -= splitMeters
                partialSeconds = 0.0
            }
        }
        return Splits(completed, partialMeters, partialSeconds)
    }

    /** Seconds per completed split — drop-in shape for the summary splits table. */
    fun splitSeconds(points: List<ActivityGpsPointEntity>, splitMeters: Double, profile: ActivityTrackingProfile): List<Double> =
        splits(points, splitMeters, profile).completedSeconds

    /** Incremental counterpart of [distanceMeters] + [splits] for the live screen: O(1) per GPS
     *  fix instead of re-walking the whole route every update. Applies the exact same gap/speed
     *  segment rules, so live totals always match the batch recompute at finish. Points must
     *  arrive in timestamp order; a stale out-of-order fix is skipped. */
    class Accumulator(private val profile: ActivityTrackingProfile, private val splitMeters: Double) {
        var distanceMeters: Double = 0.0
            private set
        var splits: Splits = Splits()
            private set
        private var last: Triple<Double, Double, Long>? = null

        fun add(latitude: Double, longitude: Double, timestampMs: Long) {
            val previous = last
            if (previous == null) {
                last = Triple(latitude, longitude, timestampMs)
                return
            }
            if (timestampMs < previous.third) return // out-of-order fix — skip
            val meters = segmentMeters(
                lat1 = previous.first, lon1 = previous.second, t1 = previous.third,
                lat2 = latitude, lon2 = longitude, t2 = timestampMs,
                profile = profile,
            )
            if (meters != null) {
                distanceMeters += meters
                if (splitMeters > 0) {
                    var partialMeters = splits.partialMeters + meters
                    var partialSeconds = splits.partialSeconds + (timestampMs - previous.third) / 1000.0
                    val completed = splits.completedSeconds.toMutableList()
                    while (partialMeters >= splitMeters) {
                        // Same crossing rule as the batch `splits`: the crossing segment's full
                        // time credits the completed split; leftover distance rolls forward.
                        completed.add(partialSeconds)
                        partialMeters -= splitMeters
                        partialSeconds = 0.0
                    }
                    splits = Splits(completed, partialMeters, partialSeconds)
                }
            }
            last = Triple(latitude, longitude, timestampMs)
        }

        /** Replay a batch of already-persisted points (recovery seeding). Accepted points only. */
        fun seed(points: List<ActivityGpsPointEntity>) {
            for (p in points.filter { it.accepted }.sortedBy { it.timestamp }) {
                add(p.latitude, p.longitude, p.timestamp)
            }
        }
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = lat1 * Math.PI / 180
        val p2 = lat2 * Math.PI / 180
        val dPhi = (lat2 - lat1) * Math.PI / 180
        val dLambda = (lon2 - lon1) * Math.PI / 180
        val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(p1) * cos(p2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    // MARK: - Internals

    private fun accepted(points: List<ActivityGpsPointEntity>): List<ActivityGpsPointEntity> =
        points.filter { it.accepted }.sortedBy { it.timestamp }

    /** Segment length, or null when the segment must not count: spans longer than the gap
     *  threshold (pause/signal loss — dt is huge so implied speed looks plausible even for a
     *  cross-town teleport) or implying an impossible speed (GPS jump). */
    private fun segmentMeters(a: ActivityGpsPointEntity, b: ActivityGpsPointEntity, profile: ActivityTrackingProfile): Double? =
        segmentMeters(
            lat1 = a.latitude, lon1 = a.longitude, t1 = a.timestamp,
            lat2 = b.latitude, lon2 = b.longitude, t2 = b.timestamp,
            profile = profile,
        )

    /** Raw-value form shared with [Accumulator] so live accumulation and batch recompute can
     *  never disagree on what counts as a valid segment. */
    private fun segmentMeters(
        lat1: Double, lon1: Double, t1: Long,
        lat2: Double, lon2: Double, t2: Long,
        profile: ActivityTrackingProfile,
    ): Double? {
        val dt = (t2 - t1) / 1000.0
        if (dt < 0 || dt > profile.gapSeconds) return null
        val meters = haversineMeters(lat1, lon1, lat2, lon2)
        if (dt > 0 && meters / dt > profile.maxSpeedMps) return null
        return meters
    }
}

package com.pulseloop.strava

import com.pulseloop.data.entity.ActivityEventEntity
import com.pulseloop.data.entity.ActivityGpsPointEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.data.entity.MeasurementEntity

/**
 * Pure TCX generator for Strava uploads, ported from StravaTCXBuilder.swift (iOS #100). No DB
 * access — the caller passes pre-fetched rows so the builder stays deterministic and testable.
 */
object StravaTCXBuilder {

    /**
     * Ring HR is sparse (spot reads), so a GPS trackpoint reuses the most recent HR sample
     * at-or-before its own time — but only within this window; older samples are omitted rather
     * than smeared across a gap.
     */
    const val HR_STALENESS_WINDOW_MS = 60_000L

    /** A paused span, half-open. */
    data class PauseInterval(val start: Long, val end: Long) {
        fun contains(ts: Long) = ts in start..end
    }

    /**
     * Builds the TCX document, or **null when there are no emittable trackpoints**. A TCX whose
     * `<Track>` is empty is schema-invalid and Strava rejects it; the caller is expected to fall
     * back to creating a manual activity so the workout still lands.
     */
    fun build(
        session: ActivitySessionEntity,
        gpsPoints: List<ActivityGpsPointEntity>,
        hrSamples: List<MeasurementEntity>,
        pauseIntervals: List<PauseInterval> = emptyList(),
    ): String? {
        val sortedGps = gpsPoints.filter { it.accepted }.sortedBy { it.timestamp }
        val sortedHR = hrSamples.filter { it.value > 0 }.sortedBy { it.timestamp }

        val trackpoints = when {
            sortedGps.size >= 2 -> gpsTrackpoints(sortedGps, sortedHR, pauseIntervals)
            sortedHR.isNotEmpty() -> indoorTrackpoints(sortedHR, pauseIntervals)
            else -> emptyList()
        }
        if (trackpoints.isEmpty()) return null

        val startISO = iso8601(session.startedAt)
        val ended = session.endedAt ?: session.startedAt
        // Elapsed minus paused time — the previous version reported wall-clock, so a workout
        // paused for 20 minutes showed 20 extra minutes of moving time on Strava.
        val totalSeconds = maxOf(0.0, (ended - session.startedAt) / 1000.0 - session.totalPauseSeconds)

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""")
            appendLine("""  <Activities>""")
            appendLine("""    <Activity Sport="${sportName(session.type)}">""")
            // TCX types Activity/Id as xsd:dateTime — it is not an epoch counter.
            appendLine("""      <Id>$startISO</Id>""")
            appendLine("""      <Lap StartTime="$startISO">""")
            appendLine("""        <TotalTimeSeconds>${fmt1(totalSeconds)}</TotalTimeSeconds>""")
            appendLine("""        <DistanceMeters>${fmt1(session.distanceMeters ?: 0.0)}</DistanceMeters>""")
            appendLine("""        <Calories>${(session.calories ?: 0.0).toInt()}</Calories>""")
            session.avgHeartRate?.let {
                appendLine("""        <AverageHeartRateBpm><Value>${Math.round(it)}</Value></AverageHeartRateBpm>""")
            }
            session.maxHeartRate?.let {
                appendLine("""        <MaximumHeartRateBpm><Value>${Math.round(it)}</Value></MaximumHeartRateBpm>""")
            }
            appendLine("""        <Intensity>Active</Intensity>""")
            // Required by the ActivityLap schema; omitting it makes the document invalid.
            appendLine("""        <TriggerMethod>Manual</TriggerMethod>""")
            appendLine("""        <Track>""")
            trackpoints.forEach { appendLine(it) }
            appendLine("""        </Track>""")
            appendLine("""      </Lap>""")
            appendLine("""    </Activity>""")
            appendLine("""  </Activities>""")
            appendLine("""</TrainingCenterDatabase>""")
        }
    }

    /**
     * Pairs each `paused` event with the next `resumed`; an unpaired trailing `paused` closes at
     * [endedAt]. Kind strings match what the activity recorder writes.
     */
    fun pauseIntervals(events: List<ActivityEventEntity>, endedAt: Long): List<PauseInterval> {
        val intervals = mutableListOf<PauseInterval>()
        var openPause: Long? = null
        for (event in events.sortedBy { it.timestamp }) {
            when (event.kind) {
                "paused" -> if (openPause == null) openPause = event.timestamp
                "resumed" -> {
                    val start = openPause
                    if (start != null && event.timestamp > start) {
                        intervals.add(PauseInterval(start, event.timestamp))
                    }
                    openPause = null
                }
            }
        }
        openPause?.let { if (endedAt > it) intervals.add(PauseInterval(it, endedAt)) }
        return intervals
    }

    // ── Trackpoints ─────────────────────────────────────────────────────────────

    /** GPS mode: one trackpoint per fix, HR merged as a step function within the staleness window. */
    private fun gpsTrackpoints(
        gps: List<ActivityGpsPointEntity>,
        hr: List<MeasurementEntity>,
        pauses: List<PauseInterval>,
    ): List<String> {
        val lines = mutableListOf<String>()
        var hrIndex = 0
        for (point in gps) {
            while (hrIndex < hr.size && hr[hrIndex].timestamp <= point.timestamp) hrIndex++
            val current = if (hrIndex > 0) hr[hrIndex - 1] else null
            if (pauses.any { it.contains(point.timestamp) }) continue

            lines.add("""          <Trackpoint>""")
            lines.add("""            <Time>${iso8601(point.timestamp)}</Time>""")
            lines.add("""            <Position>""")
            lines.add("""              <LatitudeDegrees>${fmt6(point.latitude)}</LatitudeDegrees>""")
            lines.add("""              <LongitudeDegrees>${fmt6(point.longitude)}</LongitudeDegrees>""")
            lines.add("""            </Position>""")
            point.altitude?.let { lines.add("""            <AltitudeMeters>${fmt1(it)}</AltitudeMeters>""") }
            if (current != null && point.timestamp - current.timestamp <= HR_STALENESS_WINDOW_MS) {
                lines.add("""            <HeartRateBpm><Value>${Math.round(current.value)}</Value></HeartRateBpm>""")
            }
            lines.add("""          </Trackpoint>""")
        }
        return lines
    }

    /** Indoor mode (fewer than 2 GPS fixes): one trackpoint per HR sample, no Position. */
    private fun indoorTrackpoints(hr: List<MeasurementEntity>, pauses: List<PauseInterval>): List<String> {
        val lines = mutableListOf<String>()
        for (sample in hr) {
            if (pauses.any { it.contains(sample.timestamp) }) continue
            lines.add("""          <Trackpoint>""")
            lines.add("""            <Time>${iso8601(sample.timestamp)}</Time>""")
            lines.add("""            <HeartRateBpm><Value>${Math.round(sample.value)}</Value></HeartRateBpm>""")
            lines.add("""          </Trackpoint>""")
        }
        return lines
    }

    // TCX v2 only allows "Running" | "Biking" | "Other" for the Activity Sport attribute — which is
    // why most types need the follow-up sport_type update in StravaSportMapping.
    private fun sportName(type: String): String = when (type) {
        "run" -> "Running"
        "cycle" -> "Biking"
        else -> "Other"
    }

    private fun fmt1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt6(v: Double) = String.format(java.util.Locale.US, "%.6f", v)

    private fun iso8601(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(epochMs))
    }
}

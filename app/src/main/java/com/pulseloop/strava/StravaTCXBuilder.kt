package com.pulseloop.strava

import com.pulseloop.data.entity.ActivityGpsPointEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.data.entity.MeasurementEntity

object StravaTCXBuilder {

    fun build(session: ActivitySessionEntity, gpsPoints: List<ActivityGpsPointEntity>, hrSamples: List<MeasurementEntity>): String {
        val sport = sportName(session.type)
        val durationSec = ((session.endedAt ?: System.currentTimeMillis()) - session.startedAt) / 1000.0
        val dist = session.distanceMeters ?: 0.0
        val cal = session.calories ?: 0
        val avgHR = session.avgHeartRate?.toInt()
        val maxHR = session.maxHeartRate?.toInt()

        val acceptedGps = gpsPoints.filter { it.accepted }
        val hasGps = acceptedGps.size >= 2

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""")
            appendLine("""  <Activities>""")
            appendLine("""    <Activity Sport="$sport">""")
            appendLine("""      <Id>${session.startedAt / 1000}</Id>""")
            appendLine("""      <Lap StartTime="${iso8601(session.startedAt)}">""")
            appendLine("""        <TotalTimeSeconds>$durationSec</TotalTimeSeconds>""")
            appendLine("""        <DistanceMeters>$dist</DistanceMeters>""")
            appendLine("""        <Calories>$cal</Calories>""")
            if (avgHR != null) appendLine("""        <AverageHeartRateBpm><Value>$avgHR</Value></AverageHeartRateBpm>""")
            if (maxHR != null) appendLine("""        <MaximumHeartRateBpm><Value>$maxHR</Value></MaximumHeartRateBpm>""")
            appendLine("""        <Intensity>Active</Intensity>""")
            appendLine("""        <Track>""")

            if (hasGps) {
                val merged = mergeHR(acceptedGps, hrSamples)
                for (tp in merged) {
                    appendLine("""          <Trackpoint>""")
                    appendLine("""            <Time>${iso8601(tp.timestamp)}</Time>""")
                    appendLine("""            <Position>""")
                    appendLine("""              <LatitudeDegrees>${tp.latitude}</LatitudeDegrees>""")
                    appendLine("""              <LongitudeDegrees>${tp.longitude}</LongitudeDegrees>""")
                    appendLine("""            </Position>""")
                    if (tp.altitude != null) appendLine("""            <AltitudeMeters>${tp.altitude}</AltitudeMeters>""")
                    tp.hr?.let { hr ->
                        appendLine("""            <HeartRateBpm><Value>$hr</Value></HeartRateBpm>""")
                    }
                    appendLine("""          </Trackpoint>""")
                }
            } else {
                for (hr in hrSamples) {
                    appendLine("""          <Trackpoint>""")
                    appendLine("""            <Time>${iso8601(hr.timestamp)}</Time>""")
                    appendLine("""            <HeartRateBpm><Value>${hr.value.toInt()}</Value></HeartRateBpm>""")
                    appendLine("""          </Trackpoint>""")
                }
            }

            appendLine("""        </Track>""")
            appendLine("""      </Lap>""")
            appendLine("""    </Activity>""")
            appendLine("""  </Activities>""")
            appendLine("""</TrainingCenterDatabase>""")
        }
    }

    private data class TrackPoint(
        val timestamp: Long, val latitude: Double, val longitude: Double,
        val altitude: Double? = null, val hr: Int? = null,
    )

    /** Merge HR samples into GPS trackpoints: most recent HR at-or-before each GPS fix, max 60s staleness. */
    private fun mergeHR(gpsPoints: List<ActivityGpsPointEntity>, hrSamples: List<MeasurementEntity>): List<TrackPoint> {
        val sortedHR = hrSamples.sortedBy { it.timestamp }
        return gpsPoints.map { gp ->
            val hr = sortedHR.lastOrNull { it.timestamp <= gp.timestamp && gp.timestamp - it.timestamp < 60_000L }
            TrackPoint(gp.timestamp, gp.latitude, gp.longitude, gp.altitude, hr?.value?.toInt())
        }
    }

    private fun sportName(type: String): String = when (type) {
        "run" -> "Running"
        "cycle" -> "Biking"
        else -> "Other"
    }

    private fun iso8601(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(epochMs))
    }
}

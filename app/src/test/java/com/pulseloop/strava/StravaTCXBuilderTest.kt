package com.pulseloop.strava

import com.pulseloop.data.entity.ActivityEventEntity
import com.pulseloop.data.entity.ActivityGpsPointEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.data.entity.MeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors iOS `StravaTCXBuilderTests.swift`. Android shipped #100 with no test for any of this —
 * every assertion here failed against the first version.
 */
class StravaTCXBuilderTest {

    private val start = 1_723_000_000_000L   // 2024-08-07T03:06:40Z

    private fun session(
        type: String = "run",
        endedAt: Long? = start + 30 * 60_000L,
        pauseSeconds: Double = 0.0,
        distance: Double? = 5000.0,
        useGps: Boolean = true,
    ) = ActivitySessionEntity(
        id = "s1", type = type, statusRaw = "finished", startedAt = start, endedAt = endedAt,
        totalPauseSeconds = pauseSeconds, calories = 320.0, distanceMeters = distance,
        avgHeartRate = 141.4, maxHeartRate = 168.6, useGps = useGps,
    )

    private fun gps(offsetMs: Long, lat: Double = 51.5074, lon: Double = -0.1278, alt: Double? = 12.0) =
        ActivityGpsPointEntity(
            id = "g$offsetMs", sessionId = "s1", latitude = lat, longitude = lon, altitude = alt,
            timestamp = start + offsetMs, accepted = true,
        )

    private fun hr(offsetMs: Long, bpm: Double) = MeasurementEntity(
        id = "m$offsetMs", kindRaw = "HEART_RATE", value = bpm, unit = "bpm",
        timestamp = start + offsetMs, sourceRaw = "ring",
    )

    @Test
    fun `Id is an ISO-8601 dateTime, not epoch seconds`() {
        // TCX types Activity/Id as xsd:dateTime. Emitting `startedAt / 1000` produced
        // <Id>1723000000</Id>, which is not a valid dateTime.
        val tcx = StravaTCXBuilder.build(session(), listOf(gps(0), gps(60_000)), listOf(hr(0, 140.0)))!!
        assertTrue(tcx, tcx.contains("<Id>2024-08-07T03:06:40Z</Id>"))
        assertFalse(tcx.contains("<Id>1723000000</Id>"))
    }

    @Test
    fun `TriggerMethod is present`() {
        // Required by the TCX ActivityLap schema; omitting it makes the document invalid.
        val tcx = StravaTCXBuilder.build(session(), listOf(gps(0), gps(60_000)), emptyList())!!
        assertTrue(tcx.contains("<TriggerMethod>Manual</TriggerMethod>"))
    }

    @Test
    fun `TotalTimeSeconds excludes paused time`() {
        val tcx = StravaTCXBuilder.build(
            session(pauseSeconds = 300.0), listOf(gps(0), gps(60_000)), emptyList(),
        )!!
        // 30 min elapsed − 5 min paused = 1500 s of moving time.
        assertTrue(tcx, tcx.contains("<TotalTimeSeconds>1500.0</TotalTimeSeconds>"))
    }

    @Test
    fun `returns null when there is nothing to emit`() {
        // No accepted GPS and no HR — an empty <Track> is schema-invalid, so the caller must fall
        // back to a manual activity instead of uploading garbage.
        assertNull(StravaTCXBuilder.build(session(), emptyList(), emptyList()))
    }

    @Test
    fun `a single GPS fix is not a route and falls back to HR trackpoints`() {
        val tcx = StravaTCXBuilder.build(session(), listOf(gps(0)), listOf(hr(0, 132.0)))!!
        assertFalse("one fix is not a route", tcx.contains("<Position>"))
        assertTrue(tcx.contains("<HeartRateBpm><Value>132</Value></HeartRateBpm>"))
    }

    @Test
    fun `HR merges into the most recent fix within the staleness window`() {
        val tcx = StravaTCXBuilder.build(
            session(),
            listOf(gps(0), gps(30_000), gps(120_000)),
            listOf(hr(0, 120.0), hr(25_000, 150.0)),
        )!!
        assertTrue(tcx.contains("<Value>120</Value>"))
        assertTrue(tcx.contains("<Value>150</Value>"))
        // The 120 s fix is >60 s past the last sample, so it carries no HR.
        val lastPoint = tcx.substringAfterLast("<Trackpoint>")
        assertFalse("stale HR must not be smeared forward", lastPoint.contains("HeartRateBpm"))
    }

    @Test
    fun `trackpoints inside a pause are dropped`() {
        val pauses = listOf(StravaTCXBuilder.PauseInterval(start + 20_000, start + 40_000))
        val tcx = StravaTCXBuilder.build(
            session(), listOf(gps(0), gps(30_000), gps(60_000)), emptyList(), pauses,
        )!!
        assertEquals(2, Regex("<Trackpoint>").findAll(tcx).count())
    }

    @Test
    fun `pause intervals pair paused with the next resumed`() {
        val events = listOf(
            ActivityEventEntity(id = "1", sessionId = "s1", kind = "paused", timestamp = start + 10_000),
            ActivityEventEntity(id = "2", sessionId = "s1", kind = "resumed", timestamp = start + 40_000),
            ActivityEventEntity(id = "3", sessionId = "s1", kind = "paused", timestamp = start + 80_000),
        )
        val intervals = StravaTCXBuilder.pauseIntervals(events, endedAt = start + 100_000)

        assertEquals(2, intervals.size)
        assertEquals(start + 10_000, intervals[0].start)
        assertEquals(start + 40_000, intervals[0].end)
        // The unpaired trailing pause closes at endedAt.
        assertEquals(start + 100_000, intervals[1].end)
    }

    @Test
    fun `pause intervals pair through the gps_stopped and gps_started markers Android writes`() {
        // LiveWorkoutManager.pause/resume write `paused`+`gps_stopped` sharing one timestamp,
        // then `resumed`+`gps_started` sharing another (mirrors iOS PulseServices). The pairing
        // must react only to `paused`/`resumed` and treat the gps_* markers as transparent.
        val pausedAt = start + 15_000L
        val resumedAt = start + 45_000L
        val events = listOf(
            ActivityEventEntity(id = "1", sessionId = "s1", kind = "paused", timestamp = pausedAt),
            ActivityEventEntity(id = "2", sessionId = "s1", kind = "gps_stopped", timestamp = pausedAt),
            ActivityEventEntity(id = "3", sessionId = "s1", kind = "resumed", timestamp = resumedAt),
            ActivityEventEntity(id = "4", sessionId = "s1", kind = "gps_started", timestamp = resumedAt),
        )
        val intervals = StravaTCXBuilder.pauseIntervals(events, endedAt = start + 60_000L)

        assertEquals(1, intervals.size)
        assertEquals(pausedAt, intervals[0].start)
        assertEquals(resumedAt, intervals[0].end)
    }

    @Test
    fun `sport attribute uses the three TCX-legal values`() {
        fun sportOf(type: String) = StravaTCXBuilder
            .build(session(type = type), listOf(gps(0), gps(60_000)), emptyList())!!
            .substringAfter("<Activity Sport=\"").substringBefore("\"")

        assertEquals("Running", sportOf("run"))
        assertEquals("Biking", sportOf("cycle"))
        assertEquals("Other", sportOf("yoga"))
    }

    @Test
    fun `numbers are formatted locale-independently`() {
        val tcx = StravaTCXBuilder.build(session(), listOf(gps(0), gps(60_000)), emptyList())!!
        assertTrue(tcx.contains("<LatitudeDegrees>51.507400</LatitudeDegrees>"))
        assertTrue(tcx.contains("<DistanceMeters>5000.0</DistanceMeters>"))
    }

    @Test
    fun `heart rate summary values are rounded, not truncated`() {
        val tcx = StravaTCXBuilder.build(session(), listOf(gps(0), gps(60_000)), emptyList())!!
        assertTrue(tcx.contains("<AverageHeartRateBpm><Value>141</Value></AverageHeartRateBpm>"))
        assertTrue(tcx.contains("<MaximumHeartRateBpm><Value>169</Value></MaximumHeartRateBpm>"))
    }
}

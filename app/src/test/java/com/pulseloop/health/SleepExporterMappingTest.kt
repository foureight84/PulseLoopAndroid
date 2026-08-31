package com.pulseloop.health

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.health.exporters.buildSleepSessionRecord
import com.pulseloop.ring.SleepStage
import com.pulseloop.ring.SleepStageSegment
import com.pulseloop.service.buildTimestampedStageBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SleepExporterMappingTest {
    private val device = Device(type = Device.TYPE_RING, manufacturer = "test", model = "YCBT")

    @Test
    fun `corrected Room bounds and unknown gaps build a valid Health Connect sleep record`() {
        val start = Instant.parse("2026-07-06T22:30:00Z")
        val end = start.plusSeconds(6 * 60 * 60L)
        val day = Instant.parse("2026-07-07T00:00:00Z").toEpochMilli()
        val updatedAt = 2_000L
        val session = SleepSessionEntity(
            id = "room-session",
            date = day,
            startAt = start.toEpochMilli(),
            endAt = end.toEpochMilli(),
            totalMinutes = 360,
            updatedAt = updatedAt,
        )
        val blocks = buildTimestampedStageBlocks(
            session.id,
            session.startAt,
            session.endAt,
            listOf(
                SleepStageSegment(SleepStage.UNKNOWN, start, start.plusSeconds(30 * 60L)),
                SleepStageSegment(SleepStage.LIGHT, start.plusSeconds(30 * 60L), start.plusSeconds(90 * 60L)),
                SleepStageSegment(SleepStage.UNKNOWN, start.plusSeconds(90 * 60L), start.plusSeconds(4 * 60 * 60L)),
                SleepStageSegment(SleepStage.DEEP, start.plusSeconds(4 * 60 * 60L), start.plusSeconds(4 * 60 * 60L + 56 * 60L)),
                SleepStageSegment(SleepStage.UNKNOWN, start.plusSeconds(4 * 60 * 60L + 56 * 60L), end),
            ),
        )
        val recordId = HealthConnectTypeMappings.sleepSessionRecordIdV2(session.id)

        val record = buildSleepSessionRecord(session, blocks, recordId, device, ZoneId.of("UTC"))!!

        assertEquals(start, record.startTime)
        assertEquals(end, record.endTime)
        assertEquals(recordId, record.metadata.clientRecordId)
        assertEquals(updatedAt, record.metadata.clientRecordVersion)
        assertEquals(
            listOf(
                SleepSessionRecord.STAGE_TYPE_UNKNOWN,
                SleepSessionRecord.STAGE_TYPE_LIGHT,
                SleepSessionRecord.STAGE_TYPE_UNKNOWN,
                SleepSessionRecord.STAGE_TYPE_DEEP,
                SleepSessionRecord.STAGE_TYPE_UNKNOWN,
            ),
            record.stages.map { it.stage },
        )
        assertEquals(start, record.stages.first().startTime)
        assertEquals(end, record.stages.last().endTime)
        assertTrue(record.stages.zipWithNext().all { (a, b) -> a.endTime <= b.startTime })
    }

    @Test
    fun `sleep correction keeps client identity and advances updatedAt version`() {
        val day = Instant.parse("2026-07-07T00:00:00Z").toEpochMilli()
        val start = Instant.parse("2026-07-06T22:30:00Z")
        fun record(updatedAt: Long, minutes: Int): SleepSessionRecord {
            val end = start.plusSeconds(minutes * 60L)
            val session = SleepSessionEntity(
                id = "room-session",
                date = day,
                startAt = start.toEpochMilli(),
                endAt = end.toEpochMilli(),
                totalMinutes = minutes,
                updatedAt = updatedAt,
            )
            val blocks = buildTimestampedStageBlocks(
                session.id,
                session.startAt,
                session.endAt,
                listOf(SleepStageSegment(SleepStage.UNKNOWN, start, end)),
            )
            return buildSleepSessionRecord(
                session,
                blocks,
                HealthConnectTypeMappings.sleepSessionRecordIdV2(session.id),
                device,
                ZoneId.of("UTC"),
            )!!
        }

        val collapsed = record(updatedAt = 1_000L, minutes = 116)
        val corrected = record(updatedAt = 2_000L, minutes = 360)

        assertEquals(collapsed.metadata.clientRecordId, corrected.metadata.clientRecordId)
        assertEquals(1_000L, collapsed.metadata.clientRecordVersion)
        assertEquals(2_000L, corrected.metadata.clientRecordVersion)
        assertTrue(corrected.endTime > collapsed.endTime)
    }

    @Test
    fun `role change keeps every stable v2 identity unchanged`() {
        val day = Instant.parse("2026-07-07T00:00:00Z").toEpochMilli()
        val nightStart = Instant.parse("2026-07-06T22:30:00Z")
        val napStart = Instant.parse("2026-07-07T14:00:00Z")

        fun session(id: String, start: Instant, minutes: Int, updatedAt: Long) = SleepSessionEntity(
            id = id,
            date = day,
            startAt = start.toEpochMilli(),
            endAt = start.plusSeconds(minutes * 60L).toEpochMilli(),
            totalMinutes = minutes,
            updatedAt = updatedAt,
        )

        val collapsedNight = session("night-session", nightStart, 116, 1_000L)
        val nap = session("nap-session", napStart, 180, 1_000L)
        val correctedNight = session("night-session", nightStart, 360, 2_000L)

        val idsBefore = listOf(collapsedNight, nap).associate { session ->
            session.id to HealthConnectTypeMappings.sleepSessionRecordIdV2(session.id)
        }
        val idsAfter = listOf(correctedNight, nap).associate { session ->
            session.id to HealthConnectTypeMappings.sleepSessionRecordIdV2(session.id)
        }

        assertTrue(collapsedNight.totalMinutes < nap.totalMinutes)
        assertTrue(correctedNight.totalMinutes > nap.totalMinutes)
        assertEquals(idsBefore, idsAfter)
        assertEquals(2, idsAfter.values.toSet().size)
        assertTrue(idsAfter.values.all { it.length <= 100 })
    }
}

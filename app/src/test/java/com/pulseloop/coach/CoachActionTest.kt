package com.pulseloop.coach.orchestration

import com.pulseloop.coach.tools.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

/**
 * Tests for pending action creation, serialization, and applyUpdates.
 * Pure logic — no Room dependency for creation/parsing tests.
 */
class CoachActionTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ── PendingAction Construction ──────────────────────────────────────

    @Test
    fun `pending action delete has all required fields`() {
        val action = PendingAction(
            kind = PendingActionKind.DELETE_ACTIVITY_SESSION,
            activityId = "session-1",
            summary = "Delete the Walking session from yesterday",
            confirmLabel = "Yes, delete it",
        )
        assertEquals(PendingActionKind.DELETE_ACTIVITY_SESSION, action.kind)
        assertEquals("session-1", action.activityId)
        assertEquals("Delete the Walking session from yesterday", action.summary)
        assertEquals("Yes, delete it", action.confirmLabel)
        assertNull(action.updates)
    }

    @Test
    fun `pending action update has updates field`() {
        val action = PendingAction(
            kind = PendingActionKind.UPDATE_ACTIVITY_SESSION,
            activityId = "session-2",
            summary = "Change type to Cycling",
            confirmLabel = "Update",
            updates = ActivityUpdates(type = "cycle", distanceKm = 5.0),
        )
        assertEquals(PendingActionKind.UPDATE_ACTIVITY_SESSION, action.kind)
        assertNotNull(action.updates)
        assertEquals("cycle", action.updates!!.type)
        assertEquals(5.0, action.updates!!.distanceKm)
    }

    @Test
    fun `pending action serialization roundtrip`() {
        val action = PendingAction(
            kind = PendingActionKind.DELETE_ACTIVITY_SESSION,
            activityId = "wo-42",
            summary = "Remove workout from yesterday",
            confirmLabel = "Confirm",
        )
        val serialized = action.toJson()
        val deserialized = PendingAction.fromJson(serialized)
        assertNotNull(deserialized)
        assertEquals(action.kind, deserialized!!.kind)
        assertEquals(action.activityId, deserialized.activityId)
        assertEquals(action.summary, deserialized.summary)
    }

    @Test
    fun `pending action fromJson returns null for invalid json`() {
        assertNull(PendingAction.fromJson("not json"))
        assertNull(PendingAction.fromJson(""))
        assertNull(PendingAction.fromJson(null))
    }

    @Test
    fun `pending action fromJson returns null for blank string`() {
        assertNull(PendingAction.fromJson("   "))
    }

    // ── ActivityUpdates ─────────────────────────────────────────────────

    @Test
    fun `activity updates with all fields`() {
        val updates = ActivityUpdates(
            type = "run",
            notes = "Felt strong",
            distanceKm = 5.0,
            durationMin = 30.0,
            perceivedEffort = "moderate",
            startTime = "2025-01-15T08:00:00",
        )
        assertEquals("run", updates.type)
        assertEquals("Felt strong", updates.notes)
        assertEquals(5.0, updates.distanceKm)
        assertEquals(30.0, updates.durationMin)
        assertEquals("moderate", updates.perceivedEffort)
        assertEquals("2025-01-15T08:00:00", updates.startTime)
    }

    @Test
    fun `activity updates null fields preserve existing`() {
        val updates = ActivityUpdates()
        assertNull(updates.type)
        assertNull(updates.notes)
        assertNull(updates.distanceKm)
        assertNull(updates.durationMin)
        assertNull(updates.perceivedEffort)
        assertNull(updates.startTime)
    }

    // ── PendingActionExecutor applyUpdates ──────────────────────────────

    @Test
    fun `applyUpdates changes type`() {
        val session = createTestSession(type = "walk", distanceMeters = 1000.0)
        val updates = ActivityUpdates(type = "run")
        val result = PendingActionExecutor.applyUpdates(updates, session)
        assertEquals("run", result.type)
        // Other fields unchanged
        assertEquals(session.notes, result.notes)
        assertEquals(session.distanceMeters!!, result.distanceMeters!!, 0.01)
    }

    @Test
    fun `applyUpdates changes distance`() {
        val session = createTestSession(distanceMeters = 1000.0)
        val updates = ActivityUpdates(distanceKm = 5.0)
        val result = PendingActionExecutor.applyUpdates(updates, session)
        assertEquals(5000.0, result.distanceMeters!!, 0.01)
    }

    @Test
    fun `applyUpdates null updates returns original`() {
        val session = createTestSession()
        val result = PendingActionExecutor.applyUpdates(null, session)
        assertEquals(session, result)
    }

    @Test
    fun `applyUpdates changes notes only`() {
        val session = createTestSession(notes = "old note")
        val updates = ActivityUpdates(notes = "new note")
        val result = PendingActionExecutor.applyUpdates(updates, session)
        assertEquals("new note", result.notes)
        assertEquals(session.type, result.type)
    }

    // ── ToolExecutionContext with Pending Actions ───────────────────────

    @Test
    fun `tool context starts with empty pending actions`() {
        val ctx = ToolExecutionContext()
        assertTrue(ctx.pendingActions.isEmpty())
    }

    @Test
    fun `tool context accepts pending actions`() {
        val ctx = ToolExecutionContext(
            pendingActions = mutableListOf(
                PendingAction(PendingActionKind.DELETE_ACTIVITY_SESSION, "id1", "summary", "confirm"),
            ),
        )
        assertEquals(1, ctx.pendingActions.size)
        assertEquals(PendingActionKind.DELETE_ACTIVITY_SESSION, ctx.pendingActions[0].kind)
    }

    // ── CoachFeatureFlags default values ────────────────────────────────

    @Test
    fun `coach feature flags have sensible defaults`() {
        val flags = CoachFeatureFlags()
        assertTrue(flags.coachEnabled)
        assertFalse(flags.webSearchEnabled)
        assertFalse(flags.writeToolsEnabled)
        assertEquals("gpt-5.4", flags.model)
        assertEquals(15, flags.maxRounds)
        assertEquals(30, flags.maxToolCalls)
    }

    @Test
    fun `coach feature flags can disable coach`() {
        val flags = CoachFeatureFlags(coachEnabled = false)
        assertFalse(flags.coachEnabled)
    }

    @Test
    fun `coach feature flags enable write tools`() {
        val flags = CoachFeatureFlags(writeToolsEnabled = true)
        assertTrue(flags.writeToolsEnabled)
    }

    // ── ToolResult summary ──────────────────────────────────────────────

    @Test
    fun `tool result summary truncates long json`() {
        val longJson = "x".repeat(200)
        val result = ToolResult(longJson)
        assertTrue(result.summary.length <= 163) // 160 + "…"
        assertTrue(result.summary.endsWith("…"))
    }

    @Test
    fun `tool result summary keeps short json`() {
        val shortJson = """{"steps":5000}"""
        val result = ToolResult(shortJson)
        assertEquals(shortJson, result.summary)
    }

    @Test
    fun `tool result error flag`() {
        val ok = ToolResult("{}")
        assertFalse(ok.isError)
        val err = ToolResult("""{"error":"msg"}""", isError = true)
        assertTrue(err.isError)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun createTestSession(
        type: String = "walk",
        notes: String? = null,
        distanceMeters: Double? = null,
    ): com.pulseloop.data.entity.ActivitySessionEntity {
        return com.pulseloop.data.entity.ActivitySessionEntity(
            id = "test-session",
            type = type,
            statusRaw = "finished",
            startedAt = System.currentTimeMillis(),
            endedAt = System.currentTimeMillis() + 3600_000L,
            notes = notes,
            distanceMeters = distanceMeters,
        )
    }
}

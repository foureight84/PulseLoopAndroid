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

    // ── PendingActionExecutor.resolveUpdates (iOS #57c) ─────────────────
    // `applyUpdates` itself now routes type/time changes through `ActivityAggregates.applyEdit`
    // (a DB-integration call, like `LiveWorkoutManager.finish()` / `ActivityRollup` — this app has
    // no in-memory-Room/Robolectric test harness) — `resolveUpdates` pulls out its pure branching
    // (duration-derives-end, a start-only shift preserves the span, notes/effort pass through) so
    // that part stays independently testable.

    @Test
    fun `resolveUpdates type change alone does not touch the window`() {
        val session = createTestSession(type = "walk", distanceMeters = 1000.0)
        val resolved = PendingActionExecutor.resolveUpdates(ActivityUpdates(type = "run"), session)
        assertEquals("run", resolved.type)
        assertEquals(session.startedAt, resolved.startedAt)
        assertEquals(session.endedAt, resolved.endedAt)
        assertTrue(resolved.windowChanged)
    }

    @Test
    fun `resolveUpdates duration derives a new end time`() {
        val session = createTestSession()
        val resolved = PendingActionExecutor.resolveUpdates(ActivityUpdates(durationMin = 45.0), session)
        assertEquals(session.startedAt + 45L * 60_000, resolved.endedAt)
        assertTrue(resolved.windowChanged)
    }

    @Test
    fun `resolveUpdates start shift without duration preserves the span`() {
        val session = createTestSession()
        val originalSpan = session.endedAt!! - session.startedAt
        // startTime is date-level only (CoachDataAccess.parseLocalDate truncates to start-of-day).
        val targetDate = java.time.Instant.ofEpochMilli(session.startedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().minusDays(1)
        val expectedStart = targetDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val resolved = PendingActionExecutor.resolveUpdates(
            ActivityUpdates(startTime = targetDate.toString()), session,
        )
        assertEquals(expectedStart, resolved.startedAt)
        assertEquals(originalSpan, resolved.endedAt - resolved.startedAt)
    }

    @Test
    fun `resolveUpdates with no type or time fields leaves the window unchanged`() {
        val session = createTestSession(notes = "old note")
        val resolved = PendingActionExecutor.resolveUpdates(ActivityUpdates(notes = "new note"), session)
        assertFalse(resolved.windowChanged)
        assertEquals("new note", resolved.notes)
        assertEquals(session.type, resolved.type)
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

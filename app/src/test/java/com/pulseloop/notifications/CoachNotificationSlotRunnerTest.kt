package com.pulseloop.notifications

import com.pulseloop.data.dao.CoachNotificationRecordDao
import com.pulseloop.data.entity.CoachNotificationRecordEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate tests for [CoachNotificationSlotRunner] — the iOS #94 contract: the
 * in-flight guard, per-(day, slot) dedupe, the disabled gate, and the two
 * "not now, not yet" skips (stale data, unsynced sleep) that are deliberately
 * NOT recorded so the data trigger can fire the slot later.
 *
 * The app has no in-memory-Room/Robolectric harness (see ActivityAggregatesTest's
 * note), so the runner is exercised against an in-memory fake of the small
 * [CoachNotificationRecordDao] interface — the dedupe query runs against the
 * same rows the fake inserts, so the key logic is the real one.
 */
class CoachNotificationSlotRunnerTest {

    /** In-memory [CoachNotificationRecordDao]: inserts and the dedupe EXISTS query
     *  share one row list, so tests exercise the real interface + real key logic. */
    private class InMemoryRecordDao : CoachNotificationRecordDao {
        val records = mutableListOf<CoachNotificationRecordEntity>()

        override suspend fun insert(record: CoachNotificationRecordEntity) {
            records += record
        }

        override suspend fun recent(limit: Int): List<CoachNotificationRecordEntity> =
            records.sortedByDescending { it.createdAt }.take(limit)

        override suspend fun existsForDateKeyAndSlot(dateKey: Long, slotRaw: String): Boolean =
            records.any { it.dateKey == dateKey && it.slotRaw == slotRaw }

        override suspend fun clear() {
            records.clear()
        }
    }

    /** Mutable harness: one runner wired to in-memory fakes, all knobs default to
     *  "healthy, fresh, inside the morning window, feature enabled." */
    private class Harness(
        var coachEnabled: Boolean = true,
        var notificationsEnabled: Boolean = true,
        var apiKey: String = "sk-test",
        var fresh: Boolean = true,
        var latestMeasurementAt: Long? = null,
        var sleepSessionEndAt: Long? = null,
        var deviceFullSyncAt: Long? = null,
        var policy: CoachStaleDataPolicy = CoachStaleDataPolicy.SKIP,
    ) {
        val recordDao = InMemoryRecordDao()
        val delivered = mutableListOf<Pair<String, String>>()
        var sleepRetriesScheduled = 0
        var generateGate: CompletableDeferred<CoachNotificationContent>? = null

        // Lambdas read the mutable knobs via this. so a test mutating a knob
        // (h.fresh = false, ...) is seen by the runner on its next run.
        val runner = CoachNotificationSlotRunner(
            settings = {
                CoachCheckinSettings(
                    this.coachEnabled, this.notificationsEnabled, this.apiKey, "gpt-5.4",
                )
            },
            recordDao = recordDao,
            latestSleepSessionEndAt = { this.sleepSessionEndAt },
            currentDeviceFullSyncAt = { this.deviceFullSyncAt },
            latestMeasurementTimestamp = { this.latestMeasurementAt },
            staleDataPolicy = policy,
            ensureFreshData = { this.fresh },
            generate = { slot, _ ->
                this.generateGate?.await()
                CoachNotificationContent("AI-${slot.name}", "ai body")
            },
            deliver = { t, b -> this.delivered += t to b },
            onSleepRetryNeeded = { this.sleepRetriesScheduled++ },
            clock = { NOW_MORNING },
        )
    }

    private companion object {
        private fun at(local: LocalDateTime): Long =
            local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        /** 09:00 — inside the default morning window (08:00–12:00). */
        val NOW_MORNING = at(LocalDateTime.of(2026, 8, 22, 9, 0))
        val NOW_MORNING_TOMORROW = at(LocalDateTime.of(2026, 8, 23, 9, 0))
        /** 15:00 — inside no slot window. */
        val NOW_AFTERNOON = at(LocalDateTime.of(2026, 8, 22, 15, 0))
    }

    @Test
    fun `a due slot sends once and records the dedupe key`() = runTest {
        val h = Harness()
        assertEquals(
            CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING),
            h.runner.runDueSlot(),
        )
        assertEquals(1, h.recordDao.records.size)
        val rec = h.recordDao.records.single()
        assertEquals(CoachNotificationSlotRunner.dateKeyFor(NOW_MORNING), rec.dateKey)
        assertEquals("morning", rec.slotRaw)
        assertEquals(listOf("AI-MORNING" to "ai body"), h.delivered)
    }

    @Test
    fun `a second run for the same day and slot is skippedDuplicate`() = runTest {
        val h = Harness()
        assertEquals(CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING), h.runner.runDueSlot())
        assertEquals(CoachNotificationOutcome.SkippedDuplicate, h.runner.runDueSlot())
        // No double record, no double delivery — the worker/data-trigger safety net.
        assertEquals(1, h.recordDao.records.size)
        assertEquals(1, h.delivered.size)
    }

    @Test
    fun `the same slot the next day is not a duplicate`() = runTest {
        val h = Harness()
        assertEquals(CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING), h.runner.runDueSlot())
        assertEquals(
            CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING),
            h.runner.runDueSlot(now = NOW_MORNING_TOMORROW),
        )
        assertEquals(2, h.recordDao.records.size)
    }

    @Test
    fun `outside every slot window is skippedNoSlot`() = runTest {
        val h = Harness()
        assertEquals(CoachNotificationOutcome.SkippedNoSlot, h.runner.runDueSlot(now = NOW_AFTERNOON))
        assertTrue(h.recordDao.records.isEmpty())
        assertTrue(h.delivered.isEmpty())
    }

    @Test
    fun `a concurrent entry while a run is in flight is skippedDuplicate`() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<CoachNotificationContent>()
        h.generateGate = gate

        // The first run holds the static in-flight guard while generation awaits.
        val first = async(start = CoroutineStart.UNDISPATCHED) { h.runner.runDueSlot() }
        assertEquals(CoachNotificationOutcome.SkippedDuplicate, h.runner.runDueSlot())

        gate.complete(CoachNotificationContent("done", "body"))
        assertEquals(CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING), first.await())
        assertEquals(1, h.recordDao.records.size)
        assertEquals(1, h.delivered.size)
    }

    @Test
    fun `a disabled coach or opt-in is skippedDisabled with no delivery`() = runTest {
        val coachOff = Harness(coachEnabled = false)
        assertEquals(CoachNotificationOutcome.SkippedDisabled, coachOff.runner.runDueSlot())
        assertTrue(coachOff.recordDao.records.isEmpty())
        assertTrue(coachOff.delivered.isEmpty())

        val optInOff = Harness(notificationsEnabled = false)
        assertEquals(CoachNotificationOutcome.SkippedDisabled, optInOff.runner.runDueSlot())
        assertTrue(optInOff.recordDao.records.isEmpty())
        assertTrue(optInOff.delivered.isEmpty())
    }

    @Test
    fun `a stale-data skip is not recorded so a later fresh run still sends`() = runTest {
        val h = Harness()
        h.fresh = false
        assertEquals(CoachNotificationOutcome.SkippedStaleData, h.runner.runDueSlot())
        // The whole point of iOS #94: nothing was recorded, so the slot is still
        // deliverable.
        assertTrue(h.recordDao.records.isEmpty())
        assertTrue(h.delivered.isEmpty())

        // A full sync lands (what the data trigger is waiting for) — the slot fires.
        h.fresh = true
        assertEquals(CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING), h.runner.runDueSlot())
        assertEquals(1, h.recordDao.records.size)
        assertEquals(1, h.delivered.size)
    }

    @Test
    fun `sendWithLastKnown sends with data but skips an empty store`() = runTest {
        val h = Harness(policy = CoachStaleDataPolicy.SEND_WITH_LAST_KNOWN)
        h.fresh = false
        assertEquals(CoachNotificationOutcome.SkippedNoData, h.runner.runDueSlot())
        h.latestMeasurementAt = NOW_MORNING - 60_000L
        assertEquals(CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING), h.runner.runDueSlot())
        assertEquals(1, h.recordDao.records.size)
    }

    @Test
    fun `a morning slot blocked on sleep data is not recorded and schedules the retry`() = runTest {
        val h = Harness()
        // Last night ended 5h ago (recent) but the last full sync is OLDER than that —
        // the one shape CoachSleepSyncGate.sleepDataSynced blocks on.
        h.sleepSessionEndAt = NOW_MORNING - 5 * 3600_000L
        h.deviceFullSyncAt = NOW_MORNING - 10 * 3600_000L

        assertEquals(CoachNotificationOutcome.SkippedNoSleepData, h.runner.runDueSlot())
        assertTrue(h.recordDao.records.isEmpty())
        assertTrue(h.delivered.isEmpty())
        assertEquals(1, h.sleepRetriesScheduled)
    }

    @Test
    fun `a missing api key delivers the generic check-in and records the slot`() = runTest {
        val h = Harness(apiKey = "")
        assertEquals(CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING), h.runner.runDueSlot())
        assertEquals(
            listOf(CoachNotificationSlotRunner.GENERIC_TITLE to CoachNotificationSlotRunner.GENERIC_BODY),
            h.delivered,
        )
        // ...and a later trigger run the same day (fresh data landing) can't double-send.
        assertEquals(CoachNotificationOutcome.SkippedDuplicate, h.runner.runDueSlot())
        assertEquals(1, h.recordDao.records.size)
    }
}

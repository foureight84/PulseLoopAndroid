package com.pulseloop.notifications

import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.RingConnectionState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [CoachNotificationDataTrigger]'s handle() contract — the iOS #94
 * subscriber behavior: it fires only on SyncProgress("done"), coalesces
 * back-to-back completions into a single attempt after the settle window, and
 * stays silent when the feature is off.
 *
 * These drive the internal handle() directly on a virtual Main dispatcher rather
 * than publishing through the shared [PulseEventBus]: the bus fans out on real
 * Dispatchers.Default threads, and bridging that into a virtual test clock is
 * racy (it was a flaky failure). The bus itself is covered by PulseEventBusTest,
 * and the one-line events.collect { handle(it) } wiring in start() is exercised
 * in production, so the deterministic handle() tests here are the meaningful
 * slice.
 */
class CoachNotificationDataTriggerTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun settings(
        coachEnabled: Boolean = true,
        notificationsEnabled: Boolean = true,
    ): () -> CoachCheckinSettings = {
        CoachCheckinSettings(coachEnabled, notificationsEnabled, "sk-test", "gpt-5.4")
    }

    @Test
    fun `fires only on a completed sync and coalesces back-to-back completions`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val trigger = CoachNotificationDataTrigger(
            checkinSettings = settings(),
            runDueSlot = {
                calls++
                CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING)
            },
        )
        try {
            // Other sync stages and unrelated events are ignored — no settle window is armed:
            trigger.handle(PulseEvent.SyncProgress("Syncing sleep…"))
            trigger.handle(PulseEvent.HeartRateSample(72, Instant.now()))
            trigger.handle(PulseEvent.BatteryLevel(88))
            trigger.handle(PulseEvent.DeviceStateChanged(RingConnectionState.CONNECTED, "AA:BB:CC"))
            advanceUntilIdle()
            assertEquals(0, calls)

            // A full sync completion arms the settle window; a back-to-back completion
            // coalesces into the same single attempt (the earlier debounce is cancelled).
            trigger.handle(PulseEvent.SyncProgress("done"))
            trigger.handle(PulseEvent.SyncProgress("done"))
            advanceUntilIdle()
            assertEquals(1, calls)
        } finally {
            trigger.destroy()
        }
    }

    @Test
    fun `a disabled feature never wakes the runner`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val trigger = CoachNotificationDataTrigger(
            checkinSettings = settings(coachEnabled = false),
            runDueSlot = {
                calls++
                CoachNotificationOutcome.Sent(CoachNotificationSlot.MORNING)
            },
        )
        try {
            trigger.handle(PulseEvent.SyncProgress("done"))
            advanceUntilIdle()
            assertEquals(0, calls)
        } finally {
            trigger.destroy()
        }
    }
}

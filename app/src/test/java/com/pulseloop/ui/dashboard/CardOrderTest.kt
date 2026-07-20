package com.pulseloop.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure list operations behind card reorder, ported from iOS `CardReorderDragTests` /
 * `CardReorderPrefsTests` (#64). No Android dependency.
 */
class CardOrderTest {

    private val keys = listOf("steps", "sleep", "heartRate", "spo2", "hrv")

    // ── CardOrder.moving ────────────────────────────────────────────────
    // `to` indexes the array *before* the removal — what the drop delegate hands us.

    @Test fun movingForwardLandsAtTargetIndex() {
        assertEquals(listOf(1, 2, 0, 3), CardOrder.moving(listOf(0, 1, 2, 3), from = 0, to = 2))
    }

    @Test fun movingBackwardLandsAtTargetIndex() {
        assertEquals(listOf(0, 3, 1, 2), CardOrder.moving(listOf(0, 1, 2, 3), from = 3, to = 1))
    }

    @Test fun movingToEitherEnd() {
        assertEquals(listOf("sleep", "heartRate", "spo2", "hrv", "steps"), CardOrder.moving(keys, from = 0, to = 4))
        assertEquals(listOf("hrv", "steps", "sleep", "heartRate", "spo2"), CardOrder.moving(keys, from = 4, to = 0))
    }

    @Test fun movingIsIdentityWhenSourceEqualsDestination() {
        assertEquals(keys, CardOrder.moving(keys, from = 2, to = 2))
    }

    @Test fun movingIgnoresOutOfRangeSource() {
        assertEquals(keys, CardOrder.moving(keys, from = 9, to = 0))
    }

    @Test fun movingClampsOutOfRangeDestination() {
        assertEquals(listOf(1, 2, 0), CardOrder.moving(listOf(0, 1, 2), from = 0, to = 99))
        assertEquals(listOf(2, 0, 1), CardOrder.moving(listOf(0, 1, 2), from = 2, to = -5))
    }

    // ── CardOrder.reconcile ─────────────────────────────────────────────

    @Test fun reconcilePreservesTheUsersOrder() {
        assertEquals(
            listOf("hrv", "steps", "heartRate"),
            CardOrder.reconcile(listOf("hrv", "steps", "heartRate"), listOf("steps", "heartRate", "hrv")),
        )
    }

    @Test fun reconcileDropsKeysThatVanished() {
        assertEquals(
            listOf("steps", "heartRate"),
            CardOrder.reconcile(listOf("hrv", "steps", "heartRate"), listOf("steps", "heartRate")),
        )
    }

    @Test fun reconcileInsertsNewKeyAtItsTargetIndex() {
        assertEquals(
            listOf("steps", "spo2", "heartRate"),
            CardOrder.reconcile(listOf("steps", "heartRate"), listOf("steps", "spo2", "heartRate")),
        )
    }

    @Test fun reconcileWithEmptyCurrentYieldsTarget() {
        assertEquals(keys, CardOrder.reconcile(emptyList(), keys))
    }

    @Test fun reconcileWithEmptyTargetYieldsEmpty() {
        assertEquals(emptyList<String>(), CardOrder.reconcile(keys, emptyList()))
    }

    @Test fun reconcileIsIdempotent() {
        val once = CardOrder.reconcile(listOf("hrv", "steps"), listOf("steps", "hrv", "spo2"))
        assertEquals(once, CardOrder.reconcile(once, once))
    }

    // ── CardOrder.resolvedOrder ─────────────────────────────────────────

    @Test fun noSavedOrderFallsBackToDefaultOrder() {
        assertEquals(keys, CardOrder.resolvedOrder(saved = emptyList(), visible = keys.toSet(), defaultOrder = keys))
    }

    @Test fun savedOrderIsHonored() {
        val reversed = keys.reversed()
        assertEquals(reversed, CardOrder.resolvedOrder(saved = reversed, visible = keys.toSet(), defaultOrder = keys))
    }

    @Test fun resolvedOrderDropsMetricsThatAreNotVisible() {
        val visible = listOf("steps", "heartRate", "hrv")
        assertEquals(visible, CardOrder.resolvedOrder(saved = emptyList(), visible = visible.toSet(), defaultOrder = keys))
    }

    @Test fun unorderedMetricSlotsIntoItsDefaultPositionNotTheEnd() {
        // Saved order lists only steps + sleep; hrv (visible, unordered) must land at its default
        // slot (right after spo2), not appended.
        val saved = listOf("steps", "sleep")
        val resolved = CardOrder.resolvedOrder(saved = saved, visible = keys.toSet(), defaultOrder = keys)
        assertEquals(keys, resolved)
        assertEquals(resolved.indexOf("spo2") + 1, resolved.indexOf("hrv"))
    }

    @Test fun unorderedFirstMetricSlotsToTheFront() {
        // steps (default-first) is unordered; it slots to the front, not the end.
        val saved = listOf("sleep", "heartRate", "spo2", "hrv")
        val resolved = CardOrder.resolvedOrder(saved = saved, visible = keys.toSet(), defaultOrder = keys)
        assertEquals("steps", resolved.first())
        assertEquals(keys, resolved)
    }

    @Test fun resolvedOrderIgnoresSavedIdsNoLongerVisible() {
        val saved = listOf("dinosaur", "steps", "sleep", "heartRate", "spo2", "hrv")
        assertEquals(keys, CardOrder.resolvedOrder(saved = saved, visible = keys.toSet(), defaultOrder = keys))
    }
}

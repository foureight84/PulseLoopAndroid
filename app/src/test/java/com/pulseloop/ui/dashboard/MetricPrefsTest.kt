package com.pulseloop.ui.dashboard

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tolerant JSON decode + visibility/order behavior of [MetricPrefs], ported from iOS
 * `CardReorderPrefsTests` (#64). Pure — exercises the same serializer the store persists with.
 */
class MetricPrefsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun decode(s: String) = json.decodeFromString(MetricPrefs.serializer(), s)
    private fun encode(p: MetricPrefs) = json.encodeToString(MetricPrefs.serializer(), p)

    @Test fun roundTripsThroughJson() {
        val p = MetricPrefs(
            hiddenMetrics = setOf("hrv"),
            todayHiddenMetrics = setOf("stress"),
            vitalsOrder = listOf("spo2", "heartRate"),
            todayOrder = listOf("sleep", "steps"),
        )
        assertEquals(p, decode(encode(p)))
    }

    @Test fun decodingBlobWrittenBeforeOrderExistedYieldsEmptyOrder() {
        // Only hiddenMetrics was persisted (an older build); the order fields fall back to empty.
        val prefs = decode("""{"hiddenMetrics":["hrv"]}""")
        assertEquals(emptyList<String>(), prefs.todayOrder)
        assertEquals(emptyList<String>(), prefs.vitalsOrder)
        assertEquals(setOf("hrv"), prefs.hiddenMetrics)
    }

    @Test fun decodingToleratesUnknownKeys() {
        // iOS also persists `resolution`/`todayResolution`; Android ignores them, not fails.
        val prefs = decode("""{"hiddenMetrics":["hrv"],"resolution":"full","todayResolution":"hourly"}""")
        assertEquals(setOf("hrv"), prefs.hiddenMetrics)
    }

    @Test fun hideAndRestoreRoundTripsPerScope() {
        var p = MetricPrefs.DEFAULT
        p = p.withHidden(DashboardCard.HRV, true, MetricScope.TODAY)
        assertTrue(p.isHidden(DashboardCard.HRV, MetricScope.TODAY))
        // Scopes are independent: hiding on Today doesn't hide on Vitals.
        assertFalse(p.isHidden(DashboardCard.HRV, MetricScope.VITALS))
        p = p.withHidden(DashboardCard.HRV, false, MetricScope.TODAY)
        assertFalse(p.isHidden(DashboardCard.HRV, MetricScope.TODAY))
    }

    @Test fun orderIsScopedIndependently() {
        val p = MetricPrefs.DEFAULT.withOrder(listOf("spo2", "heartRate"), MetricScope.VITALS)
        assertEquals(listOf("spo2", "heartRate"), p.order(MetricScope.VITALS))
        assertEquals(emptyList<String>(), p.order(MetricScope.TODAY))
    }

    @Test fun hiddenCardRestoresToItsDraggedPositionNotItsDefault() {
        val keys = listOf("steps", "sleep", "heartRate", "spo2", "hrv")
        val cards = keys.map { DashboardCard.fromKey(it)!! }
        fun resolve(p: MetricPrefs, visible: List<DashboardCard>) =
            p.resolvedOrder(visible.map { it.key }.toSet(), keys, MetricScope.TODAY)

        // User drags HRV to the front — the live order is persisted on drop.
        val dragged = listOf("hrv", "steps", "sleep", "heartRate", "spo2")
        var p = MetricPrefs.DEFAULT.withOrder(dragged, MetricScope.TODAY)

        // Then hides HRV: it leaves the grid.
        p = p.withHidden(DashboardCard.HRV, true, MetricScope.TODAY)
        val visibleAfterHide = cards.filter { !p.isHidden(it, MetricScope.TODAY) }
        assertEquals(listOf("steps", "sleep", "heartRate", "spo2"), resolve(p, visibleAfterHide))

        // The tray's "+" brings it back to where they dragged it — resolvedOrder only filters the
        // saved order by visibility, so the front position survives.
        p = p.withHidden(DashboardCard.HRV, false, MetricScope.TODAY)
        assertEquals(dragged, resolve(p, cards))
    }
}

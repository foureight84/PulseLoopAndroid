package com.pulseloop.ui.dashboard

import kotlinx.serialization.Serializable

/**
 * User-tunable per-metric visibility + card ordering, ported from iOS #64 (`MetricPrefs`).
 * Persisted as JSON; an immutable value so all logic here is pure and JVM-testable. Field names
 * mirror the iOS keys (`hiddenMetrics`/`todayHiddenMetrics`/`vitalsOrder`/`todayOrder`) so the blob
 * is portable. iOS's `resolution`/`todayResolution` (chart downsampling) are omitted — Android has
 * no downsampling surface.
 *
 * Hidden sets are opt-out (by [DashboardCard.key]) so a newly-supported metric defaults to visible
 * with no migration. An empty order means "use the screen default".
 */
@Serializable
data class MetricPrefs(
    val hiddenMetrics: Set<String> = emptySet(),        // Vitals scope
    val todayHiddenMetrics: Set<String> = emptySet(),   // Today scope
    val vitalsOrder: List<String> = emptyList(),
    val todayOrder: List<String> = emptyList(),
) {
    fun isHidden(card: DashboardCard, scope: MetricScope): Boolean =
        hiddenSet(scope).contains(card.key)

    fun withHidden(card: DashboardCard, hidden: Boolean, scope: MetricScope): MetricPrefs {
        val next = hiddenSet(scope).toMutableSet().apply { if (hidden) add(card.key) else remove(card.key) }
        return when (scope) {
            MetricScope.VITALS -> copy(hiddenMetrics = next)
            MetricScope.TODAY -> copy(todayHiddenMetrics = next)
        }
    }

    fun order(scope: MetricScope): List<String> =
        if (scope == MetricScope.TODAY) todayOrder else vitalsOrder

    fun withOrder(order: List<String>, scope: MetricScope): MetricPrefs = when (scope) {
        MetricScope.TODAY -> copy(todayOrder = order)
        MetricScope.VITALS -> copy(vitalsOrder = order)
    }

    /**
     * The resolved display order for [visible] cards in [scope], slotting unordered-but-visible cards
     * into their default neighbourhood (see [CardOrder.resolvedOrder]).
     */
    fun resolvedOrder(visible: Set<String>, defaultOrder: List<String>, scope: MetricScope): List<String> =
        CardOrder.resolvedOrder(order(scope), visible, defaultOrder)

    private fun hiddenSet(scope: MetricScope): Set<String> =
        if (scope == MetricScope.TODAY) todayHiddenMetrics else hiddenMetrics

    companion object {
        val DEFAULT = MetricPrefs()
    }
}

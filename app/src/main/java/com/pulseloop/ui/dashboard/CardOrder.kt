package com.pulseloop.ui.dashboard

/**
 * Pure list operations behind card reorder, ported from iOS #64 (`CardOrder`). No Android
 * dependencies — driven directly by unit tests. All three keep a drag off the persistence path
 * and keep a mid-edit data rebuild from undoing what the user sees.
 */
object CardOrder {

    /**
     * Move the item at [from] to [to], where [to] indexes the list *before* the removal (the value
     * a drop delegate hands us). Out-of-range [from] is a no-op; [to] is clamped into range.
     */
    fun <T> moving(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val out = list.toMutableList()
        val item = out.removeAt(from)
        out.add(to.coerceIn(0, out.size), item)
        return out
    }

    /**
     * Fold a freshly-derived [target] order into the in-flight [current] drag order: keep the
     * survivors in the user's current order, drop keys that vanished, and insert genuinely-new keys
     * at their [target] position (not at the end). Idempotent once the two agree.
     */
    fun <T> reconcile(current: List<T>, target: List<T>): List<T> {
        val targetSet = target.toSet()
        val result = current.filter { it in targetSet }.toMutableList()
        val survivors = result.toSet()
        for ((i, id) in target.withIndex()) {
            if (id in survivors) continue
            // Land just after the nearest target-predecessor that already has a slot; a run of new
            // keys therefore keeps its target-relative order.
            val anchor = target.subList(0, i).lastOrNull { result.contains(it) }
            val at = anchor?.let { result.indexOf(it) + 1 } ?: 0
            result.add(at, id)
        }
        return result
    }

    /**
     * Resolve the display order for the currently-[visible] card keys: the [saved] order filtered to
     * visible, with any visible-but-unordered key slotted into its [defaultOrder] neighbourhood rather
     * than appended — so a card restored from the Hidden tray, or one a newly-paired ring just
     * unlocked, reappears where the user expects. With no saved order this reduces to [defaultOrder]
     * filtered by [visible].
     */
    fun resolvedOrder(saved: List<String>, visible: Set<String>, defaultOrder: List<String>): List<String> {
        val result = saved.filter { visible.contains(it) }.toMutableList()
        val savedSet = result.toSet()
        for ((i, id) in defaultOrder.withIndex()) {
            if (!visible.contains(id) || savedSet.contains(id)) continue
            val anchor = defaultOrder.subList(0, i).lastOrNull { result.contains(it) }
            val at = anchor?.let { result.indexOf(it) + 1 } ?: 0
            result.add(at, id)
        }
        return result
    }
}

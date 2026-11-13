package com.pulseloop.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spotting a ring whose clock has stopped (issue #142).
 *
 * A YCBT ring's clock can freeze while the ring still connects and syncs. It then takes no
 * automatic readings and records no sleep, so in the app it just looks like the ring wasn't worn.
 * Setting the time on connect does not restart it; powering the ring off and back on does. There is
 * no command to read the ring's clock, so this watches the one thing we can check: a spot reading
 * the ring completed itself (`04 0e` success, stored as `source = "spot"`) is logged by the ring
 * into its own history, stamped by the ring's clock **when the measurement ends** (the reporter's
 * captures: 08:44:44 for a connection ending 08:44:48; 10:31:26 for one ending 10:31:30). With the
 * clock stopped it carries the last time we set instead (00:38:25 for a connection 00:38:21 to
 * 00:39:48), or the copy lands nowhere near our reading at all.
 *
 * So the evidence is either
 *  - the ring's copy paired with our reading is stamped at least [LAG_EVIDENCE_MS] before it
 *    (measurements run 35–63 s, so a working clock stamps within seconds of our settled time), or
 *  - a reading still has no copy [UNMATCHED_AFTER_MS] after a completed sync.
 *
 * One piece of evidence is not enough (a dropped history frame looks like the second case), so the
 * warning needs [EVIDENCE_NEEDED] within [EVIDENCE_WINDOW_MS]. A healthy pairing clears it, which is
 * also how the restart flow confirms the fix: the next reading after restarting comes back stamped
 * at its end.
 *
 * Only YCBT rings produce `spot` rows (see `EventPersistenceSubscriber.SOURCE_SPOT`), so the
 * detector is scoped to the family that has the problem without checking the family itself.
 */
object RingClockStall {
    const val LAG_EVIDENCE_MS = 45_000L
    /** A ring stamping to the whole minute can read up to 60 s early on a working clock. */
    const val LAG_EVIDENCE_MINUTE_STAMP_MS = 60_000L + LAG_EVIDENCE_MS
    const val HEALTHY_LAG_MS = 20_000L
    const val UNMATCHED_AFTER_MS = 10 * 60_000L
    const val EVIDENCE_NEEDED = 2
    const val EVIDENCE_WINDOW_MS = 24 * 3600_000L
    private const val CHECKED_CAP = 50

    data class State(
        /** When each piece of evidence was seen. */
        val evidence: List<Long> = emptyList(),
        /** Our spot readings already judged, so a reading counts once. */
        val checked: List<Long> = emptyList(),
        /** When the warning was raised; null while the clock looks fine. */
        val suspectedAt: Long? = null,
    )

    /** The ring's copy of our spot reading at [ourAt] landed, stamped [ringAt]. */
    fun onCopyPaired(state: State, ourAt: Long, ringAt: Long, now: Long): State {
        val lag = ourAt - ringAt
        // A working clock clears the warning even for a reading an earlier sync already counted
        // as unmatched: its copy simply arrived late.
        if (kotlin.math.abs(lag) <= HEALTHY_LAG_MS) return State(checked = state.markChecked(ourAt).checked)
        if (ourAt in state.checked) return state
        val threshold = if (ringAt % 60_000L == 0L) LAG_EVIDENCE_MINUTE_STAMP_MS else LAG_EVIDENCE_MS
        return if (lag >= threshold) addEvidence(state.markChecked(ourAt), now) else state.markChecked(ourAt)
    }

    /** A sync finished at [now]; [unmatched] are our spot readings still waiting for a copy. */
    fun onSyncFinished(state: State, unmatched: List<Long>, now: Long): State =
        unmatched.sorted()
            .filter { now - it >= UNMATCHED_AFTER_MS && it !in state.checked }
            .fold(state) { s, at -> addEvidence(s.markChecked(at), now) }

    private fun addEvidence(state: State, now: Long): State {
        val evidence = (state.evidence + now).filter { now - it <= EVIDENCE_WINDOW_MS }
        val suspected = state.suspectedAt ?: if (evidence.size >= EVIDENCE_NEEDED) now else null
        return state.copy(evidence = evidence, suspectedAt = suspected)
    }

    private fun State.markChecked(at: Long): State = copy(checked = (checked + at).takeLast(CHECKED_CAP))
}

/** Process-wide holder of [RingClockStall.State], persisted in SharedPreferences. */
object RingClockStallStore {
    private const val PREFS = "ring_clock_stall"
    private const val KEY_EVIDENCE = "evidence"
    private const val KEY_CHECKED = "checked"
    private const val KEY_SUSPECTED = "suspectedAt"
    private const val KEY_DISMISSED = "dismissedAt"

    private val _suspectedAt = MutableStateFlow<Long?>(null)
    /** When the warning was raised and not dismissed; null hides it. */
    val suspectedAt: StateFlow<Long?> = _suspectedAt.asStateFlow()
    @Volatile private var loaded = false

    @Synchronized
    fun update(context: Context, change: (RingClockStall.State) -> RingClockStall.State) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val before = read(prefs)
        val after = change(before)
        if (after != before) {
            prefs.edit()
                .putString(KEY_EVIDENCE, after.evidence.joinToString(","))
                .putString(KEY_CHECKED, after.checked.joinToString(","))
                .apply { if (after.suspectedAt != null) putLong(KEY_SUSPECTED, after.suspectedAt) else remove(KEY_SUSPECTED) }
                // A cleared warning forgets its dismissal, so a later stall warns again.
                .apply { if (after.suspectedAt == null) remove(KEY_DISMISSED) }
                .apply()
        }
        publish(prefs, after)
    }

    /** Load the persisted state so the warning shows on app start. */
    fun load(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        publish(prefs, read(prefs))
        loaded = true
    }

    /** Hide the warning until the clock is next seen working and then stalls again. */
    fun dismiss(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val suspected = prefs.getLong(KEY_SUSPECTED, 0L).takeIf { it > 0L } ?: return
        prefs.edit().putLong(KEY_DISMISSED, suspected).apply()
        _suspectedAt.value = null
    }

    private fun publish(prefs: android.content.SharedPreferences, state: RingClockStall.State) {
        val dismissed = prefs.getLong(KEY_DISMISSED, 0L)
        _suspectedAt.value = state.suspectedAt?.takeIf { it != dismissed }
        loaded = true
    }

    private fun read(prefs: android.content.SharedPreferences) = RingClockStall.State(
        evidence = longs(prefs.getString(KEY_EVIDENCE, null)),
        checked = longs(prefs.getString(KEY_CHECKED, null)),
        suspectedAt = prefs.getLong(KEY_SUSPECTED, 0L).takeIf { it > 0L },
    )

    private fun longs(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
}

package com.pulseloop.notifications

import android.content.Context
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ported from CoachNotificationDataTrigger.swift (iOS #94).
 *
 * Fires the coach check-in the moment fresh data actually lands, instead of
 * only at the scheduled wake. Subscribes to [PulseEventBus] and, when a full
 * history sync completes (SyncProgress stage "done"), runs the due slot — so a
 * slot the periodic worker skipped as SkippedStaleData (ring out of range, sync
 * didn't finish in budget) is delivered right after the sync lands, while the
 * numbers are minutes old.
 *
 * Deliberately owns no slot/dedupe/freshness logic:
 * [CoachNotificationSlotRunner.runDueSlot] gates everything. A sync that
 * completes outside a slot window is SkippedNoSlot (silence), an already-sent
 * slot is SkippedDuplicate, and the runner's static in-flight guard covers a
 * race with a concurrently running worker. Lives for the app lifetime, like
 * [com.pulseloop.coach.summaries.CoachSummaryCoordinator].
 */
class CoachNotificationDataTrigger(
    /** Needed only when the production runner is used (the default [runDueSlot]). */
    private val context: Context? = null,
    /** The opt-in slice the pre-check reads (iOS runAfterSync's settings check). */
    private val checkinSettings: () -> CoachCheckinSettings,
    /** The due-slot runner to nudge on sync completion. Injectable for tests;
     *  the default is the shared production runner. */
    private val runDueSlot: (suspend () -> CoachNotificationOutcome)? = null,
) {
    private val slotRun: suspend () -> CoachNotificationOutcome =
        runDueSlot ?: {
            val c = requireNotNull(context) {
                "CoachNotificationDataTrigger needs a Context when the production runner is used"
            }
            CoachNotificationSlotRunner.forContext(c).runDueSlot()
        }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var streamJob: Job? = null
    private var debounceJob: Job? = null

    /**
     * Short settle window after the "done" event: bus fan-out order between
     * subscribers isn't guaranteed, so this lets [com.pulseloop.service.EventPersistenceSubscriber]
     * stamp Device.lastFullSyncAt (what the freshness gate reads) before the slot
     * runs — and coalesces back-to-back completions into one attempt.
     */
    private val debounceMs = 3_000L

    fun start() {
        if (streamJob != null) return
        streamJob = scope.launch {
            PulseEventBus.events.collect { event -> handle(event) }
        }
    }

    fun stop() {
        streamJob?.cancel(); streamJob = null
        debounceJob?.cancel(); debounceJob = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    /** Internal for unit tests (friend source set): the event filter + debounce
     *  are the whole of this class, and the bus itself is covered separately by
     *  PulseEventBusTest. */
    internal fun handle(event: PulseEvent) {
        if (event !is PulseEvent.SyncProgress || event.stage != DONE_STAGE) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            // Pre-check (iOS runAfterSync): a disabled feature shouldn't wake the runner
            // on every sync-completion a re-linked ring produces.
            val s = checkinSettings()
            if (!s.coachEnabled || !s.notificationsEnabled) return@launch
            // The run itself is a sibling job on [scope], NOT a child of this debounce job:
            // the next "done" event cancels debounceJob, and a run started here takes seconds
            // (network generation). Cancelling it mid-flight can land between
            // recordDao.insert and deliver() inside runDueSlot — the slot recorded as sent
            // with no notification shown, and the dedupe then suppresses every later attempt
            // that day. Only the pending delay above is cancellable; concurrent runs are
            // already covered by the runner's process-wide in-flight guard (SkippedDuplicate).
            scope.launch { slotRun() }
        }
    }

    companion object {
        /** The SyncProgress stage that means a full history sync COMPLETED. */
        const val DONE_STAGE = "done"
    }
}

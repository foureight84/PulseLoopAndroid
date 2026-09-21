package com.pulseloop.ring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The jring history pager: one day at a time, and the next request is held until the current
 * day's stream goes quiet.
 *
 * **Why this exists (issue #73, second half).** Asking for the right day was necessary but not
 * sufficient. With the window enqueued in one pass, an SR08 capture showed `10 00`, `10 01`,
 * `10 02` and `16 00` all going out inside **176 ms**, with `10 02` landing *between* two of
 * today's sleep packets — and only four `0x11` packets arriving in total, three of them today's.
 * The night displayed as 45 minutes against an expected 6h30. The ring answers one history
 * request at a time; a second request mid-stream truncates the first. `JringSyncEngine` used to
 * record that the vendor's reply-driven chain would be the fix "if a ring is ever seen truncating
 * a day's stream when the next request lands" — this is that ring.
 *
 * **Why time-settled rather than reply-driven for activity/sleep.** The vendor chains on a
 * per-day sync-end callback (`DupMainActivity.onGetMultipleSportData`), but nothing in the
 * `0x10`/`0x11` wire format marks the end of a day: `0x10` is a bare run of 15× 1-minute buckets
 * and `0x11` a bare run of 15× 1-minute sleep stages (see `RingDecoder.decodeActivityHistory` /
 * `decodeSleepTimeline`). So this leg advances when the stream settles, which is what JYouPro
 * itself does — it resets a 2000 ms idle timer on each `0x10`/`0x11` reply, the same constant
 * [settleMs] defaults to. [LuckRingHistorySync] is the same shape for the same reason and this
 * is a port of it, extended with a second leg per day.
 *
 * **Heart rate does have a real end marker**, so its leg doesn't guess: `0x16` subtype `0xFF`
 * decodes to [RingDecodedEvent.HistorySyncFinished] and advances the day immediately. The
 * settle/stall timers are only its fallback.
 *
 * Per day: `0x10/offset` → the day's activity+sleep stream → settles → `0x16/offset` → the day's
 * HR stream → `0xFF` (or settles) → next day. A day that answers nothing at all still gets its HR
 * request, because `16 00` was previously sent unconditionally and today's HR must not become
 * collateral of a silent `0x10`.
 *
 * Replays are safe, so a timer that fires early costs a re-request and nothing else: activity
 * buckets upsert by timestamp with the day total recomputed from distinct buckets, and sleep
 * reconciles one waking day at a time.
 */
class JringHistorySync(
    private val writer: RingCommandWriter?,
    /** Quiet period that ends a leg. JYouPro's own idle timer between `0x10`/`0x11` replies. */
    private val settleMs: Long = 2_000,
    /** Bound on a leg that never answers at all. Comfortably under the ring's ~20 s idle
     *  disconnect, so a stalled day can't cost the connection. */
    private val stallMs: Long = 6_000,
) {
    private val encoder = RingEncoder
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The two legs of one day, in the order the vendor asks for them. */
    private enum class Leg { ACTIVITY, HEART_RATE }

    private var queue = mutableListOf<Int>()
    private var currentDay: Int? = null
    private var leg: Leg = Leg.ACTIVITY
    private var settleJob: Job? = null
    private var stallJob: Job? = null
    /** Invalidates timers that fired for a leg which has since moved on — see [armTimer]. */
    private var epoch: Long = 0

    @get:Synchronized
    val isRunning: Boolean get() = currentDay != null

    /**
     * Seed the queue and request the first day. Returns whether this call began a pass.
     *
     * A pass already in flight wins, and the caller is told so: `runStartup` is also the
     * ~30-minute background sync (and `refresh()`/`querySleep()` route through it), so a second
     * pass landing mid-backfill is the normal case, not an error — and restarting would abandon
     * the in-flight day mid-stream, which is the very truncation this class exists to prevent.
     * The `false` is what keeps `JringSyncEngine`'s once-per-connection backfill gate from being
     * consumed by a pass that never ran.
     */
    @Synchronized
    fun start(dayOffsets: List<Int>): Boolean {
        if (isRunning || dayOffsets.isEmpty()) return false
        queue = dayOffsets.toMutableList()
        advance()
        return true
    }

    /** Abandon any in-flight pass (disconnect / teardown). A fresh driver — and so a fresh pager —
     *  is built per connection, so this is only reached mid-connection. */
    @Synchronized
    fun cancel() {
        cancelTimers()
        currentDay = null
        queue.clear()
    }

    /**
     * Called by the driver for every inbound history frame, so the pager can tell a live stream
     * from a finished one. Frames for a leg that isn't in flight are ignored.
     *
     * The packet-length guard mirrors [RingPacket.fromData]: the jring wire contract is one fixed
     * 20-byte packet per notify, and anything else is already rejected by the decoder. A fragment
     * must not re-arm the settle window, or a leg stays "live" long past its stream.
     */
    @Synchronized
    fun noteFrame(data: ByteArray) {
        if (!isRunning || data.size != RingPacket.PACKET_SIZE) return
        when (data[0].toInt() and 0xFF) {
            OPCODE_ACTIVITY, OPCODE_SLEEP -> if (leg == Leg.ACTIVITY) restartSettle()
            OPCODE_HEART_RATE -> {
                if (leg != Leg.HEART_RATE) return
                // The one genuine end-of-stream signal in this protocol — take it rather than
                // waiting out the settle window.
                if (data.size >= 2 && (data[1].toInt() and 0xFF) == HR_SYNC_FINISHED) advance()
                else restartSettle()
            }
        }
    }

    // MARK: - Driving the queue

    /** Move to the next day, or finish. Always entered with the lock held. */
    private fun advance() {
        cancelTimers()
        if (queue.isEmpty()) {
            currentDay = null
            return
        }
        val next = queue.removeAt(0)
        currentDay = next
        leg = Leg.ACTIVITY
        writer?.enqueue(encoder.makeHistoryQueryCommand(dayOffset = next))
        armStall()
    }

    /** End the activity/sleep leg and ask for the same day's heart rate, as the vendor does
     *  (`getDataByDay(2, day)`). Always entered with the lock held. */
    private fun beginHeartRateLeg() {
        val day = currentDay ?: return
        cancelTimers()
        leg = Leg.HEART_RATE
        writer?.enqueue(encoder.makeHistoryMeasurementQueryCommand(dayOffset = day))
        armStall()
    }

    /** A leg's stream went quiet: hand over to the next leg, or the next day. */
    private fun legSettled() {
        when (leg) {
            Leg.ACTIVITY -> beginHeartRateLeg()
            Leg.HEART_RATE -> advance()
        }
    }

    // MARK: - Timers

    /** Data arrived: the stall no longer applies, and the quiet period starts over. */
    private fun restartSettle() {
        cancelTimers()
        settleJob = armTimer(settleMs)
    }

    /** Nothing has answered this leg yet. Fires once, then moves on. */
    private fun armStall() {
        stallJob = armTimer(stallMs)
    }

    /**
     * A timer that can't act on a leg it no longer belongs to.
     *
     * `Job.cancel()` doesn't retract a coroutine that has already left its `delay` and is waiting
     * on this monitor, so the epoch — bumped by every [cancelTimers] — is what actually decides
     * whether a fired timer is still current. Without it a settle that lost the race to an
     * arriving frame would advance the day out from under a live stream: the truncation this
     * class exists to prevent, reintroduced from the inside.
     */
    private fun armTimer(delayMs: Long): Job {
        val firedFor = epoch
        return scope.launch {
            delay(delayMs)
            synchronized(this@JringHistorySync) {
                if (epoch == firedFor) legSettled()
            }
        }
    }

    private fun cancelTimers() {
        epoch++
        settleJob?.cancel(); settleJob = null
        stallJob?.cancel(); stallJob = null
    }

    companion object {
        private const val OPCODE_ACTIVITY = 0x10
        private const val OPCODE_SLEEP = 0x11
        private const val OPCODE_HEART_RATE = 0x16
        /** `0x16` subtype `0xFF` — "sync finished" (`RingDecoder.decodeHeartRateHistory`). */
        private const val HR_SYNC_FINISHED = 0xFF
    }
}

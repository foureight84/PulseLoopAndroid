package com.pulseloop.ring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ported from LuckRingHistorySync.swift (iOS #90).
 *
 * The LuckRing history pager. Unlike [YCBTHistoryTransfer] (whose terminal block ends each type), the
 * K6 history streams carry **no cursor and no end-of-transfer marker** -- the ring just replays every
 * stored record of a type as one or more device-initiated data frames. So this is a simpler,
 * *time-settled* sequential pager: request a type, advance when its data frames stop arriving (a short
 * settle window), or skip it if none arrive at all (a stall timeout -- an unsupported type answers
 * with nothing).
 *
 * Replays are safe: persistence upserts history by `(kind, timestamp)`, activity by bucket timestamp,
 * and sleep by night, so re-requesting a type it already saw never double-counts. The destructive
 * `cleanData` (207) opcode is deliberately never sent.
 */
class LuckRingHistorySync(
    private val writer: RingCommandWriter?,
    private val settleMs: Long = 1_500,
    private val stallMs: Long = 6_000,
    /** `null` publishes to the shared bus (the production path); tests inject a spy. */
    private val progressSink: ((PulseEvent) -> Unit)? = null,
) {
    companion object {
        /** The full catalog, in request order. `mixSport` (10 -- workout records) is skipped in v1. */
        val catalog: List<UByte> = listOf(
            LuckRingDataType.HISTORY_SPORT,   // 5
            LuckRingDataType.SLEEP,           // 6
            LuckRingDataType.HISTORY_HEART,   // 8
            LuckRingDataType.HISTORY_O2,      // 40
            LuckRingDataType.HISTORY_BP,      // 41
            LuckRingDataType.HISTORY_HRV,     // 42
            LuckRingDataType.HISTORY_TEMP,    // 47
            LuckRingDataType.STRESS_HISTORY,  // 53
        )

        /** The post-workout backfill subset -- only the logs a session can have added to. */
        val vitalsTypes: List<UByte> = listOf(LuckRingDataType.HISTORY_HEART, LuckRingDataType.HISTORY_O2)

        private fun label(dataType: UByte): String = when (dataType) {
            LuckRingDataType.HISTORY_SPORT -> "activity"
            LuckRingDataType.SLEEP -> "sleep"
            LuckRingDataType.HISTORY_HEART -> "heart rate"
            LuckRingDataType.HISTORY_O2 -> "blood oxygen"
            LuckRingDataType.HISTORY_BP -> "blood pressure"
            LuckRingDataType.HISTORY_HRV -> "HRV"
            LuckRingDataType.HISTORY_TEMP -> "temperature"
            LuckRingDataType.STRESS_HISTORY -> "stress"
            else -> "history"
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var queue = mutableListOf<UByte>()
    private var currentType: UByte? = null
    private var seq: UByte = 0u
    private var settleJob: Job? = null
    private var stallJob: Job? = null

    val isRunning: Boolean get() = currentType != null

    private fun publish(event: PulseEvent) {
        if (progressSink != null) progressSink.invoke(event) else PulseEventBus.publishBlocking(event)
    }

    /** Seed the queue and request the first type. A pass already in flight wins -- a re-entrant
     *  `start` would abandon the in-flight type mid-stream and land its frames in the wrong bucket. */
    fun start(types: List<UByte>) {
        if (isRunning) return
        queue = types.toMutableList()
        advance()
    }

    /** Abandon any in-flight pass (disconnect / teardown). A fresh instance is built per connection
     *  (see [LuckRingDriver]), so this is only reached mid-connection. */
    fun cancel() {
        cancelTimers()
        currentType = null
        queue.clear()
    }

    /** Called by the driver for every completed device-initiated data frame. A frame for the
     *  in-flight type re-arms the settle window; anything else is ignored. */
    fun noteReceived(dataType: UByte) {
        if (currentType != dataType) return
        stallJob?.cancel(); stallJob = null
        armSettle()
    }

    // MARK: - Driving the queue

    private fun advance() {
        cancelTimers()
        if (queue.isEmpty()) {
            currentType = null
            publish(PulseEvent.SyncProgress("done"))
            return
        }
        val type = queue.removeAt(0)
        currentType = type
        publish(PulseEvent.SyncProgress("Syncing ${label(type)}..."))
        sendRequest(type)
        armStall()
    }

    private fun sendRequest(dataType: UByte) {
        val frame = LuckRingFrame(LuckRingCmdType.REQUEST, dataType, emptyList(), seq)
        seq = (seq + 1u).toUByte()
        for (packet in LuckRingPacketizer.packets(frame)) {
            writer?.enqueue(packet)
        }
    }

    private fun armSettle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(settleMs)
            advance()
        }
    }

    private fun armStall() {
        stallJob?.cancel()
        stallJob = scope.launch {
            delay(stallMs)
            advance()   // no data ever arrived for this type -- skip it
        }
    }

    private fun cancelTimers() {
        settleJob?.cancel(); settleJob = null
        stallJob?.cancel(); stallJob = null
    }
}

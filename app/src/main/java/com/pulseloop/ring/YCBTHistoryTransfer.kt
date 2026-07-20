package com.pulseloop.ring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ported from YCBTHistoryTransfer.swift (iOS #82).
 * The YCBT history state machine — protocol-driven, not timer-driven.
 *
 * Per type the ring answers a `05 <queryKey>` request with:
 *   header   `05 <queryKey>`  payload >= 10 -> `[recordCount:u16][totalPackets:u32][totalBytes:u32]`
 *                             payload <=  9 -> nothing stored for this type
 *   data     `05 <ackKey>`    N frames whose payloads **concatenate** into one buffer
 *   terminal `05 80`          `[totalPackets:u16][totalBytes:u16][crc16:u16]` over that buffer
 *
 * and then **waits for an ACK** (`05 80 {00}` accepted / `{04}` CRC failure) before releasing the
 * next type. The ring does not release the next type until it arrives, so we ACK before we parse
 * — a slow decode can't stall the ring.
 *
 * **Completion is the ring's terminal block, never a timer.** The watchdog below is a *safety net
 * only*: it never ACKs (an ACK without a verified terminal block claims data we don't hold) and is
 * never a completion signal — it just abandons a type the ring has gone silent on.
 */
class YCBTHistoryTransfer(
    private val writer: RingCommandWriter?,
    private val inactivityMs: Long = 10_000,
    private val absoluteCapMs: Long = 30_000,
) {
    private sealed class State {
        object Idle : State()
        /** Query written; waiting for the header (or a "no data" / error reply). */
        data class RequestSent(val historyType: YCBTHistoryType) : State()
        /** Header seen; accumulating data frames until the terminal block. */
        data class Receiving(val historyType: YCBTHistoryType) : State()

        val type: YCBTHistoryType? get() = when (this) {
            is Idle -> null
            is RequestSent -> historyType
            is Receiving -> historyType
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var state: State = State.Idle
    private val queue = mutableListOf<YCBTHistoryType>()
    private var buffer = mutableListOf<UByte>()
    /** A CRC mismatch buys the type exactly one re-request; a second failure gives up on it. */
    private var retriedCurrentType = false
    /** Types the firmware answered `0xFB`/`0xFC` for — never asked again this session. */
    private val unsupported = mutableSetOf<UByte>()

    private val defaultBufferCap = 64 * 1024
    private var bufferCap = defaultBufferCap

    // MARK: - Driving the queue

    /** True while a type is being requested or received. */
    val isActive: Boolean get() = state != State.Idle

    /**
     * Seed the queue and request the first type. Types the ring already rejected this session are
     * skipped.
     *
     * **A transfer already in flight wins.** There are multiple callers (the connect handshake,
     * the post-workout vitals backfill, the periodic pass), and a second `start` would abandon the
     * in-flight type mid-dump: the ring keeps streaming its data frames regardless, so they would
     * land in the *new* type's buffer and fail its terminal CRC.
     */
    fun start(types: List<YCBTHistoryType>) {
        if (isActive) return
        queue.clear()
        queue.addAll(types.filter { !unsupported.contains(it.queryKey) })
        publishOutOfBand(advance())
    }

    /** Abandon any in-flight transfer (disconnect / teardown). */
    fun cancel() {
        cancelWatchdog()
        state = State.Idle
        queue.clear()
        buffer.clear()
    }

    /** Request the next type, or report completion when the queue drains. */
    private fun advance(): List<RingDecodedEvent> {
        cancelWatchdog()
        buffer = mutableListOf()
        bufferCap = defaultBufferCap
        retriedCurrentType = false
        if (queue.isEmpty()) {
            state = State.Idle
            return listOf(RingDecodedEvent.HistorySyncFinished)
        }
        sendQuery(queue.removeAt(0))
        return emptyList()
    }

    /** Write `05 <queryKey>` and arm the stall watchdog. Also used for the single CRC retry. */
    private fun sendQuery(type: YCBTHistoryType) {
        state = State.RequestSent(type)
        typeDeadlineAtMs = System.currentTimeMillis() + absoluteCapMs
        writer?.enqueue(YCBTHealthCommand.historyRequest(type).toRawByteArray())
        armWatchdog(type)
    }

    // MARK: - Inbound

    /** Feed every validated Health-group (`type == 0x05`) frame here. */
    fun handle(cmd: UByte, payload: List<UByte>): List<RingDecodedEvent> {
        val type = state.type ?: return emptyList()

        // A 1-byte 0xFB..0xFF payload is a rejection, not data.
        val error = YCBTFrameError.detect(payload)
        if (error != null) {
            if (error.isPermanent) unsupported.add(type.queryKey)
            return advance()
        }

        return when (cmd) {
            type.queryKey -> handleHeader(type, payload)
            type.ackKey -> {
                appendData(payload)
                armWatchdog(type)
                emptyList()
            }
            YCBTHealth.TERMINAL_BLOCK -> handleTerminal(type, payload)
            else -> emptyList()   // a frame for some other type — not ours to interpret
        }
    }

    /** Header: `[recordCount:u16][totalPackets:u32][totalBytes:u32]`. <= 9 bytes = "no stored data". */
    private fun handleHeader(type: YCBTHistoryType, payload: List<UByte>): List<RingDecodedEvent> {
        if (payload.size < YCBTHealth.HEADER_PAYLOAD_LENGTH) return advance()
        val totalBytes = YCBTBytes.u32(payload, 6)
        buffer = mutableListOf()
        bufferCap = maxOf(totalBytes.toInt(), defaultBufferCap)
        state = State.Receiving(type)
        armWatchdog(type)
        return listOf(RingDecodedEvent.HistorySyncProgress("Syncing ${type.label}..."))
    }

    /** Data frames concatenate. Accepted even if the header was missed — the terminal CRC is the
     *  real integrity check, and it will fail us into the retry path rather than persisting a
     *  misaligned buffer. */
    private fun appendData(payload: List<UByte>) {
        if (buffer.size + payload.size > bufferCap) return
        buffer.addAll(payload)
    }

    /** Terminal: verify the CRC16 over everything accumulated, ACK, then decode. Order matters —
     *  we ACK first, and the ring gates the next type on it. */
    private fun handleTerminal(type: YCBTHistoryType, payload: List<UByte>): List<RingDecodedEvent> {
        if (state is State.RequestSent && buffer.isEmpty()) return emptyList()
        if (payload.size < YCBTHealth.TERMINAL_PAYLOAD_LENGTH) return advance()
        val expected = YCBTBytes.u16(payload, 4)
        val matches = YCBTFrame.crc16(buffer) == expected

        writer?.enqueue(
            YCBTHealthCommand.historyBlockAck(if (matches) YCBTHealth.ACK_ACCEPTED else YCBTHealth.ACK_CRC_FAILURE).toRawByteArray()
        )

        if (!matches) return retryOrSkip(type)
        return YCBTHealthRecords.decode(buffer, type) + advance()
    }

    /** One re-request per type on a corrupt transfer; if that also fails, drop the type. */
    private fun retryOrSkip(type: YCBTHistoryType): List<RingDecodedEvent> {
        if (retriedCurrentType) return advance()
        retriedCurrentType = true
        buffer = mutableListOf()
        sendQuery(type)
        return emptyList()
    }

    // MARK: - Stall watchdog (safety net)

    private var watchdogJob: Job? = null
    private var typeDeadlineAtMs: Long? = null

    /** Fires only on silence: the type is declared stalled and skipped. Must never ACK and must
     *  never stand in for completion. */
    private fun armWatchdog(type: YCBTHistoryType) {
        watchdogJob?.cancel()
        val deadline = typeDeadlineAtMs ?: (System.currentTimeMillis() + absoluteCapMs)
        val fireAt = minOf(System.currentTimeMillis() + inactivityMs, deadline)
        val delayMs = maxOf(0, fireAt - System.currentTimeMillis())
        watchdogJob = scope.launch {
            delay(delayMs)
            if (state.type == type) {
                publishOutOfBand(advance())
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        typeDeadlineAtMs = null
    }

    /** `handle` returns its events to the driver, which publishes them. `start` and the watchdog
     *  have no such return channel, so the one event they can produce — completion — is published
     *  here. */
    private fun publishOutOfBand(events: List<RingDecodedEvent>) {
        if (events.none { it is RingDecodedEvent.HistorySyncFinished }) return
        PulseEventBus.publishBlocking(PulseEvent.SyncProgress("done"))
    }
}

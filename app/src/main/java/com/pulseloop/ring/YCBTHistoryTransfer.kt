package com.pulseloop.ring

import kotlinx.coroutines.*
import java.time.Instant

/**
 * Ported from YCBTHistoryTransfer.swift.
 * The YCBT history state machine — protocol-driven, not timer-driven.
 */

class YCBTHistoryTransfer(
    private val writer: RingCommandWriter?,
    private val inactivitySeconds: Double = 10.0,
    private val absoluteCapSeconds: Double = 30.0,
    private val onOutOfBandEvents: (List<RingDecodedEvent>) -> Unit = { events ->
        for (event in events) {
            for (pulseEvent in RingEventBridge.eventsFor(event)) {
                PulseEventBus.publishBlocking(pulseEvent)
            }
        }
    },
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private enum class State {
        IDLE, REQUEST_SENT, RECEIVING
    }

    private var state = State.IDLE
    private var currentType: YCBTHistoryType? = null
    private var queue: MutableList<YCBTHistoryType> = mutableListOf()
    private var buffer: ByteArray = ByteArray(0)
    private var retriedCurrentType = false
    private var unsupported: MutableSet<Int> = mutableSetOf()
    private var bufferCap = DEFAULT_BUFFER_CAP
    private var expectedPackets: Int? = null
    private var expectedBytes: Int? = null
    private var watchdogJob: Job? = null
    private var typeDeadline: Long? = null

    companion object {
        private const val DEFAULT_BUFFER_CAP = 64 * 1024
        private const val MAX_BUFFER_CAP = 512 * 1024
    }

    @get:Synchronized
    val isActive: Boolean get() = state != State.IDLE

    @Synchronized
    fun start(types: List<YCBTHistoryType>) {
        if (isActive) return
        queue = types.filter { !unsupported.contains(it.queryKey) }.toMutableList()
        advance()
    }

    /** Add newly discovered capability-gated types without disturbing an active block. */
    @Synchronized
    fun append(types: List<YCBTHistoryType>) {
        val additions = types.distinct().filter { type ->
            !unsupported.contains(type.queryKey) && type != currentType && type !in queue
        }
        if (additions.isEmpty()) return
        if (state == State.IDLE) {
            queue = additions.toMutableList()
            advance()
        } else {
            queue.addAll(additions)
        }
    }

    @Synchronized
    fun cancel() {
        cancelWatchdog()
        state = State.IDLE
        currentType = null
        queue.clear()
        buffer = ByteArray(0)
    }

    private fun advance(): List<RingDecodedEvent> {
        cancelWatchdog()
        buffer = ByteArray(0)
        expectedPackets = null
        expectedBytes = null
        bufferCap = DEFAULT_BUFFER_CAP
        retriedCurrentType = false
        if (queue.isEmpty()) {
            state = State.IDLE
            currentType = null
            return listOf(RingDecodedEvent.HistorySyncFinished)
        }
        val next = queue.removeAt(0)
        sendQuery(next)
        return emptyList()
    }

    private fun sendQuery(type: YCBTHistoryType) {
        state = State.REQUEST_SENT
        currentType = type
        typeDeadline = System.currentTimeMillis() + (absoluteCapSeconds * 1000).toLong()
        writer?.enqueue(YCBTHealthCommand.historyRequest(type))
        armWatchdog(type)
    }

    /** Feed every validated Health-group (type == 0x05) frame here. */
    @Synchronized
    fun handle(cmd: Int, payload: ByteArray): List<RingDecodedEvent> {
        if (state == State.IDLE) return emptyList()
        val type = currentType ?: return emptyList()

        YCBTFrameError.detect(payload)?.let { error ->
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
            else -> emptyList()
        }
    }

    private fun handleHeader(type: YCBTHistoryType, payload: ByteArray): List<RingDecodedEvent> {
        if (payload.size < YCBTHealth.HEADER_PAYLOAD_LENGTH) return advance()
        expectedPackets = YCBTBytes.u16(payload, 2)
        val totalBytes = YCBTBytes.u32(payload, 6)
        expectedBytes = totalBytes
        buffer = ByteArray(0)
        bufferCap = totalBytes.coerceIn(0, MAX_BUFFER_CAP)
        state = State.RECEIVING
        armWatchdog(type)
        return listOf(RingDecodedEvent.HistorySyncProgress(stage = "Syncing ${type.label}…"))
    }

    private fun appendData(payload: ByteArray) {
        if (buffer.size + payload.size <= bufferCap) {
            buffer += payload
        }
    }

    private fun handleTerminal(type: YCBTHistoryType, payload: ByteArray): List<RingDecodedEvent> {
        if (state == State.REQUEST_SENT && buffer.isEmpty()) return emptyList()
        if (payload.size < YCBTHealth.TERMINAL_PAYLOAD_LENGTH) return advance()
        val packets = YCBTBytes.u16(payload, 0)
        val bytes = YCBTBytes.u16(payload, 2)
        val terminalMatchesHeader = packets == expectedPackets && bytes == expectedBytes
        val terminalMatchesBuffer = bytes == buffer.size
        if (!terminalMatchesHeader || !terminalMatchesBuffer) {
            writer?.enqueue(YCBTHealthCommand.historyBlockAck(status = YCBTHealth.ACK_CRC_FAILURE))
            return retryOrSkip(type)
        }
        val expected = YCBTBytes.u16(payload, 4)
        val matches = YCBTFrame.crc16(buffer) == expected

        writer?.enqueue(YCBTHealthCommand.historyBlockAck(status = if (matches) YCBTHealth.ACK_ACCEPTED else YCBTHealth.ACK_CRC_FAILURE))

        if (!matches) return retryOrSkip(type)
        return YCBTHealthRecords.decode(buffer, type) + advance()
    }

    private fun retryOrSkip(type: YCBTHistoryType): List<RingDecodedEvent> {
        if (retriedCurrentType) return advance()
        retriedCurrentType = true
        buffer = ByteArray(0)
        sendQuery(type)
        return emptyList()
    }

    // MARK: Stall watchdog (safety net)

    private fun armWatchdog(type: YCBTHistoryType) {
        watchdogJob?.cancel()
        val deadline = typeDeadline ?: (System.currentTimeMillis() + (absoluteCapSeconds * 1000).toLong())
        val fireAt = minOf(System.currentTimeMillis() + (inactivitySeconds * 1000).toLong(), deadline)
        val delayMs = maxOf(0, fireAt - System.currentTimeMillis())
        watchdogJob = scope.launch {
            delay(delayMs)
            watchdogFired(type)
        }
    }

    @Synchronized
    private fun watchdogFired(type: YCBTHistoryType) {
        if (state == State.IDLE || currentType != type) return
        publishOutOfBand(advance())
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        typeDeadline = null
    }

    private fun publishOutOfBand(events: List<RingDecodedEvent>) {
        onOutOfBandEvents(events)
    }
}

package com.pulseloop.ring

/**
 * Ported from YCBTDriver.swift.
 * YCBT driver. Owns the length-prefixed CRC16 framing and the split-channel topology.
 */

class YCBTDriver(private val writer: RingCommandWriter) : WearableDriver {
    private val decoder = YCBTDecoder()
    private val assembler = YCBTFrameAssembler()
    private val transfer: YCBTHistoryTransfer = YCBTHistoryTransfer(writer = writer)

    /** Queue of pending measurement-reply modes: the mode for a start, null for a stop. */
    private val pendingMeasurementReplies = ArrayDeque<Int?>(8)

    companion object {
        private const val MAX_PENDING_MEASUREMENT_REPLIES = 8
    }

    override val serviceUUIDs = listOf(YCBTUUIDs.SERVICE)
    override val writeUUID = YCBTUUIDs.COMMAND
    override val notifyUUIDs = listOf(YCBTUUIDs.COMMAND, YCBTUUIDs.STREAM)
    override val commandUUID = YCBTUUIDs.COMMAND
    override val batteryServiceUUID: String? = null
    override val batteryCharUUID: String? = null

    override fun frame(command: ByteArray): ByteArray {
        val logical = command
        noteLiveMeasurementCommand(logical)
        return YCBTFrame.frame(logical)
    }

    override fun usesCommandChannel(frame: ByteArray): Boolean = false

    private fun noteLiveMeasurementCommand(logical: ByteArray) {
        if (logical.size >= 4 &&
            (logical[0].toInt() and 0xFF) == YCBTGroup.APP_CONTROL &&
            (logical[1].toInt() and 0xFF) == YCBTCommand.LIVE_MEASUREMENT) {
            pendingMeasurementReplies.add(if (logical[2].toInt() and 0xFF == 1) logical[3].toInt() and 0xFF else null)
            if (pendingMeasurementReplies.size > MAX_PENDING_MEASUREMENT_REPLIES) {
                pendingMeasurementReplies.removeFirst()
            }
        }
    }

    fun connectionDidStart() {
        assembler.reset()
        transfer.cancel()
        pendingMeasurementReplies.clear()
    }

    fun connectionDidEnd() {
        assembler.reset()
        transfer.cancel()
        pendingMeasurementReplies.clear()
    }

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (logical in assembler.append(data, from)) {
            val frame = YCBTFrame.validating(logical) ?: run {
                events.add(RingDecodedEvent.Unknown(commandId = logical.firstOrNull()?.toUByte() ?: 0u, raw = logical))
                continue
            }
            when (frame.type) {
                YCBTGroup.HEALTH -> {
                    events.addAll(transfer.handle(cmd = frame.cmd, payload = frame.payload))
                }
                YCBTGroup.DEV_CONTROL -> {
                    acknowledgePush(frame)
                    events.addAll(decoder.decode(frame))
                }
                YCBTGroup.APP_CONTROL -> if (frame.cmd == YCBTCommand.LIVE_MEASUREMENT) {
                    val startedMode = if (pendingMeasurementReplies.isEmpty()) null else pendingMeasurementReplies.removeFirst()
                    events.addAll(decoder.decode(frame, startedMode = startedMode))
                } else {
                    events.addAll(decoder.decode(frame))
                }
                else -> events.addAll(decoder.decode(frame))
            }
        }
        return events
    }

    private fun acknowledgePush(frame: YCBTFrame) {
        if (YCBTFrameError.detect(frame.payload) != null) return
        writer.enqueue(YCBTDevControl.ack(key = frame.cmd))
    }

    override fun makeSyncEngine(): RingSyncEngine {
        return YCBTSyncEngine(writer = writer, transfer = transfer)
    }
}

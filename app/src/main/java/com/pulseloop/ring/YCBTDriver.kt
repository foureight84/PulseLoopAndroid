package com.pulseloop.ring

/**
 * Ported from YCBTDriver.swift.
 * YCBT driver. Owns the length-prefixed CRC16 framing and the split-channel topology.
 */

class YCBTDriver(private val writer: RingCommandWriter) : WearableDriver {
    private val decoder = YCBTDecoder()
    private val encoder = YCBTEncoder()
    private val assembler = YCBTFrameAssembler()
    private val transfer: YCBTHistoryTransfer = YCBTHistoryTransfer(writer = writer)
    private val pendingMeasurementReplies = PendingMeasurementReplies()

    override val serviceUUIDs = listOf(YCBTUUIDs.SERVICE)
    override val writeUUID = YCBTUUIDs.COMMAND
    override val notifyUUIDs = listOf(YCBTUUIDs.COMMAND, YCBTUUIDs.STREAM)
    override val commandUUID = YCBTUUIDs.COMMAND
    override val batteryServiceUUID: String? = null
    override val batteryCharUUID: String? = null
    override val requiredSubscriptionsBeforeConnected = listOf(
        RequiredSubscription(YCBTUUIDs.COMMAND, SubscriptionMode.INDICATION),
        RequiredSubscription(YCBTUUIDs.STREAM, SubscriptionMode.INDICATION),
    )

    override fun immediatePostSubscriptionCommands(): List<ByteArray> =
        encoder.postSubscriptionHandshake()

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
            pendingMeasurementReplies.record(
                if (logical[2].toInt() and 0xFF == 1) logical[3].toInt() and 0xFF else null
            )
        }
    }

    override fun connectionDidStart() {
        assembler.reset()
        transfer.cancel()
        pendingMeasurementReplies.clear()
    }

    override fun connectionDidEnd() {
        assembler.reset()
        transfer.cancel()
        pendingMeasurementReplies.clear()
    }

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (logical in assembler.append(data, from)) {
            val frame = YCBTFrame.validating(logical)
            if (frame == null) {
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
                    val startedMode = pendingMeasurementReplies.consume()?.startedMode
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

/** GATT writes and replies arrive on different threads; keep FIFO pairing atomic. */
internal class PendingMeasurementReplies {
    data class Reply(val startedMode: Int?)

    private val replies = ArrayDeque<Reply>()

    @Synchronized
    fun record(startedMode: Int?) {
        replies.addLast(Reply(startedMode))
        if (replies.size > MAX_PENDING) replies.removeFirst()
    }

    @Synchronized
    fun consume(): Reply? = if (replies.isEmpty()) null else replies.removeFirst()

    @Synchronized
    fun clear() {
        replies.clear()
    }

    private companion object {
        const val MAX_PENDING = 8
    }
}

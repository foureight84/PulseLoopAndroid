package com.pulseloop.ring

/**
 * Ported from YCBTDriver.swift (iOS #82).
 * YCBT driver. Owns the length-prefixed CRC16 framing and the split-channel topology: the command
 * characteristic `be940001` is *both* the write target and a notify source (command replies),
 * while `be940003` carries the async live/history stream. The standard `180D`/`2A37` Heart Rate
 * characteristic is deliberately left unsubscribed: on the TK5 it emits a cached resting HR
 * periodically even when the ring is off the finger (observed ~87 bpm), which would override a
 * real on-demand measurement. The vendor app never subscribes it either — live HR comes solely
 * from the proprietary `06 01` stream, which reflects actual finger contact.
 *
 * A fresh driver is built per connection ([RingBLEClient.installDriver] calls
 * `coordinator.makeDriver` on every connect), so no explicit reconnect-reset hook is needed — the
 * assembler/transfer/pending-reply state below all start clean.
 */
class YCBTDriver(private val writer: RingCommandWriter?) : WearableDriver {
    private val decoder = YCBTDecoder
    /** GATT fragments -> whole logical frames. A history data frame regularly exceeds `MTU-3` and
     *  is split across notifications. */
    private val assembler = YCBTFrameAssembler()
    /** The history state machine. Owned here because only the driver sees frames (the sync engine
     *  sees decoded events); handed to the engine so `runStartup` can seed the queue. */
    private val transfer = YCBTHistoryTransfer(writer)

    /**
     * The `03 2f` commands still owed a reply, oldest first — the mode for a **start**, `null` for
     * a **stop** (a rejected stop cancels nothing, so it names no measurement).
     *
     * The ring answers a live-measurement command with a bare status byte and **no mode**, so a
     * refusal is anonymous on the wire. This driver is the one place that sees both directions —
     * [frame] for every outbound command, [ingest] for every inbound frame — so it is the only
     * place that can pair the two up.
     *
     * It has to be a **queue**, not one slot: framing happens when a command is *enqueued* (on the
     * way into the serialized write queue), not when it reaches the wire, and every spot
     * measurement ends with a stop immediately followed by a restart during a workout — both still
     * owed replies. One serialized write queue and one ring means replies come back in the order
     * the commands went out, so FIFO pairing is exact.
     */
    private val pendingMeasurementReplies = ArrayDeque<UByte?>()

    /** A ring that stops answering `03 2f` must not grow the queue without bound. */
    private val maxPendingMeasurementReplies = 8

    // MARK: BLE topology

    override val serviceUUIDs: List<String> = listOf(YCBTUUIDs.SERVICE)
    override val writeUUID: String = YCBTUUIDs.COMMAND
    override val notifyUUIDs: List<String> = listOf(YCBTUUIDs.COMMAND, YCBTUUIDs.STREAM)
    override val batteryServiceUUID: String? = null   // battery is in-band (GetDeviceInfo 02 00, payload[5])
    override val batteryCharUUID: String? = null

    // MARK: Framing

    override fun frame(command: ByteArray): ByteArray {
        // Every outbound command passes through here exactly once, which is what makes this the
        // seam that can watch for live-measurement commands (see pendingMeasurementReplies).
        val logical = command.map { it.toUByte() }
        noteLiveMeasurementCommand(logical)
        return YCBTFrame.frame(logical)
    }

    /**
     * Queue one entry per outbound `03 2f {enable, mode}`: the mode for a start, `null` for a
     * stop. A stop is queued too, and that is the point — its reply is byte-for-byte
     * indistinguishable from a start's, so a stop we didn't queue would have its reply consumed by
     * the next start in line.
     */
    private fun noteLiveMeasurementCommand(logical: List<UByte>) {
        if (logical.size < 4 || logical[0] != YCBTGroup.APP_CONTROL || logical[1] != YCBTCommand.LIVE_MEASUREMENT) return
        pendingMeasurementReplies.addLast(if (logical[2] == 1u.toUByte()) logical[3] else null)
        if (pendingMeasurementReplies.size > maxPendingMeasurementReplies) {
            pendingMeasurementReplies.removeFirst()
        }
    }

    // MARK: Inbound decode

    /**
     * Every notification goes through the assembler first. Health-group frames drive the history
     * transfer; DevControl pushes must be acknowledged; everything else is a stateless decode.
     */
    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (logical in assembler.append(data, from)) {
            val frame = YCBTFrame.validating(logical)
            if (frame == null) {
                events.add(RingDecodedEvent.Unknown(logical.firstOrNull()?.toUByte() ?: 0u, logical))
                continue
            }
            when {
                frame.type == YCBTGroup.HEALTH ->
                    events.addAll(transfer.handle(frame.cmd, frame.payload))
                frame.type == YCBTGroup.DEV_CONTROL -> {
                    acknowledgePush(frame)
                    events.addAll(decoder.decode(frame))
                }
                frame.type == YCBTGroup.APP_CONTROL && frame.cmd == YCBTCommand.LIVE_MEASUREMENT -> {
                    // The verdict on the *oldest* 03 2f still owed one.
                    val startedMode = if (pendingMeasurementReplies.isEmpty()) null else pendingMeasurementReplies.removeFirst()
                    events.addAll(decoder.decode(frame, startedMode = startedMode))
                }
                else -> events.addAll(decoder.decode(frame))
            }
        }
        return events
    }

    /**
     * The ring **retransmits an unacknowledged DevControl push** until the app answers
     * `04 <key> {00}`, so the ACK goes out before the frame is even decoded.
     *
     * A 1-byte `0xFB..0xFF` payload is an *error* frame, not a push — those are dropped without a
     * reply, since ACKing one would answer a rejection as though it were a push the ring never sent.
     */
    private fun acknowledgePush(frame: YCBTFrame) {
        if (YCBTFrameError.detect(frame.payload) != null) return
        writer?.enqueue(YCBTDevControl.ack(frame.cmd).toRawByteArray())
    }

    override fun makeSyncEngine(): RingSyncEngine = YCBTSyncEngine(writer, transfer)
}

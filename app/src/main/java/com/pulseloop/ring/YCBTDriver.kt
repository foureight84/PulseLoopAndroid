package com.pulseloop.ring

/**
 * Ported from YCBTDriver.swift.
 * YCBT driver. Owns the length-prefixed CRC16 framing and the split-channel topology.
 */

class YCBTDriver(private val writer: RingCommandWriter) : WearableDriver {
    private val decoder = YCBTDecoder()
    private val encoder = YCBTEncoder()
    private val assembler = YCBTFrameAssembler()
    private var syncEngine: RingSyncEngine? = null
    private val transfer: YCBTHistoryTransfer = YCBTHistoryTransfer(
        writer = writer,
        onOutOfBandEvents = ::handleOutOfBandEvents,
    )
    private val pendingMeasurementReplies = PendingMeasurementReplies()
    private var capabilities = YCBTCoordinator.capabilities

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
        capabilities = YCBTCoordinator.capabilities
    }

    override fun connectionDidEnd() {
        assembler.reset()
        transfer.cancel()
        pendingMeasurementReplies.clear()
        capabilities = YCBTCoordinator.capabilities
    }

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (logical in assembler.append(data, from)) {
            val frame = YCBTFrame.validating(logical)
            if (frame == null) {
                events.add(RingDecodedEvent.Unknown(commandId = logical.firstOrNull()?.toUByte() ?: 0u, raw = logical))
                continue
            }
            val decoded = when (frame.type) {
                YCBTGroup.HEALTH -> {
                    transfer.handle(cmd = frame.cmd, payload = frame.payload)
                }
                YCBTGroup.DEV_CONTROL -> {
                    acknowledgePush(frame)
                    decoder.decode(frame).also { decoded ->
                        // A real measurement value proves a start succeeded even if its command
                        // reply was lost. Drop stale FIFO correlation before the next command.
                        if (frame.cmd == YCBTDevControl.MEASUREMENT_STATUS &&
                            decoded.any { it !is RingDecodedEvent.CommandAck }) {
                            pendingMeasurementReplies.clear()
                        }
                    }
                }
                YCBTGroup.APP_CONTROL -> if (frame.cmd == YCBTCommand.LIVE_MEASUREMENT) {
                    val startedMode = pendingMeasurementReplies.consume()?.startedMode
                    decoder.decode(frame, startedMode = startedMode)
                } else {
                    decoder.decode(frame)
                }
                else -> decoder.decode(frame)
            }
            updateCapabilities(decoded)
            events.addAll(decoded.filter(::isSupported))
        }
        return events
    }

    private fun acknowledgePush(frame: YCBTFrame) {
        if (YCBTFrameError.detect(frame.payload) != null) return
        writer.enqueue(YCBTDevControl.ack(key = frame.cmd))
    }

    override fun makeSyncEngine(): RingSyncEngine {
        return YCBTSyncEngine(writer = writer, transfer = transfer).also { syncEngine = it }
    }

    private fun updateCapabilities(events: List<RingDecodedEvent>) {
        val support = events.filterIsInstance<RingDecodedEvent.SupportFunctions>().lastOrNull() ?: return
        capabilities = YCBTCoordinator.capabilities +
            support.capabilities.intersect(YCBTCoordinator.bitmapGatedCapabilities)
    }

    private fun isSupported(event: RingDecodedEvent): Boolean = when (event) {
        is RingDecodedEvent.BloodPressureSample -> WearableCapability.BLOOD_PRESSURE in capabilities
        is RingDecodedEvent.BloodSugarSample -> WearableCapability.BLOOD_SUGAR in capabilities
        is RingDecodedEvent.HrvSample -> WearableCapability.HRV in capabilities
        is RingDecodedEvent.StressSample -> WearableCapability.STRESS in capabilities
        is RingDecodedEvent.TemperatureSample -> WearableCapability.TEMPERATURE in capabilities
        is RingDecodedEvent.HistoryMeasurement -> when (event.kind_field) {
            MeasurementKind.HEART_RATE, MeasurementKind.SPO2 -> true
            MeasurementKind.BLOOD_PRESSURE_SYSTOLIC,
            MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> WearableCapability.BLOOD_PRESSURE in capabilities
            MeasurementKind.BLOOD_SUGAR -> WearableCapability.BLOOD_SUGAR in capabilities
            MeasurementKind.HRV -> WearableCapability.HRV in capabilities
            MeasurementKind.STRESS -> WearableCapability.STRESS in capabilities
            MeasurementKind.FATIGUE -> WearableCapability.FATIGUE in capabilities
            MeasurementKind.TEMPERATURE -> WearableCapability.TEMPERATURE in capabilities
            MeasurementKind.RESPIRATORY_RATE, MeasurementKind.VO2MAX -> false
        }
        else -> true
    }

    private fun handleOutOfBandEvents(events: List<RingDecodedEvent>) {
        for (event in events.filter(::isSupported)) {
            syncEngine?.handle(event)
            for (pulseEvent in RingEventBridge.eventsFor(event)) {
                PulseEventBus.publishBlocking(pulseEvent)
            }
        }
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

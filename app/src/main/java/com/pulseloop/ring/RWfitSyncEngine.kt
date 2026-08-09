package com.pulseloop.ring

import android.util.Log

/**
 * Drives the RWfit connect handshake and history sync.
 *
 * Mirrors the vendor's own order (`u1.java g()` → `blesdk/service/l.java`'s cascade): ask for device
 * info, set the clock, ask what history the ring is holding, then pull **only** the streams the
 * manifest claims — one at a time, each request fired when the previous stream's reply lands.
 *
 * The manifest gate matters. Every per-stream method in `l.java` opens with an
 * `isHasXData()` check and falls through to the next stream when it's false, so a ring with no
 * temperature sensor is never asked for temperature history.
 */
class RWfitSyncEngine(
    private val writer: RingCommandWriter?,
    private val encoder: RWfitEncoder,
) : RingSyncEngine {

    var framing: RWfitFraming = RWfitFraming.LEGACY

    /** Streams still to request this pass, in the vendor's cascade order. */
    private var pending = ArrayDeque<RWfitProtocol.HistoryType>()
    private var handshakeDone = false

    fun reset() {
        pending.clear()
        handshakeDone = false
    }

    private fun send(command: ByteArray?) {
        if (command == null) return
        writer?.enqueue(command)
    }

    // ── Startup ─────────────────────────────────────────────────────────────────

    /**
     * Also the ~30-minute background sync pass, so it stays lean: device info + clock + manifest.
     * The manifest reply is what starts the history cascade.
     */
    override fun runStartup() {
        send(encoder.deviceInfo())
        send(encoder.timeSync())
        send(encoder.battery())
        requestManifest()
    }

    private fun requestManifest() {
        if (framing != RWfitFraming.LEGACY) {
            // JieLi has no manifest command, and its 05-group history bodies aren't decodable yet
            // (see RWfitDriver.decodeJieLiFrame). Requesting them would spend the link on frames we
            // would only log — so the JieLi path is live/battery only until those layouts are read
            // out of the vendor app.
            Log.i(TAG, "JieLi link — history sync not enabled yet")
            return
        }
        send(encoder.syncManifest())
    }

    // ── Driver callbacks ────────────────────────────────────────────────────────

    fun onDeviceInfo() {
        handshakeDone = true
    }

    /** The device ACKed one of our commands. Status != 0 means it refused it. */
    fun onDeviceAck(cmd: Byte, status: Byte) {
        if (status.toInt() != 0) {
            Log.w(TAG, "ring rejected cmd 0x${"%02X".format(cmd)} with status $status")
        }
    }

    /** The ring told us what it's holding — queue exactly those streams and start the cascade. */
    fun onManifest(manifest: RWfitDecoder.SyncManifest) {
        pending = ArrayDeque(manifest.pendingStreams())
        Log.i(TAG, "manifest: ${manifest.totalDataCount} records, streams ${pending.toList()}")
        requestNextStream()
    }

    /** A stream's reply landed; move on to the next one. */
    fun onHistoryReply(type: RWfitProtocol.HistoryType) {
        pending.remove(type)
        requestNextStream()
    }

    private fun requestNextStream() {
        while (pending.isNotEmpty()) {
            val next = pending.first()
            val command = encoder.history(next)
            if (command == null) {
                // Not available on this framing — drop it rather than stalling the cascade.
                pending.removeFirst()
                continue
            }
            send(command)
            return
        }
    }

    // ── Live measurement ────────────────────────────────────────────────────────
    //
    // JieLi only: the vendor app has no legacy on-demand measurement command at all, so on a legacy
    // link these are genuinely unavailable rather than unimplemented. RWfitCoordinator keeps the
    // matching capabilities out of the baseline set so the UI never offers a button that could only
    // time out.

    override fun startHeartRate() {
        send(encoder.realtimeMeasure(RWfitProtocol.JLDataType.HEART_RATE, enable = true))
    }

    override fun stopHeartRate() {
        send(encoder.realtimeMeasure(RWfitProtocol.JLDataType.HEART_RATE, enable = false))
    }

    override fun startSpO2() {
        send(encoder.realtimeMeasure(RWfitProtocol.JLDataType.SPO2, enable = true))
    }

    override fun stopSpO2() {
        send(encoder.realtimeMeasure(RWfitProtocol.JLDataType.SPO2, enable = false))
    }

    override fun startBloodPressure() {
        send(encoder.realtimeMeasure(RWfitProtocol.JLDataType.BLOOD_PRESSURE, enable = true))
    }

    override fun stopBloodPressure() {
        send(encoder.realtimeMeasure(RWfitProtocol.JLDataType.BLOOD_PRESSURE, enable = false))
    }

    override fun startHRV() {
        send(encoder.realtimeMeasure(RWfitProtocol.JLDataType.HRV, enable = true))
    }

    override fun stopHRV() {
        send(encoder.realtimeMeasure(RWfitProtocol.JLDataType.HRV, enable = false))
    }

    override fun handle(event: RingDecodedEvent) {
        // The cascade is driven from RWfitDriver's decode path, which sees the raw command ids;
        // by the time an event reaches here the stream it came from is no longer identifiable.
    }

    /**
     * Unbind so the ring releases this phone (`h0.java n()`). Legacy only — the JieLi unbind triple
     * is unconfirmed, see [RWfitEncoder.unbind].
     */
    override fun factoryReset() {
        send(encoder.unbind())
    }

    // Not present in the vendor's command set for this family.
    override fun findDevice() {}
    override fun setGoal(steps: Int) {}
    override fun powerOff() {}

    private companion object {
        const val TAG = "RWfitSyncEngine"
    }
}

package com.pulseloop.ring

import android.util.Log

/**
 * Drives the RWfit connect handshake and history sync.
 *
 * **Legacy (`0x7E`)** mirrors the vendor's own order (`u1.java g()` → `blesdk/service/l.java`'s
 * cascade): ask for device info, set the clock, ask what history the ring is holding, then pull
 * **only** the streams the manifest claims — one at a time, each request fired when the previous
 * stream's reply lands. The manifest gate matters: every per-stream method in `l.java` opens with
 * an `isHasXData()` check and falls through to the next stream when it's false, so a ring with no
 * temperature sensor is never asked for temperature history.
 *
 * **JieLi (`0xAB`) has no manifest.** The vendor requests each `05`-group stream directly with a
 * **bare `{5, type, 0x10}` triple — no payload** (`blesdk/service/y.java:345-537`, one per stream;
 * the UI screens do the same, e.g. `TRingHeartRateStatisticsActivity.java:545`), and each reply is
 * a single (possibly multi-packet) frame holding **all** of that stream's records — the vendor's
 * per-type parsers (`x5/b.java`) just loop over the whole body. PulseLoop therefore fires the whole
 * ported catalog at once, once per connection, right after the handshake: no manifest, no cascade
 * (there is nothing to wait for — a type the ring has no records for simply answers with the bare
 * triple, which decodes to nothing). [runStartup] doubles as the ~30-minute background sync pass,
 * so the burst is gated by [jieliHistoryRequested] rather than re-firing on every pass — the
 * ring's buffer is re-sent in full each time, and persistence upserts it idempotently.
 */
class RWfitSyncEngine(
    private val writer: RingCommandWriter?,
    private val encoder: RWfitEncoder,
) : RingSyncEngine {

    var framing: RWfitFraming = RWfitFraming.LEGACY

    /** Streams still to request this pass, in the vendor's cascade order (legacy path). */
    private var pending = ArrayDeque<RWfitProtocol.HistoryType>()
    private var handshakeDone = false

    /**
     * JieLi history has been requested on this connection. The engine instance outlives a single
     * link (the driver builds it once and resets it in [connectionDidStart]/[connectionDidEnd],
     * which both call [reset]), so this flag — not a fresh-engine-per-connection trick — is what
     * makes the burst once-per-connection across [runStartup]'s foreground and background passes.
     */
    private var jieliHistoryRequested = false

    fun reset() {
        pending.clear()
        handshakeDone = false
        jieliHistoryRequested = false
    }

    private fun send(command: ByteArray?) {
        if (command == null) return
        writer?.enqueue(command)
    }

    // ── Startup ─────────────────────────────────────────────────────────────────

    /**
     * Also the ~30-minute background sync pass, so it stays lean: device info + clock + battery,
     * then the history entry point for the active framing — the manifest on legacy (whose reply
     * starts the cascade), the once-per-connection `05`-group burst on JieLi.
     */
    override fun runStartup() {
        send(encoder.deviceInfo())
        send(encoder.timeSync())
        send(encoder.battery())
        if (framing == RWfitFraming.LEGACY) {
            send(encoder.syncManifest())
        } else {
            requestJieliHistory()
        }
    }

    /**
     * The JieLi history burst: every `05`-group stream this port decodes, as a bare
     * `{5, type, 0x10}` triple with no payload — the exact request shape the vendor sends
     * (`y.java:345-537`, `TRingHeartRateStatisticsActivity.java:545`). [encoder.history] builds
     * it via [RWfitProtocol.JieLi.historySync]; it returns null for [RWfitProtocol.HistoryType]
     * without a JieLi type (breathe), which is dropped here for free.
     *
     * Fired **once per connection**: the burst is the connect backfill (the ring re-sends its
     * whole buffer for each stream, and persistence upserts idempotently), and [runStartup] is
     * also the ~30-minute background pass — re-firing would spend the link on frames whose data we
     * already hold. [reset] re-arms it for the next link.
     */
    private fun requestJieliHistory() {
        if (jieliHistoryRequested) return
        jieliHistoryRequested = true
        for (type in RWfitProtocol.HistoryType.entries) {
            send(encoder.history(type))
        }
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

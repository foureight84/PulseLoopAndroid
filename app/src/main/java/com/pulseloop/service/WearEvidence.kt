package com.pulseloop.service

/**
 * Whether the app has recent proof the ring is actually on a finger.
 *
 * The CRP ring pushes `group 3 / cmd 7 [00]` when a spot measure is about to come back empty. As an
 * *abort* signal that is reliable — in zaggash's 2026-07-25 capture it arrives 2 ms before the `0xFF`
 * no-reading sentinel, and every measure that saw one produced no reading while every measure that
 * didn't produced one. As a *wear* signal it is not: that ring never once reports `[01]` (32 pushes,
 * all `[00]`), several of them seconds after a good heart-rate reading.
 *
 * The reason is hardware, not firmware. Per COLMI's own R11 spec the ring carries two sensors — an
 * STK8321 accelerometer and a Vcare VC30F *heart rate* sensor — and no SpO2 hardware, so its SpO2
 * measure fails no matter how well the ring is worn. Telling someone to put on a ring they are
 * already wearing sends them to fix the one thing that isn't broken.
 *
 * So the "put the ring on" copy needs corroboration, and heart rate is the honest witness: a real
 * bpm can only be read off skin, making it the ring's one trustworthy proof of contact. This holds
 * the rule as a value type so it can be tested without a live BLE link — the same reason
 * [SpotMeasurementGate] and `HRSampleWindow` are separate types.
 */
data class WearEvidence(
    /** When the ring last returned a real bpm, or `null` if it never has this connection. */
    val lastHeartRateSampleAt: Long? = null,
) {
    /** Record a bpm arriving at [at]. */
    fun withHeartRateSample(at: Long): WearEvidence = copy(lastHeartRateSampleAt = at)

    /** Forget what we knew — a new connection has to earn its own proof. */
    fun cleared(): WearEvidence = WearEvidence()

    /**
     * True when a real bpm arrived recently enough to vouch for skin contact, so a failed measure
     * should NOT be blamed on how the ring is worn.
     *
     * The window only has to span one measurement plus the gap to the next: in the capture a good HR
     * reading and the SpO2 failure that followed it were 8 seconds apart. Beyond it we stop vouching,
     * so a ring genuinely taken off is reported as not-worn again within a couple of minutes.
     */
    fun provesWorn(now: Long): Boolean =
        lastHeartRateSampleAt?.let { now - it in 0..WEAR_PROOF_WINDOW_MS } == true

    companion object {
        const val WEAR_PROOF_WINDOW_MS = 120_000L
    }
}

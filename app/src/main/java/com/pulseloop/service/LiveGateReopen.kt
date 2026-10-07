package com.pulseloop.service

import com.pulseloop.ring.MeasurementKind

/**
 * Which kinds' live-sample gates a finished spot leg still owes a reopen, and when to pay it.
 *
 * A spot leg closes its kind's gate so the samples it settles from are not stored (issue #60).
 * Reopening it the moment the leg ended was too early: told to stop, a Colmi ring sends one more
 * `0x69` reading before its `0x6A` acknowledgement, so that trailing reading landed on an open
 * gate and was stored beside the settled one — two rows per measurement, a few hundred ms apart
 * (HR 85 + 86, SpO₂ 98 + 98 on a Ring 2 Pro; QRing's own capture shows the same frame order).
 *
 * So the leg arms a reopen instead, paid by whichever comes first: the ring's acknowledgement
 * ([onStreamStopped]) or a timeout ([onTimeout]) for one that never arrives. Only a stop the ring
 * will acknowledge waits at all (`RingSyncEngine.stopAwaitsAck`); every other stop pays the reopen
 * straight away, since a family that never acknowledges would otherwise drop ~2 s of a workout's
 * samples after every leg. A new leg on the
 * same kind takes ownership of the gate again ([disarm]), so a late acknowledgement or timeout
 * from the previous run cannot open it under the new one.
 */
class LiveGateReopen {
    private val pending = mutableMapOf<MeasurementKind, Long>()
    private var nextToken = 0L

    /** Arm a reopen for [kind]; returns the token its timeout must present. */
    @Synchronized
    fun arm(kind: MeasurementKind): Long {
        val token = ++nextToken
        pending[kind] = token
        return token
    }

    /** A leg on [kind] is starting and owns the gate again. */
    @Synchronized
    fun disarm(kind: MeasurementKind) {
        pending.remove(kind)
    }

    /** The ring finished streaming [kind]; true when the caller should reopen the gate now. */
    @Synchronized
    fun onStreamStopped(kind: MeasurementKind): Boolean = pending.remove(kind) != null

    /** The timeout armed with [token] fired; true when the caller should reopen the gate now. */
    @Synchronized
    fun onTimeout(kind: MeasurementKind, token: Long): Boolean {
        if (pending[kind] != token) return false
        pending.remove(kind)
        return true
    }

    companion object {
        /** Captured acknowledgements land 100–250 ms after the stop; this only bounds one that
         *  was expected and never came. */
        const val TIMEOUT_MS = 2_000L
    }
}

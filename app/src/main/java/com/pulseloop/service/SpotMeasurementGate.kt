package com.pulseloop.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Ported from SpotMeasurementGate in RingSyncCoordinator.swift (iOS `c8969a4`, riding the #82
 * sync): the fast-fail rule for a **refused** spot measurement, extended for issue #59 to carry
 * the ring's own end-of-measurement verdict as well.
 *
 * A YCBT ring answers `03 2f` with a verdict byte, and it refuses modes it has no sensor for
 * (the R99 refuses HRV `0x0a`). Without this gate the coordinator would poll a ring that already
 * said no for the full measurement window before reporting a generic failure. The same ring also
 * *ends* a measurement itself with `04 0e` once its PPG has converged (issue #59), which is the
 * other half of the same problem: without it the leg idles out its whole window after the ring
 * has already gone quiet, then reports the reading was never steady.
 *
 * The danger in aborting on a device-pushed signal is aborting the *wrong* thing, so ownership
 * is by token, not by mode: a refusal or completion may only ever end the measurement it names,
 * while that measurement is actually running. Tokens matter because spot measurements really do
 * overlap — the workout poll service fires on its own timer while the user (or the coach's action
 * tools) can start another reading, and nothing serializes those flows against each other.
 *
 * Those flows also run on different threads: the ring's verdicts land on the Main collector while
 * a coach `trigger_measurement` polls from `Dispatchers.IO` under `runBlocking`. Every access to
 * [inFlight] is therefore synchronised — a `04 0e` iterating the map while another leg's `end()`
 * removes its token would otherwise throw `ConcurrentModificationException` on the Main collector,
 * which has no handler and takes the process down with it.
 */
class SpotMeasurementGate {
    /** A handle to one in-flight spot measurement. Identity is [id], **not** the mode, so two
     *  flows that somehow ran the same mode at once still could not end or abort each other. */
    data class Token internal constructor(internal val id: Int, val mode: Int)

    /** What the ring has said about one in-flight measurement, if anything. */
    private enum class Outcome { RUNNING, REJECTED, SUCCEEDED, FAILED }

    /** The measurements currently mid-poll, and what the ring has said about each. */
    private val inFlight = LinkedHashMap<Token, Outcome>()
    private val nextId = AtomicInteger(0)

    /** Arm the gate for one measurement and hand back its handle. */
    fun begin(mode: Int): Token {
        val token = Token(nextId.getAndIncrement(), mode)
        synchronized(inFlight) { inFlight[token] = Outcome.RUNNING }
        return token
    }

    /** Disarm [token] — and only [token]. Called on every exit path (success, timeout,
     *  rejection); the measurement that finishes first must not disarm one still running. */
    fun end(token: Token) {
        synchronized(inFlight) { inFlight.remove(token) }
    }

    /** Has the ring refused **this** measurement? What each poll loop's abort check asks, so a
     *  refusal can only ever end the measurement it actually named. */
    fun isRejected(token: Token): Boolean = synchronized(inFlight) { inFlight[token] == Outcome.REJECTED }

    /**
     * Has the ring *ended* **this** measurement, and did it call it a success? `null` while the
     * ring is still measuring — which is the normal answer for every family that never sends a
     * completion, so a poll loop that consults this keeps its window as the fallback bound.
     *
     * A rejection is deliberately not reported here: refusal is a start-time verdict with its own
     * abort path ([isRejected]), and folding the two together would let a refusal look like a
     * finished measurement whose samples are worth settling.
     */
    fun completedSuccessfully(token: Token): Boolean? = synchronized(inFlight) {
        when (inFlight[token]) {
            Outcome.SUCCEEDED -> true
            Outcome.FAILED -> false
            else -> null
        }
    }

    /** The ring refused [mode]. Honoured only by the in-flight measurement(s) actually running
     *  it — a late reply for a mode nothing is polling is ignored. */
    fun noteRejected(mode: Int) {
        synchronized(inFlight) {
            for (token in inFlight.keys) {
                if (token.mode == mode) inFlight[token] = Outcome.REJECTED
            }
        }
    }

    /**
     * The ring ended [mode] itself and reported [success]. Same ownership rule as [noteRejected]:
     * only the measurement(s) actually running that mode see it, and a refusal already recorded
     * for this token wins — a ring that refuses a start and then pushes a stray completion must
     * not turn its own refusal into a settled reading.
     */
    fun noteCompleted(mode: Int, success: Boolean) {
        synchronized(inFlight) {
            for (token in inFlight.keys) {
                if (token.mode == mode && inFlight[token] == Outcome.RUNNING) {
                    inFlight[token] = if (success) Outcome.SUCCEEDED else Outcome.FAILED
                }
            }
        }
    }

    /** The modes currently mid-poll. Read by tests; the coordinator drives everything through
     *  tokens. */
    val modesInFlight: Set<Int> get() = synchronized(inFlight) { inFlight.keys.map { it.mode }.toSet() }
}

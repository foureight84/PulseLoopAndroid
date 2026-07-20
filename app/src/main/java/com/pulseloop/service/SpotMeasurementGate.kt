package com.pulseloop.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Ported from SpotMeasurementGate in RingSyncCoordinator.swift (iOS `c8969a4`, riding the #82
 * sync): the fast-fail rule for a **refused** spot measurement.
 *
 * A YCBT ring answers `03 2f` with a verdict byte, and it refuses modes it has no sensor for
 * (the R99 refuses HRV `0x0a`). Without this gate the coordinator would poll a ring that already
 * said no for the full measurement window before reporting a generic failure.
 *
 * The danger in aborting on a device-pushed signal is aborting the *wrong* thing, so ownership
 * is by token, not by mode: a refusal may only ever cancel the measurement it names, while that
 * measurement is actually running. Tokens matter because spot measurements really do overlap —
 * the workout poll service fires on its own timer while the user (or the coach's action tools)
 * can start another reading, and nothing serializes those flows against each other.
 */
class SpotMeasurementGate {
    /** A handle to one in-flight spot measurement. Identity is [id], **not** the mode, so two
     *  flows that somehow ran the same mode at once still could not end or abort each other. */
    data class Token internal constructor(internal val id: Int, val mode: UByte)

    /** The measurements currently mid-poll, and whether the ring has refused each. */
    private val inFlight = LinkedHashMap<Token, Boolean>()
    private val nextId = AtomicInteger(0)

    /** Arm the gate for one measurement and hand back its handle. */
    fun begin(mode: UByte): Token {
        val token = Token(nextId.getAndIncrement(), mode)
        inFlight[token] = false
        return token
    }

    /** Disarm [token] — and only [token]. Called on every exit path (success, timeout,
     *  rejection); the measurement that finishes first must not disarm one still running. */
    fun end(token: Token) {
        inFlight.remove(token)
    }

    /** Has the ring refused **this** measurement? What each poll loop's abort check asks, so a
     *  refusal can only ever end the measurement it actually named. */
    fun isRejected(token: Token): Boolean = inFlight[token] ?: false

    /** The ring refused [mode]. Honoured only by the in-flight measurement(s) actually running
     *  it — a late reply for a mode nothing is polling is ignored. */
    fun noteRejected(mode: UByte) {
        for (token in inFlight.keys) {
            if (token.mode == mode) inFlight[token] = true
        }
    }

    /** The modes currently mid-poll. Read by tests; the coordinator drives everything through
     *  tokens. */
    val modesInFlight: Set<UByte> get() = inFlight.keys.map { it.mode }.toSet()
}

package com.pulseloop.ring

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Physical teardown outlives both the GATT disconnected callback and a worker-owned client. */
internal class CRPTeardownGate(
    private val nowNanos: () -> Long,
    private val timeoutMs: Long = 5_000L,
) {
    internal class Ticket(val address: String) {
        val ready = CompletableDeferred<Boolean>()
        var closedAtNanos: Long? = null
        var disconnected = false
        var scanAfterNanos: Long? = null
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, Ticket>()

    fun begin(address: String): Ticket = synchronized(lock) {
        // An older close completion must never release a newer teardown for the same ring.
        pending.remove(address)?.ready?.complete(false)
        Ticket(address).also { pending[address] = it }
    }

    fun isPending(address: String): Boolean = synchronized(lock) { address in pending }

    fun gattClosed(ticket: Ticket) = synchronized(lock) {
        if (pending[ticket.address] !== ticket) return@synchronized
        ticket.closedAtNanos = nowNanos()
        finishIfReady(ticket)
    }

    fun aclDisconnected(address: String) = synchronized(lock) {
        pending[address]?.let {
            it.disconnected = true
            finishIfReady(it)
        }
    }

    /**
     * The OS already reported no physical link when this teardown began (a watchdog or hung
     * CONNECTING reconnect), so no ACL_DISCONNECTED broadcast will follow. Only the GATT close
     * remains to wait for. A stale ticket cannot release a newer teardown.
     */
    fun linkAlreadyDown(ticket: Ticket) = synchronized(lock) {
        if (pending[ticket.address] !== ticket) return@synchronized
        ticket.disconnected = true
        finishIfReady(ticket)
    }

    fun bluetoothOff() = synchronized(lock) {
        pending.values.toList().forEach {
            it.disconnected = true
            finishIfReady(it)
        }
    }

    /** False means require a fresh scan, never blindly reuse the old link on timeout. */
    suspend fun await(address: String): Boolean {
        val ticket = synchronized(lock) { pending[address] } ?: return true
        if (synchronized(lock) { ticket.scanAfterNanos != null }) return false
        val ready = withTimeoutOrNull(timeoutMs) { ticket.ready.await() }
        if (ready != null) return ready
        return synchronized(lock) {
            if (pending[address] !== ticket) return@synchronized !pending.containsKey(address)
            ticket.scanAfterNanos = nowNanos()
            false
        }
    }

    /** Only a post-timeout, freshly observed advertisement can substitute for a missed broadcast. */
    fun freshAdvertisement(address: String, timestampNanos: Long): Boolean = synchronized(lock) {
        val ticket = pending[address] ?: return true
        val after = ticket.scanAfterNanos ?: return false
        val closed = ticket.closedAtNanos ?: return false
        if (timestampNanos <= maxOf(after, closed)) return false
        pending.remove(address)
        ticket.ready.complete(true)
        true
    }

    private fun finishIfReady(ticket: Ticket) {
        if (ticket.closedAtNanos != null && ticket.disconnected) {
            pending.remove(ticket.address)
            ticket.ready.complete(true)
        }
    }
}

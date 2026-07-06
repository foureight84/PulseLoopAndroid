package com.pulseloop.ui.components

import com.pulseloop.ring.RingConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from DeviceHeroStatusTests.swift (iOS #49). Pure logic — the status factory takes
 * epoch millis, so no Android framework is involved.
 */
class DeviceHeroStatusTest {

    private val now = 1_000_000_000L

    @Test
    fun `connected with battery shows chip and disconnect`() {
        val s = DeviceHeroStatus.make(
            state = RingConnectionState.CONNECTED, connectedName = "Jring 56ff",
            knownName = "Jring 56ff", batteryPercent = 82, lastSyncAt = null, now = now,
        )
        assertEquals("Jring 56ff", s.title)
        assertEquals("Connected", s.statusLine)
        assertEquals("82%", s.batteryText)
        assertEquals(DeviceHeroStatus.Action.DISCONNECT, s.action)
        assertEquals("Disconnect", s.actionTitle)
    }

    @Test
    fun `connected with null battery hides chip`() {
        val s = DeviceHeroStatus.make(
            state = RingConnectionState.CONNECTED, connectedName = "Colmi R11",
            knownName = "Colmi R11", batteryPercent = null, lastSyncAt = null, now = now,
        )
        assertNull(s.batteryText)
    }

    @Test
    fun `known but disconnected shows connect`() {
        val s = DeviceHeroStatus.make(
            state = RingConnectionState.DISCONNECTED, connectedName = null,
            knownName = "Jring 56ff", batteryPercent = null, lastSyncAt = null, now = now,
        )
        assertEquals("Jring 56ff", s.title)
        assertEquals("Disconnected", s.statusLine)
        assertEquals(DeviceHeroStatus.Action.CONNECT, s.action)
    }

    @Test
    fun `no device shows set up`() {
        val s = DeviceHeroStatus.make(
            state = RingConnectionState.IDLE, connectedName = null,
            knownName = null, batteryPercent = null, lastSyncAt = null, now = now,
        )
        assertEquals("No ring connected", s.title)
        assertEquals("No ring paired", s.statusLine)
        assertEquals(DeviceHeroStatus.Action.SET_UP, s.action)
    }

    @Test
    fun `sync text null when no samples`() {
        val s = DeviceHeroStatus.make(
            state = RingConnectionState.CONNECTED, connectedName = "Jring 56ff",
            knownName = "Jring 56ff", batteryPercent = 50, lastSyncAt = null, now = now,
        )
        assertNull(s.syncText)
    }

    @Test
    fun `sync text present with last sync`() {
        val s = DeviceHeroStatus.make(
            state = RingConnectionState.CONNECTED, connectedName = "Jring 56ff",
            knownName = "Jring 56ff", batteryPercent = 50,
            lastSyncAt = now - 120_000, now = now,
        )
        assertTrue(s.syncText?.startsWith("Synced") == true)
    }

    @Test
    fun `pending states disable the action`() {
        for (state in listOf(
            RingConnectionState.CONNECTING,
            RingConnectionState.RECONNECTING,
            RingConnectionState.SCANNING,
        )) {
            val s = DeviceHeroStatus.make(
                state = state, connectedName = null,
                knownName = "Jring 56ff", batteryPercent = null, lastSyncAt = null, now = now,
            )
            assertEquals(DeviceHeroStatus.Action.PENDING, s.action)
            assertEquals("Connecting…", s.actionTitle)
            assertEquals(false, s.actionEnabled)
        }
    }
}

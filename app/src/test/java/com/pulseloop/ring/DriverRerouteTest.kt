package com.pulseloop.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRerouteTest {

    /** Exactly what itspuia's R09 reported in `onSearchComplete` (issue #29 diagnostics). */
    private val r09Services = listOf(
        "0000fef5-0000-1000-8000-00805f9b34fb",
        "000056ff-0000-1000-8000-00805f9b34fb",
        "0000ff12-0000-1000-8000-00805f9b34fb",
        "00001800-0000-1000-8000-00805f9b34fb",
        "00001801-0000-1000-8000-00805f9b34fb",
        "0000180f-0000-1000-8000-00805f9b34fb",
        "0000180a-0000-1000-8000-00805f9b34fb",
        "00001812-0000-1000-8000-00805f9b34fb",
    )

    @Test
    fun `re-routes a 56ff ring stranded on the YCBT driver by the carousel pick`() {
        assertTrue(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = listOf(YCBTUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
            )
        )
    }

    @Test
    fun `leaves a ring that genuinely speaks its selected protocol alone`() {
        // Both services present: the YCBT pick is correct, so 56ff must not steal it.
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services + YCBTUUIDs.SERVICE,
                activeDeclaredServices = listOf(YCBTUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
            )
        )
    }

    @Test
    fun `no-ops when the jring driver is already installed`() {
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = listOf(RingUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.JRING,
            )
        )
    }

    @Test
    fun `does not fire for a ring with no 56ff service`() {
        // zaggash's CRP R11: the CRP block re-routes it; this one must stay out of the way.
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = listOf(CRPUUIDs.SERVICE, "0000180a-0000-1000-8000-00805f9b34fb"),
                activeDeclaredServices = listOf(CRPUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.CRP,
            )
        )
    }

    @Test
    fun `matches service UUIDs case-insensitively`() {
        assertTrue(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services.map { it.uppercase() },
                activeDeclaredServices = listOf(YCBTUUIDs.SERVICE.uppercase()),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
            )
        )
    }

    @Test
    fun `a driver declaring no service of its own is left alone`() {
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = emptyList(),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
            )
        )
    }
}
